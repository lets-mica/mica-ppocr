/*
 * Copyright (c) 2019-2026, dreamlu.net All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.dreamlu.mica.ai.ppocr.engine;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.OrtSession.SessionOptions.ExecutionMode;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import net.dreamlu.mica.ai.ppocr.config.PPOcrV6Config;
import net.dreamlu.mica.ai.ppocr.postprocessor.CtcLabelDecoder;
import net.dreamlu.mica.ai.ppocr.postprocessor.DbPostProcessor;
import net.dreamlu.mica.ai.ppocr.postprocessor.DocOrientationPostprocessor;
import net.dreamlu.mica.ai.ppocr.preprocessor.DetectionPreprocessor;
import net.dreamlu.mica.ai.ppocr.preprocessor.DocOrientationPreprocessor;
import net.dreamlu.mica.ai.ppocr.preprocessor.RecognitionPreprocessor;
import net.dreamlu.mica.ai.ppocr.utils.*;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.imgcodecs.Imgcodecs;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/**
 * PP-OCRv6 纯 ONNX Runtime 推理引擎。
 *
 * <p>典型用法：
 * <pre>{@code
 * PPOcrV6Config config = PPOcrV6Config.builder()
 *     .detModelPath("det.onnx")
 *     .recModelPath("rec.onnx")
 *     .recCharDictPath("dict.txt")
 *     .build();
 * try (PPOcrV6Engine engine = new PPOcrV6Engine(config)) {
 *     // 推荐：直接传文件路径 / byte[]，内部自动处理 native 内存释放
 *     List<PPOcrV6Result> results = engine.run("test_images/vehicle/vehicle1.png");
 * }
 * }</pre>
 *
 * <p>公开 API 只暴露 {@code byte[]} / {@link File} / {@link String} 三种入参，
 * 内部自动解码为 BGR Mat 并在方法返回时 release，调用方无需管理 native 内存。
 * 如确需复用已加载的 Mat，可使用 {@link #runMat(Mat)} 等方法。
 */
@Slf4j
public final class PPOcrV6Engine implements Closeable {
	private final OrtEnvironment env;
	private final OrtSession detSession;
	private final OrtSession recSession;
	private final OrtSession docOriSession;
	private final String detInputName;
	private final String recInputName;
	private final String docOriInputName;
	private final String detOutputName;
	private final String recOutputName;
	private final String docOriOutputName;

	private final DetectionPreprocessor detPre;
	private final DbPostProcessor detPost;
	private final RecognitionPreprocessor recPre;
	private final CtcLabelDecoder recPost;
	private final int recBatchSize;
	private final DocOrientationPreprocessor docOriPre;
	private final DocOrientationPostprocessor docOriPost;
	private final boolean docOriEnabled;

	private boolean closed = false;

	/**
	 * 创建 PP-OCRv6 推理引擎。
	 *
	 * @param config 配置参数
	 */
	public PPOcrV6Engine(PPOcrV6Config config) {
		requirePath(config.getDetModelPath(), "detModelPath");
		requirePath(config.getRecModelPath(), "recModelPath");
		requirePath(config.getRecCharDictPath(), "recCharDictPath");
		if (config.getRecBatchSize() < 1) {
			throw new IllegalArgumentException("recBatchSize must be >= 1, got " + config.getRecBatchSize());
		}
		if (config.getRecImageShape() == null || config.getRecImageShape().length != 3) {
			throw new IllegalArgumentException("recImageShape must be [C, H, W]");
		}
		this.docOriEnabled = config.isUseDocOrientationClassify();
		if (docOriEnabled) {
			if (config.getDocOrientationModelPath() == null || config.getDocOrientationModelPath().isEmpty()) {
				throw new IllegalArgumentException(
					"useDocOrientationClassify=true 时必须指定 docOrientationModelPath");
			}
			requirePath(config.getDocOrientationModelPath(), "docOrientationModelPath");
		}
		String[] providers = OrtProviders.resolve(!config.isPreferAccelerator());
		log.info("ONNX Runtime provider: {}", String.join(",", providers));
		this.env = OrtEnvironment.getEnvironment();

		OrtSession detSess = null;
		OrtSession recSess = null;
		OrtSession docOriSess = null;
		try (OrtSession.SessionOptions opts = new OrtSession.SessionOptions()) {
			// issue #14：OCR 输入分辨率随图片变化，CPU arena / 内存模式优化
			// 高水位会随新 shape 持续抬升且不归还 OS，内存受限环境（Docker）
			// 表现为内存持续增长直至 OOM。默认关闭两项：临时内存用完即释放，
			// 吞吐损失约 10%（可配置开启，仅当输入分辨率固定且追求极致吞吐时建议）。
			try {
				opts.setCPUArenaAllocator(config.isEnableCpuMemArena());
			} catch (OrtException e) {
				log.warn("设置 CPU memory arena 失败，使用默认值: {}", e.getMessage());
			}
			try {
				opts.setMemoryPatternOptimization(config.isEnableMemoryPattern());
			} catch (OrtException e) {
				log.warn("设置 memory pattern 失败，使用默认值: {}", e.getMessage());
			}
			try {
				opts.setExecutionMode(config.getExecMode());
			} catch (OrtException e) {
				log.warn("设置执行模式失败，使用默认值: {}", e.getMessage());
			}
			try {
				opts.setIntraOpNumThreads(Math.max(1, config.getIntraOpNumThreads()));
				opts.setInterOpNumThreads(Math.max(1, config.getInterOpNumThreads()));
			} catch (OrtException e) {
				log.warn("设置线程数失败，使用默认值: {}", e.getMessage());
			}

			// providers 解析后必须显式注册到 SessionOptions，
			// 否则 prefer-accelerator=true 时只打印日志、GPU 永不生效。
			try {
				if (providers.length > 0 && "CUDAExecutionProvider".equals(providers[0])) {
					opts.addCUDA(0);
					log.info("CUDAExecutionProvider 已注册到 SessionOptions (addCUDA(0))");
				} else if (providers.length > 0 && "CoreMLExecutionProvider".equals(providers[0])) {
					opts.addCoreML();
					log.info("CoreMLExecutionProvider 已注册到 SessionOptions");
				}
			} catch (OrtException e) {
				log.warn("注册加速 provider 失败，回退 CPU: {}", e.getMessage());
			}

			try {
				detSess = env.createSession(ModelResourceLoader.load(config.getDetModelPath()), opts);
				recSess = env.createSession(ModelResourceLoader.load(config.getRecModelPath()), opts);
				if (docOriEnabled) {
					docOriSess = env.createSession(ModelResourceLoader.load(config.getDocOrientationModelPath()), opts);
				}
			} catch (OrtException e) {
				silentClose(detSess);
				silentClose(recSess);
				silentClose(docOriSess);
				throw new RuntimeException("创建 ONNX Runtime 会话失败: " + e.getMessage(), e);
			}
		}
		this.detSession = detSess;
		this.recSession = recSess;
		this.docOriSession = docOriSess;

		try {
			this.detInputName = detSession.getInputNames().iterator().next();
			this.recInputName = recSession.getInputNames().iterator().next();
			this.detOutputName = detSession.getOutputNames().iterator().next();
			this.recOutputName = recSession.getOutputNames().iterator().next();
			this.detPre = new DetectionPreprocessor(config.getDetLimitSideLen(), config.getDetLimitType(), config.getDetMaxSideLimit());
			this.detPost = new DbPostProcessor(config.getDetThresh(), config.getDetBoxThresh(), config.getDetUnclipRatio(),
				1000, 3);
			this.recPre = new RecognitionPreprocessor(config.getRecImageShape()[1], 320, 3200);
			this.recPost = new CtcLabelDecoder(config.getRecCharDictPath());
			this.recBatchSize = config.getRecBatchSize();
			this.docOriPre = new DocOrientationPreprocessor();
			this.docOriPost = new DocOrientationPostprocessor(config.getDocOrientationThresh());
			if (docOriEnabled) {
				this.docOriInputName = docOriSession.getInputNames().iterator().next();
				this.docOriOutputName = docOriSession.getOutputNames().iterator().next();
			} else {
				this.docOriInputName = null;
				this.docOriOutputName = null;
			}
		} catch (RuntimeException e) {
			closeOnInitFailure(e);
			throw e;
		}

		log.info("PPOcrV6Engine 初始化完成: det={}, rec={}, vocab={}, docOri={}",
			this.detPre, this.recPre, this.recPost.vocabSize(), docOriEnabled ? "enabled" : "disabled");
	}

	private static void silentClose(OrtSession session) {
		if (session == null) {
			return;
		}
		try {
			session.close();
		} catch (OrtException e) {
			log.debug("关闭 session 失败: {}", e.getMessage());
		}
	}

	private static void requirePath(String path, String name) {
		if (path == null) {
			throw new IllegalArgumentException(name + " is null");
		}
		if (path.isEmpty()) {
			throw new IllegalArgumentException(name + " is empty");
		}
	}

	/**
	 * 将图片字节解码为 BGR Mat。
	 *
	 * <p>{@link MatOfByte} 构造时会分配 native 内存并把 byte[] 拷贝过去，
	 * imdecode 用完后即丢——必须显式 release，否则每次调用泄漏一个 mob 的 native buffer。
	 *
	 * @param imgBytes 图片字节
	 * @return BGR 格式的 Mat（非空）
	 * @throws IllegalArgumentException 字节为空或解码失败
	 */
	private static Mat decodeMat(byte[] imgBytes) {
		if (imgBytes == null || imgBytes.length == 0) {
			throw new IllegalArgumentException("imgBytes must not be empty");
		}
		MatOfByte mob = new MatOfByte(imgBytes);
		Mat mat;
		try {
			mat = Imgcodecs.imdecode(mob, Imgcodecs.IMREAD_COLOR);
		} finally {
			mob.release();
		}
		if (mat.empty()) {
			mat.release();
			throw new IllegalArgumentException("Failed to decode image from byte[] (unsupported format or corrupted data)");
		}
		return mat;
	}

	/**
	 * 从 Path 加载 BGR Mat。
	 *
	 * <p>默认 FileSystem 走 native OpenCV 读取（省内存，不经过 JVM heap 中转）；
	 * 非默认 FileSystem（ZIP / JIMFS / 内存 FS 等）自动退回 {@code Files.readAllBytes}。
	 *
	 * @param imagePath 图片路径
	 * @return BGR Mat（由调用方负责 release）
	 * @throws IllegalArgumentException 路径加载失败或解码失败
	 */
	private static Mat loadMat(Path imagePath) {
		try {
			// 默认 FileSystem → native 读取 OpenCV
			Mat mat = Imgcodecs.imread(imagePath.toFile().getAbsolutePath());
			if (mat.empty()) {
				mat.release();
				throw new IllegalArgumentException("Failed to load image: " + imagePath);
			}
			return mat;
		} catch (UnsupportedOperationException ignore) {
			// 非默认 FileSystem：退回字节流
			byte[] bytes;
			try {
				bytes = Files.readAllBytes(imagePath);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
			return decodeMat(bytes);
		}
	}

	private void closeOnInitFailure(Exception cause) {
		closeSessions(cause::addSuppressed);
		closed = true;
	}

	@Override
	public void close() {
		if (!closed) {
			closeSessions(e -> log.debug("关闭 session 失败: {}", e.getMessage()));
			closed = true;
			log.info("PPOcrV6Engine 已关闭");
		}
	}

	private void closeSessions(Consumer<OrtException> onError) {
		for (OrtSession session : new OrtSession[]{detSession, recSession, docOriSession}) {
			if (session == null) {
				continue;
			}
			try {
				session.close();
			} catch (OrtException e) {
				onError.accept(e);
			}
		}
	}

	// ==================================================================
	// 推荐公开 API：byte[] / File / String，内部自动管理 Mat 生命周期
	// ==================================================================

	private void requireOpen() {
		if (closed) {
			throw new IllegalStateException("PPOcrV6Engine has been closed and can no longer be used.");
		}
	}

	@Override
	public String toString() {
		return "PPOcrV6Engine(det=" + detPre + ", rec=" + recPre
			+ ", vocab=" + recPost.vocabSize() + ", closed=" + closed + ")";
	}

	/**
	 * 完整 OCR 流程：检测 → 排序 → 裁剪 → 识别。
	 *
	 * <p>内部自动解码为 BGR Mat 并在方法返回时 release，调用方无需管理 native 内存。
	 *
	 * @param imagePath 图片路径（PNG / JPG / BMP 等任意 OpenCV 支持的格式）
	 * @return 识别结果列表（按阅读顺序排列）
	 * @throws IllegalArgumentException 路径为空、文件不存在或解码失败
	 */
	public List<PPOcrV6Result> run(String imagePath) {
		if (imagePath == null || imagePath.isEmpty()) {
			throw new IllegalArgumentException("imagePath must not be empty");
		}
		return run(CollUtil.pathOf(imagePath));
	}

	/**
	 * 完整 OCR 流程：检测 → 排序 → 裁剪 → 识别。
	 *
	 * <p>内部自动解码为 BGR Mat 并在方法返回时 release，调用方无需管理 native 内存。
	 *
	 * @param imageFile 图片文件
	 * @return 识别结果列表（按阅读顺序排列）
	 * @throws IllegalArgumentException 文件不存在或解码失败
	 */
	public List<PPOcrV6Result> run(File imageFile) {
		if (imageFile == null) {
			throw new IllegalArgumentException("imageFile must not be null");
		}
		return run(imageFile.toPath());
	}

	/**
	 * 完整 OCR 流程：检测 → 排序 → 裁剪 → 识别。
	 *
	 * <p>兼容非默认文件系统的 {@link Path}（如 ZIP/JIMFS 等）：优先走 native 文件读取，
	 * 不支持的 FileSystem 自动退回 {@code Files.readAllBytes}。
	 *
	 * @param imagePath 图片路径
	 * @return 识别结果列表（按阅读顺序排列）
	 * @throws UncheckedIOException 读取字节时发生 IO 异常
	 */
	public List<PPOcrV6Result> run(Path imagePath) {
		if (imagePath == null) {
			throw new IllegalArgumentException("imagePath must not be null");
		}
		Mat mat = loadMat(imagePath);
		try {
			return runMat(mat);
		} finally {
			mat.release();
		}
	}

	/**
	 * 完整 OCR 流程：检测 → 排序 → 裁剪 → 识别。
	 *
	 * <p>内部自动解码为 BGR Mat 并在方法返回时 release，调用方无需管理 native 内存。
	 * 典型场景：Spring Boot 上传 {@code MultipartFile.getBytes()}。
	 *
	 * @param imgBytes 图片字节（PNG / JPG / BMP 等任意 OpenCV 支持的格式）
	 * @return 识别结果列表（按阅读顺序排列）
	 * @throws IllegalArgumentException 字节为空或解码失败
	 */
	public List<PPOcrV6Result> run(byte[] imgBytes) {
		Mat mat = decodeMat(imgBytes);
		try {
			return runMat(mat);
		} finally {
			mat.release();
		}
	}

	/**
	 * 文本检测（仅检测，不识别）。
	 *
	 * <p>内部自动解码为 BGR Mat 并在方法返回时 release。
	 *
	 * @param imagePath 图片路径
	 * @return boxes 形状 (N, 4, 2) int，scores 长度 N
	 */
	public DetectResult detect(String imagePath) {
		if (imagePath == null || imagePath.isEmpty()) {
			throw new IllegalArgumentException("imagePath must not be empty");
		}
		return detect(CollUtil.pathOf(imagePath));
	}

	/**
	 * 文本检测（仅检测，不识别）。
	 *
	 * @param imageFile 图片文件
	 * @return boxes 形状 (N, 4, 2) int，scores 长度 N
	 */
	public DetectResult detect(File imageFile) {
		if (imageFile == null) {
			throw new IllegalArgumentException("imageFile must not be null");
		}
		return detect(imageFile.toPath());
	}

	// ==================================================================
	// 内部/高级用法：Mat 入参，调用方负责 release
	// ==================================================================

	/**
	 * 文本检测（仅检测，不识别）。
	 *
	 * <p>兼容非默认文件系统的 {@link Path}（如 ZIP/JIMFS 等）。
	 *
	 * @param imagePath 图片路径
	 * @return boxes 形状 (N, 4, 2) int，scores 长度 N
	 * @throws UncheckedIOException 读取字节时发生 IO 异常
	 */
	public DetectResult detect(Path imagePath) {
		if (imagePath == null) {
			throw new IllegalArgumentException("imagePath must not be null");
		}
		Mat mat = loadMat(imagePath);
		try {
			return detectMat(mat);
		} finally {
			mat.release();
		}
	}

	/**
	 * 文本检测（仅检测，不识别）。
	 *
	 * <p>典型场景：Spring Boot 上传 {@code MultipartFile.getBytes()}。
	 *
	 * @param imgBytes 图片字节
	 * @return boxes 形状 (N, 4, 2) int，scores 长度 N
	 */
	public DetectResult detect(byte[] imgBytes) {
		Mat mat = decodeMat(imgBytes);
		try {
			return detectMat(mat);
		} finally {
			mat.release();
		}
	}

	/**
	 * 文本检测（Mat 版）。
	 *
	 * <p>仅适用于「已持有 Mat 并需复用」的高级场景（如同一图跑多次推理）；
	 * Mat 的 release 由调用方负责。一般场景请使用 {@link #detect(String)} / {@link #detect(byte[])} 等重载。
	 *
	 * @param imgBgr BGR 格式图像 (H, W, 3) uint8
	 * @return boxes 形状 (N, 4, 2) int，scores 长度 N
	 */
	public DetectResult detectMat(Mat imgBgr) {
		requireOpen();
		DetectionPreprocessor.Result prep = detPre.call(imgBgr);
		long[] shape = toLongArray(prep.shape());
		FloatBuffer buf = NdArrayUtils.toBuffer(prep.data());
		try (
			OnnxTensor input = OnnxTensor.createTensor(env, buf, shape);
			OrtSession.Result result = detSession.run(CollUtil.mapOf(detInputName, input))
		) {
			OnnxTensor outTensor = (OnnxTensor) result.get(detOutputName).get();
			Mat probMat = readProbToMat(outTensor);
			try {
				DbPostProcessor.Result post = detPost.call(probMat, prep.imgShape());
				return new DetectResult(post.boxes(), post.scores());
			} finally {
				probMat.release();
			}
		} catch (OrtException e) {
			throw new RuntimeException("det 推理失败: " + e.getMessage(), e);
		}
	}

	/**
	 * 文本识别（Mat 版，支持批量）。
	 *
	 * <p>仅适用于「已持有 Mat 并需复用」的高级场景（如同一图跑多次推理）；
	 * 每个 crop Mat 的 release 由调用方负责。一般场景请使用 {@link #run(String)} 等重载。
	 *
	 * @param imgList 裁剪后的 BGR 文本行图像列表
	 * @return texts 与 scores 长度一致
	 */
	public RecognizeResult recognizeMat(List<Mat> imgList) {
		requireOpen();
		int n = imgList.size();
		if (n == 0) {
			return new RecognizeResult(new String[0], new float[0]);
		}
		if (log.isDebugEnabled()) {
			Mat first = imgList.get(0);
			log.debug("rec 输入 #0: {}x{}x{} type={} (BGR)", first.rows(), first.cols(), first.channels(), first.type());
		}

		// 按宽高比排序：让 batch 内尺寸相近，padding 浪费最小
		Integer[] sortedOrder = new Integer[n];
		double[] ratios = new double[n];
		for (int i = 0; i < n; i++) {
			sortedOrder[i] = i;
			ratios[i] = (double) imgList.get(i).cols() / imgList.get(i).rows();
		}
		Arrays.sort(sortedOrder, Comparator.comparingDouble(i -> ratios[i]));

		String[] texts = new String[n];
		float[] scores = new float[n];

		for (int start = 0; start < n; start += recBatchSize) {
			int end = Math.min(start + recBatchSize, n);
			List<Mat> batch = new ArrayList<>(end - start);
			for (int i = start; i < end; i++) {
				batch.add(imgList.get(sortedOrder[i]));
			}
			RecognitionPreprocessor.Result prep = recPre.call(batch);
			long[] shape = toLongArray(prep.shape());
			FloatBuffer buf = NdArrayUtils.toBuffer(prep.data());
			try (
				OnnxTensor input = OnnxTensor.createTensor(env, buf, shape);
				OrtSession.Result result = recSession.run(CollUtil.mapOf(recInputName, input))
			) {
				OnnxTensor outTensor = (OnnxTensor) result.get(recOutputName).get();
				long[] outShape = outTensor.getInfo().getShape();
				int bOut = (int) outShape[0];
				int tOut = (int) outShape[1];
				int cOut = (int) outShape[2];
				float[] flat = new float[bOut * tOut * cOut];
				outTensor.getFloatBuffer().get(flat);
				CtcLabelDecoder.Result decoded = recPost.call(flat, bOut, tOut, cOut);
				for (int j = 0; j < decoded.texts().length; j++) {
					int orig = sortedOrder[start + j];
					texts[orig] = decoded.texts()[j];
					scores[orig] = decoded.scores()[j];
				}
			} catch (OrtException e) {
				throw new RuntimeException("rec 推理失败: " + e.getMessage(), e);
			}
		}
		return new RecognizeResult(texts, scores);
	}

	/**
	 * 完整 OCR 流程（Mat 版）：检测 → 排序 → 裁剪 → 识别。
	 *
	 * <p>仅适用于「已持有 Mat 并需复用」的高级场景（如同一图跑多次推理）；
	 * Mat 的 release 由调用方负责。一般场景请使用 {@link #run(String)} / {@link #run(byte[])} / {@link #run(Path)} 等重载。
	 *
	 * @param imgBgr BGR 格式图像 (H, W, 3) uint8
	 * @return 识别结果列表（按阅读顺序排列）；
	 * 启用 doc_ori 时每个 {@link PPOcrV6Result#rotatedDegrees()} 记录
	 * doc_ori 应用到原图的顺时针旋转角度（0/90/180/270）
	 */
	public List<PPOcrV6Result> runMat(Mat imgBgr) {
		requireOpen();
		// 文档方向分类（可选）：根据整图方向把图片旋转到正向，再走检测
		DocOriRotated rotatedInfo = classifyAndRotateDocOrientation(imgBgr);
		Mat rotated = rotatedInfo.mat();
		try {
			List<PPOcrV6Result> results = runOnMat(rotated);
			if (rotatedInfo.degrees() == 0) {
				return results;
			}
			// 把 doc_ori 应用的旋转角度带到每个 result，便于调用方把 box 投影回原图坐标系
			int deg = rotatedInfo.degrees();
			List<PPOcrV6Result> wrapped = new ArrayList<>(results.size());
			for (PPOcrV6Result r : results) {
				wrapped.add(new PPOcrV6Result(r.text(), r.score(), r.box(), deg));
			}
			return wrapped;
		} finally {
			if (rotated != imgBgr) {
				rotated.release();
			}
		}
	}

	/**
	 * 在已正向化的 Mat 上跑核心 OCR 流水线（检测 → 排序 → 裁剪 → 识别）。
	 * 内部负责所有 crop Mat 的 release。
	 */
	private List<PPOcrV6Result> runOnMat(Mat imgBgr) {
		DetectResult dr = detectMat(imgBgr);
		if (dr.boxes().length == 0) {
			return CollUtil.listOf();
		}

		int[][][] sortedBoxes = BoxUtil.sortQuadBoxes(dr.boxes());
		List<Mat> crops = CropUtil.cropByPolys(imgBgr, sortedBoxes);
		try {
			List<int[][]> validBoxes = new ArrayList<>();
			List<Mat> validCrops = new ArrayList<>();
			for (int i = 0; i < sortedBoxes.length; i++) {
				if (crops.get(i) != null) {
					validBoxes.add(sortedBoxes[i]);
					validCrops.add(crops.get(i));
				}
			}
			if (validCrops.isEmpty()) {
				return CollUtil.listOf();
			}

			RecognizeResult rr = recognizeMat(validCrops);
			List<PPOcrV6Result> results = new ArrayList<>(validBoxes.size());
			for (int i = 0; i < validBoxes.size(); i++) {
				results.add(new PPOcrV6Result(rr.texts()[i], rr.scores()[i], validBoxes.get(i)));
			}
			return results;
		} finally {
			for (Mat crop : crops) {
				if (crop != null) {
					crop.release();
				}
			}
		}
	}

	// ==================================================================
	// 内部工具
	// ==================================================================

	/**
	 * 文档方向分类 + 旋转：返回正向的 Mat 与应用到原图的顺时针旋转角度。
	 *
	 * <p>如果未启用或判定为 0°，返回原图（不旋转、不 release），degrees=0。
	 *
	 * @param imgBgr BGR 图像
	 * @return (旋转后 Mat, 应用到原图的顺时针旋转角度 0/90/180/270)；
	 * Mat 由调用方负责 release（不旋转时返回原图）
	 */
	private DocOriRotated classifyAndRotateDocOrientation(Mat imgBgr) {
		if (!docOriEnabled) {
			return new DocOriRotated(imgBgr, 0);
		}
		DocOrientationPostprocessor.Result ori;
		try {
			ori = classifyDocOrientationMat(imgBgr);
		} catch (RuntimeException e) {
			log.warn("文档方向分类失败，按 0° 处理: {}", e.getMessage());
			return new DocOriRotated(imgBgr, 0);
		}
		if (ori.degrees() == 0) {
			return new DocOriRotated(imgBgr, 0);
		}
		log.debug("文档方向分类: label={}, degrees={}, score={}", ori.label(), ori.degrees(), ori.score());
		// PaddleX 官方语义：label N 表示图片已经顺时针旋转了 N 度，
		// 要把图片摆正到 0°，需要**逆向**旋转同样的角度：
		//   90° (图片已顺时针 90°) → 逆时针 90° = ROTATE_90_COUNTERCLOCKWISE
		//   180°                       → ROTATE_180
		//   270° (图片已顺时针 270°)  → 逆时针 270° = 顺时针 90° = ROTATE_90_CLOCKWISE
		int code;
		switch (ori.degrees()) {
			case 90:
				code = Core.ROTATE_90_COUNTERCLOCKWISE;
				break;
			case 180:
				code = Core.ROTATE_180;
				break;
			case 270:
				code = Core.ROTATE_90_CLOCKWISE;
				break;
			default:
				code = -1;
				break;
		}
		if (code == -1) {
			return new DocOriRotated(imgBgr, 0);
		}
		Mat rotated = new Mat();
		try {
			Core.rotate(imgBgr, rotated, code);
			return new DocOriRotated(rotated, ori.degrees());
		} catch (RuntimeException | Error e) {
			rotated.release();
			throw e;
		}
	}

	/**
	 * 文档方向分类推理（仅返回结果，不做任何旋转）。
	 *
	 * @param imgBgr BGR 图像
	 * @return 分类结果
	 */
	private DocOrientationPostprocessor.Result classifyDocOrientationMat(Mat imgBgr) {
		DocOrientationPreprocessor.Result prep = docOriPre.call(imgBgr);
		long[] shape = toLongArray(prep.shape());
		FloatBuffer buf = NdArrayUtils.toBuffer(prep.data());
		try (
			OnnxTensor input = OnnxTensor.createTensor(env, buf, shape);
			OrtSession.Result result = docOriSession.run(CollUtil.mapOf(docOriInputName, input))
		) {
			OnnxTensor outTensor = (OnnxTensor) result.get(docOriOutputName).get();
			// 输出 shape: [1, 4]，展平为 length=4 的 logits
			FloatBuffer out = outTensor.getFloatBuffer();
			float[] logits = new float[4];
			out.get(logits);
			return docOriPost.call(logits);
		} catch (OrtException e) {
			throw new RuntimeException("doc_ori 推理失败: " + e.getMessage(), e);
		}
	}

	private long[] toLongArray(int[] arr) {
		long[] out = new long[arr.length];
		for (int i = 0; i < arr.length; i++) {
			out[i] = arr[i];
		}
		return out;
	}

	/**
	 * 读取 det 模型输出 [1, 1, H, W] → 2D Mat (H, W, CV_32F)。
	 *
	 * <p>合并原先的 readProb2D + probToMat 两步，消除 float[][] 中间层：
	 * tensor FloatBuffer → flat[] → Mat.put()，省掉 2 次冗余拷贝。
	 */
	private Mat readProbToMat(OnnxTensor tensor) throws OrtException {
		FloatBuffer buf = tensor.getFloatBuffer();
		long[] shape = tensor.getInfo().getShape();
		int h = (int) shape[2];
		int w = (int) shape[3];
		float[] data = new float[h * w];
		buf.get(data);
		Mat m = new Mat(h, w, org.opencv.core.CvType.CV_32F);
		try {
			m.put(0, 0, data);
			return m;
		} catch (RuntimeException | Error e) {
			m.release();
			throw e;
		}
	}

	// ==================================================================
	// 内部记录
	// ==================================================================

	/**
	 * 文档方向分类 + 旋转结果。
	 */
	@Getter
	@ToString
	@RequiredArgsConstructor
	@Accessors(fluent = true)
	private static class DocOriRotated {
		/**
		 * 正向化后的 Mat（不旋转时就是原图）
		 */
		private final Mat mat;
		/**
		 * doc_ori 应用到原图的顺时针旋转角度（0/90/180/270）
		 */
		private final int degrees;
	}

	/**
	 * 检测结果。
	 */
	@Getter
	@ToString
	@EqualsAndHashCode
	@RequiredArgsConstructor
	@Accessors(fluent = true)
	public static class DetectResult {
		/**
		 * 文本框 (N, 4, 2) int
		 */
		private final int[][][] boxes;
		/**
		 * 每框分数
		 */
		private final float[] scores;
	}

	/**
	 * 识别结果。
	 */
	@Getter
	@ToString
	@EqualsAndHashCode
	@RequiredArgsConstructor
	@Accessors(fluent = true)
	public static class RecognizeResult {
		/**
		 * 识别文本
		 */
		private final String[] texts;
		/**
		 * 每条文本的置信度
		 */
		private final float[] scores;
	}
}
