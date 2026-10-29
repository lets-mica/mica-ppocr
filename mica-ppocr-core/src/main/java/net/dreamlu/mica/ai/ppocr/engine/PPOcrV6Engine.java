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

import ai.onnxruntime.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import net.dreamlu.mica.ai.ppocr.config.PPOcrV6Config;
import net.dreamlu.mica.ai.ppocr.pdf.PdfOcrConfig;
import net.dreamlu.mica.ai.ppocr.pdf.PdfPageResult;
import net.dreamlu.mica.ai.ppocr.pdf.PdfTextExtractor;
import net.dreamlu.mica.ai.ppocr.pdf.PdfTextQuality;
import net.dreamlu.mica.ai.ppocr.postprocessor.CtcLabelDecoder;
import net.dreamlu.mica.ai.ppocr.postprocessor.DbDetParams;
import net.dreamlu.mica.ai.ppocr.postprocessor.DbPostProcessor;
import net.dreamlu.mica.ai.ppocr.postprocessor.DocOrientationPostprocessor;
import net.dreamlu.mica.ai.ppocr.preprocessor.DetectionPreprocessor;
import net.dreamlu.mica.ai.ppocr.preprocessor.DocOrientationPreprocessor;
import net.dreamlu.mica.ai.ppocr.preprocessor.RecognitionPreprocessor;
import net.dreamlu.mica.ai.ppocr.utils.*;
import nu.pattern.OpenCV;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.FloatBuffer;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.stream.Collectors;

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
	/**
	 * 关闭时等待在途推理结束的最长时间（毫秒）默认值。
	 *
	 * <p>取值理由：单张图片的端到端推理实测 200~800ms，1000ms 足以覆盖绝大多数
	 * 「关停时恰好有一个请求在途」的场景，从而走「干净关闭」路径（直接释放 native 会话）；
	 * 超过则进入「延迟释放」路径 —— 关停不再被在途推理拖住。
	 */
	private static final long DEFAULT_CLOSE_WAIT_TIMEOUT_MS = 1000L;
	/**
	 * ONNX Runtime 全局环境，进程内单例。
	 */
	private final OrtEnvironment env;
	/**
	 * det (文本检测) ONNX 会话。
	 */
	private final OrtSession detSession;
	/**
	 * rec (文本识别) ONNX 会话。
	 */
	private final OrtSession recSession;
	/**
	 * doc_ori (文档方向分类) ONNX 会话；未启用时为 {@code null}。
	 */
	private final OrtSession docOriSession;
	/**
	 * det 模型输入张量名称。
	 */
	private final String detInputName;
	/**
	 * rec 模型输入张量名称。
	 */
	private final String recInputName;
	/**
	 * doc_ori 模型输入张量名称；未启用时为 {@code null}。
	 */
	private final String docOriInputName;
	/**
	 * det 模型输出张量名称。
	 */
	private final String detOutputName;
	/**
	 * rec 模型输出张量名称。
	 */
	private final String recOutputName;
	/**
	 * doc_ori 模型输出张量名称；未启用时为 {@code null}。
	 */
	private final String docOriOutputName;

	/**
	 * 检测预处理：resize + 归一化 + HWC→NCHW。
	 */
	private final DetectionPreprocessor detPre;
	/**
	 * 检测后处理：DB 二值图 → contours → boxes（线程共享默认值）。
	 */
	private final DbPostProcessor detPost;
	/**
	 * 识别预处理：按宽高比分桶 + 动态 padding。
	 */
	private final RecognitionPreprocessor recPre;
	/**
	 * 识别后处理：CTC greedy decode → text + score。
	 */
	private final CtcLabelDecoder recPost;
	/**
	 * 识别批大小。
	 */
	private final int recBatchSize;
	/**
	 * 文档方向分类预处理。
	 */
	private final DocOrientationPreprocessor docOriPre;
	/**
	 * 文档方向分类后处理。
	 */
	private final DocOrientationPostprocessor docOriPost;
	/**
	 * 是否启用文档方向分类（由 {@link PPOcrV6Config#isUseDocOrientationClassify()} 决定）。
	 */
	private final boolean docOriEnabled;
	/**
	 * PDF 文本层抽取器（线程安全，复用）。
	 */
	private final PdfTextExtractor pdfExtractor = new PdfTextExtractor();
	/**
	 * PDF 双通道配置（构造期从 {@link PPOcrV6Config#getPdfOcrConfig()} 取，
	 * 为 null 时回落 {@link PdfOcrConfig#defaults()}）。
	 *
	 * <p>返回生效中的 PDF 双通道配置，即构造期从
	 * {@link PPOcrV6Config#getPdfOcrConfig()} 取到的值；
	 * 未显式配置时为 {@link PdfOcrConfig#defaults()}。
	 */
	@Getter
	private final PdfOcrConfig pdfOcrConfig;
	/**
	 * 生命周期读写锁。
	 *
	 * <p>推理入口持<b>读锁</b>（多线程可并发推理，互不阻塞）；{@link #close()} 持<b>写锁</b>，
	 * 从而保证「释放 native ONNX 会话」这一动作不会与在途推理并发发生。
	 *
	 * <p>为什么必须有这把锁：ONNX Runtime 的 {@code OrtSession} 释放后会释放底层 native
	 * 句柄。若在 {@code detSession.run(...)} / {@code recSession.run(...)} 执行期间释放，
	 * 在途 native 代码会访问已释放内存，表现为 <b>SIGSEGV（JVM 级崩溃，不可捕获）</b>，
	 * 而不是可处理的 Java 异常。单纯的 {@code volatile boolean closed} 标志只能保证
	 * 「关闭后不再接受新调用」，无法阻止「关闭与在途调用重叠」。
	 *
	 * <p>采用<b>公平模式</b>：保证 {@code close()} 不会被源源不断的推理请求饿死，
	 * 关闭语义可预期。
	 */
	private final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock(true);
	/**
	 * 生命周期读锁（推理路径持有）。
	 */
	private final Lock lifecycleReadLock = lifecycleLock.readLock();
	/**
	 * 生命周期写锁（{@link #close()} 持有）。
	 */
	private final Lock lifecycleWriteLock = lifecycleLock.writeLock();
	/**
	 * 引擎是否已关闭，{@link #close()} 幂等保护。
	 *
	 * <p>用 {@code volatile} 保证跨线程可见性：{@link #isClosed()} 在不持锁的情况下读取，
	 * 必须能看到其它线程写入的 {@code true}。
	 */
	@Getter
	private volatile boolean closed = false;
	/**
	 * 关闭流程是否已被发起（幂等闩锁）。
	 *
	 * <p>用 CAS 保证「只有第一次 {@link #close()} 调用执行真正的关闭流程」，
	 * 后续调用立即返回 —— 即使第一次还在等待在途推理。
	 */
	private final AtomicBoolean closeInitiated = new AtomicBoolean(false);
	/**
	 * 关闭时等待在途推理结束的最长时间（毫秒）。
	 *
	 * <p><b>包级可见且可变，仅供测试缩短，不属于公开 API。</b>
	 *
	 * <p>默认 1000ms：足以覆盖「单张图片推理」这类常见在途耗时（实测 200~800ms），
	 * 又不会让关停被长耗时任务（多页 PDF OCR）拖住。
	 */
	volatile long closeWaitTimeoutMillis = DEFAULT_CLOSE_WAIT_TIMEOUT_MS;
	/**
	 * 「延迟释放」是否已完成。
	 *
	 * <p>{@link #close()} 走超时路径时，native 会话由守护线程在在途推理结束后释放；
	 * 本标志在释放完成后置 true。
	 *
	 * <p><b>包级可见，仅供测试观测，不属于公开 API。</b>
	 *
	 * <p>存在的意义：若有人误删了延迟释放逻辑（只「跳过释放」而不补释放），
	 * native 内存会一直泄漏到进程退出 —— 这是<b>静默回归</b>，没有任何报错。
	 * 有这个标志，测试就能断言「延迟释放确实发生了」。
	 */
	private volatile boolean deferredReleaseCompleted = false;
	/**
	 * 引擎是否已完成预热。
	 *
	 * <p>只有 {@link #warmUp()} <b>成功</b>跑完 det + rec 全链路才置 true；
	 * 预热失败保持 false。用 {@code volatile} 保证跨线程可见性。
	 */
	private volatile boolean warmedUp = false;

	/**
	 * 预热是否成功。
	 *
	 * <p><b>包级可见，仅供测试观测，不属于公开 API。</b>
	 *
	 * <p>为什么不做成公开 getter：预热失败只记 WARN、引擎照常可用，
	 * 该状态几乎恒为 {@code true}，公开出去信息量低，且容易被误读成「引擎未就绪」。
	 * 但它必须保留 —— 这是唯一能锁住「构造器确实调用了 {@link #warmUp()}」的断言点：
	 * 一旦删掉，构造器里那行预热调用被误删时，测试会全部照常通过（静默回归）。
	 *
	 * @return 预热成功返回 true
	 */
	boolean isWarmedUp() {
		return warmedUp;
	}

	/**
	 * 创建 PP-OCRv6 推理引擎。
	 *
	 * <p>构造过程：
	 * <ol>
	 *     <li>校验配置（模型路径非空、recBatchSize ≥ 1、recImageShape 长度=3、doc_ori 配置自洽）</li>
	 *     <li>加载 OpenCV 原生库（{@link #ensureOpenCvLoaded()}，引擎的硬依赖）</li>
	 *     <li>按 {@link OrtProviders} 选择 EP 并填充 {@link OrtSession.SessionOptions}</li>
	 *     <li>创建 det / rec / (可选) doc_ori 三个 ONNX 会话</li>
	 *     <li>读取每个会话的输入输出名，构造预处理与后处理实例</li>
	 * </ol>
	 *
	 * <p>构造期失败时会自动关闭已创建的 ONNX 会话（避免 native 句柄泄漏），
	 * 抛出的 {@link RuntimeException} 包含原因。
	 *
	 * @param config 配置参数（不可为 null）
	 * @throws IllegalArgumentException 配置不合法（路径为空、批次/形状非法、doc_ori 配置未自洽）
	 * @throws IllegalStateException    OpenCV 原生库加载失败（引擎无 OpenCV 不可用，构造期即失败）
	 * @throws RuntimeException         创建 ONNX 会话失败
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
		PdfOcrConfig configuredPdf = config.getPdfOcrConfig();
		this.pdfOcrConfig = configuredPdf == null ? PdfOcrConfig.defaults() : configuredPdf;
		if (docOriEnabled) {
			if (config.getDocOrientationModelPath() == null || config.getDocOrientationModelPath().isEmpty()) {
				throw new IllegalArgumentException(
					"useDocOrientationClassify=true 时必须指定 docOrientationModelPath");
			}
			requirePath(config.getDocOrientationModelPath(), "docOrientationModelPath");
		}
		// OpenCV 原生库是引擎的硬依赖（预处理 / DB 后处理 / 裁剪全部走 org.opencv.*），
		// 在此加载，保证「构造成功 ⇒ 后续推理不会撞 UnsatisfiedLinkError」。
		ensureOpenCvLoaded();
		this.env = OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR);

		OrtSession detSess = null;
		OrtSession recSess = null;
		OrtSession docOriSess = null;
		try (OrtSession.SessionOptions opts = new OrtSession.SessionOptions()) {
			// provider 选择 + SessionOptions 通用配置（arena / memory pattern /
			// exec mode / 线程数）+ 加速 EP 注册，全部下沉到 OrtProviders.apply()。
			// 任何一步失败都只 warn 并保留 ORT 默认值，session 仍可创建。
			// issue #14：OCR 输入分辨率随图片变化，CPU arena / 内存模式默认关闭，
			// 避免 arena 高水位持续抬升导致 Docker OOM，吞吐损失约 10%。
			OrtProviders.apply(opts, config);
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
			this.detPost = new DbPostProcessor(DbDetParams.of(config.getDetThresh(), config.getDetBoxThresh(), config.getDetUnclipRatio()));
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

		// 预热：把 ONNX Runtime 的计算图优化、内存池初始化与 JIT 提前到构造期，
		// 消除「容器刚起来时第一个请求显著慢于后续」。
		// 失败只记 WARN，不影响引擎可用性（模型 / native / session 已在上面校验过）。
		// 预热：把 ONNX Runtime 的计算图优化、内存池初始化与 JIT 提前到构造期，
		// 消除「容器刚起来时第一个请求显著慢于后续」。
		// 失败只记 WARN，不影响引擎可用性（模型 / native / session 已在上面校验过）。
		warmUp();
	}

	/**
	 * 加载 OpenCV 原生库（openpnp 的 {@code nu.pattern.OpenCV}）。
	 *
	 * <p><b>为什么在引擎构造期加载</b>：openpnp 的 OpenCV 不会随 JVM 启动自动加载 native 库，
	 * 首次调用 {@code Imgproc.xxx} / {@code Core.xxx} 等 native 方法时会抛
	 * {@link UnsatisfiedLinkError}。把这些加载逻辑交给调用方（starter / plugin / 业务代码）
	 * 各自记住，是典型的「谁忘了谁炸」陷阱 —— 故障会推迟到<b>首个推理请求</b>才暴露。
	 * 由引擎在构造期独占加载，可保证「构造成功 ⇒ 推理不会撞 UnsatisfiedLinkError」。
	 *
	 * <p><b>为什么能重复调用</b>：openpnp 内部用 holder-class 单例实现（{@code OpenCV$LocalLoader$Holder}
	 * 的静态字段），JVM 类初始化语义保证 {@code System.load} 只执行一次 ⇒
	 * <b>幂等且线程安全</b>，多处调用 / 多线程并发调用都安全。
	 *
	 * <p><b>注意</b>：不要自己实现「if (!loaded) load()」的标志位 —— openpnp 每次都会把 native
	 * 二进制解压到临时目录后 {@code System.load(path)}，而 {@code System.load} 按<b>路径</b>记账，
	 * 解压到不同临时路径会重复加载同一份 native，造成两份静态状态分裂。
	 * 直接调 {@code OpenCV.loadLocally()} 即可。
	 *
	 * <p><b>失败即失败</b>：引擎没有 OpenCV 根本不可用，因此这里 fail fast，
	 * 构造期直接抛出（而不是等到首个请求）。异常类型用 {@link IllegalStateException}，
	 * 并保留原始 cause（含平台不支持 / 解压失败等细节）。
	 */
	private static void ensureOpenCvLoaded() {
		try {
			OpenCV.loadLocally();
		} catch (Throwable t) {
			// 必须 catch Throwable：UnsatisfiedLinkError 是 Error 而非 Exception，
			// 只 catch Exception 会让它原样穿透，调用方拿不到可读的失败原因。
			throw new IllegalStateException("OpenCV 原生库加载失败，PP-OCR 引擎不可用: " + t.getMessage(), t);
		}
	}

	/**
	 * 静默关闭 ONNX 会话（用于构造失败时的清理路径）。
	 *
	 * <p>{@code session} 为 {@code null} 时直接返回；关闭异常仅在 debug 级别记录，不上抛。
	 *
	 * @param session 待关闭的会话（可为 {@code null}）
	 */
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

	/**
	 * 校验模型路径配置项非空。
	 *
	 * @param path 路径值
	 * @param name 配置项名（用于错误信息）
	 * @throws IllegalArgumentException 路径为 null 或空字符串
	 */
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
	 * <p>默认 FileSystem 走 native OpenCV 读取（省内存，不经过 JVM heap 中转）。
	 *
	 * @param imagePath 图片路径
	 * @return BGR Mat（由调用方负责 release）
	 * @throws IllegalArgumentException 路径加载失败或解码失败
	 */
	private static Mat loadMat(Path imagePath) {
		Mat mat = Imgcodecs.imread(imagePath.toFile().getAbsolutePath());
		if (mat.empty()) {
			mat.release();
			throw new IllegalArgumentException("Failed to load image: " + imagePath);
		}
		return mat;
	}

	/**
	 * 构造期失败时的清理入口：关闭已创建的 ONNX 会话，并把所有关闭异常作为抑制异常挂到 cause 上抛出。
	 *
	 * @param cause 引发清理的原始异常（不可为 null）
	 */
	private void closeOnInitFailure(Exception cause) {
		closeSessions(cause::addSuppressed);
		closed = true;
	}

	/**
	 * 关闭引擎并释放所有 ONNX 会话。
	 *
	 * <p>幂等：多次调用只有第一次会真正执行关闭流程，后续调用立即返回
	 * （即使第一次还在等待在途推理结束）。
	 *
	 * <p><b>并发语义：有界等待 + 超时延迟释放</b>。
	 * 本方法最多等待 {@link #closeWaitTimeoutMillis}（默认 1 秒）让在途推理自然结束：
	 *
	 * <ul>
	 *   <li><b>未超时</b>：持生命周期<b>写锁</b>释放全部 native 会话。
	 *       「释放 native 句柄」永远不会与在途推理重叠。</li>
	 *   <li><b>已超时</b>：引擎立即「退役」—— {@link #isClosed()} 变为 true、新请求被拒，
	 *       但<b>不释放</b> native 会话（在途推理还在用它，强行释放就是 use-after-free），
	 *       释放动作延后到在途推理结束后由守护线程完成。
	 *       ⇒ 关停不会被长耗时推理（多页 PDF OCR）拖住。</li>
	 * </ul>
	 *
	 * <p><b>为什么不能「不等就直接释放」</b>：ONNX Runtime 的 {@code OrtSession} 释放后，
	 * 底层 native 句柄被 free；在途 native 代码访问已释放内存 ⇒ <b>SIGSEGV（JVM 级崩溃，
	 * 不可捕获）</b>，而不是可处理的 Java 异常。有界等待 + 延迟释放是
	 * 「不拖关停」与「不崩」的兼容解。
	 *
	 * <p><b>超时后可能发生什么</b>：在途推理在下一个「引擎状态检查点」会收到
	 * {@link IllegalStateException}（引擎已关闭）而中断 —— 关停场景下属预期行为。
	 */
	@Override
	public void close() {
		if (!closeInitiated.compareAndSet(false, true)) {
			// 幂等：只有第一次调用执行真正的关闭流程
			return;
		}
		boolean acquired;
		try {
			acquired = lifecycleWriteLock.tryLock(closeWaitTimeoutMillis, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			acquired = false;
		}
		if (!acquired) {
			// 超时：引擎立即退役，但【不】释放 native 会话 ——
			// 在途推理还在用它，强行释放就是 use-after-free ⇒ SIGSEGV。
			// 释放动作延后到在途推理结束后由守护线程完成。
			closed = true;
			scheduleDeferredSessionRelease();
			log.warn("PPOcrV6Engine 关闭等待超时（仍有 {} 个推理在途），"
				+ "native 会话将在在途推理结束后由守护线程释放", activeReaderCount());
			return;
		}
		try {
			// 先置关闭标记再释放会话：万一会话释放失败，引擎也处于「已退役」状态，
			// 不会有新请求摸到半死不活的 native 会话。
			closed = true;
			closeSessions(e -> log.debug("关闭 session 失败: {}", e.getMessage()));
			log.info("PPOcrV6Engine 已关闭");
		} finally {
			lifecycleWriteLock.unlock();
		}
	}

	/**
	 * 安排在「在途推理全部结束」后释放 native 会话。
	 *
	 * <p>由 {@link #close()} 的超时路径调用：此时写锁没能拿到（还有推理在途），
	 * <b>绝不能</b>在此刻释放 native 会话。本方法起一个<b>守护线程</b>阻塞等待写锁，
	 * 等在途推理自然结束后再释放 —— 既不崩，也不泄漏（进程存活时一定会释放；
	 * 进程退出时由 OS 回收）。
	 *
	 * <p>守护线程：JVM 退出时不会因它而挂起。
	 */
	private void scheduleDeferredSessionRelease() {
		Thread thread = new Thread(() -> {
			lifecycleWriteLock.lock();
			try {
				closeSessions(e -> log.debug("关闭 session 失败: {}", e.getMessage()));
				deferredReleaseCompleted = true;
				log.info("PPOcrV6Engine native 会话已在在途推理结束后释放");
			} catch (Throwable e) {
				log.warn("延迟释放 native 会话失败: {}", e.toString());
			} finally {
				lifecycleWriteLock.unlock();
			}
		}, "mica-ppocr-close-deferred");
		thread.setDaemon(true);
		thread.start();
	}

	/**
	 * 预热：用一张合成小图跑通「检测 → 识别」全链路，触发 ONNX Runtime 的计算图优化、
	 * 内存池初始化与 JIT 编译。
	 *
	 * <p><b>由构造器自动调用</b>，不需要（也不应当）由调用方显式触发 ——
	 * 预热是引擎的<b>初始化语义</b>，不是可选开关：每个引擎实例都该在「构造完成」时
	 * 就绪，而不是把首个慢请求留给用户。
	 *
	 * <p><b>为什么需要</b>：ONNX Runtime 会在每个 Session 的<b>首次</b>推理时做计算图优化
	 * （常量折叠、算子融合等）并初始化 arena / 内存池，因此首次推理显著慢于后续。
	 * 「滚动更新时新实例被首个慢请求打爆」是典型生产事故，构造期预热即可消除。
	 *
	 * <p><b>合成图</b>：程序化绘制的几何笔画阵列（纯 OpenCV，零字体依赖）。
	 * 用真实笔画而非纯白图，保证 det 能检出文本块、rec 能真正进入识别分支 ——
	 * 纯白图只会预热 det 一半，rec 的首次开销仍在。
	 *
	 * <p><b>失败处理</b>：预热失败只记 WARN，<b>不影响引擎正常使用</b>
	 * （引擎可用性在构造期已校验过）；预热是优化手段，不该让应用起不来。
	 *
	 * <p>耗时（独立 JVM 实测）：tiny 约 60ms / small 约 88ms。
	 */
	private void warmUp() {
		lifecycleReadLock.lock();
		try {
			requireOpen();
			Mat warmUpImage = createWarmUpImage();
			long startNanos = System.nanoTime();
			try {
				// 走公开入口，顺带把 doc_ori 分支也预热到。
				// runMat 内部会再次取读锁 —— 读锁重入安全（见 AGENTS.md「嵌套取锁是安全的」）。
				runMat(warmUpImage, null);
				warmedUp = true;
			} catch (Throwable e) {
				// 预热是「尽力而为」的优化手段：失败只记 WARN，绝不让应用起不来。
				// 捕获 Throwable（含 UnsatisfiedLinkError / OutOfMemoryError 等 Error）
				// 是刻意的 —— 引擎可用性已在构造期校验过，此处不该再抛。
				log.warn("PPOcrV6Engine 预热失败，不影响引擎正常使用: {}", e.toString());
			} finally {
				warmUpImage.release();
				log.info("PPOcrV6Engine 预热完成，耗时 {} ms", (System.nanoTime() - startNanos) / 1_000_000);
			}
		} finally {
			lifecycleReadLock.unlock();
		}
	}

	/**
	 * 构造预热用的合成图：程序化绘制的「类汉字笔画」阵列。
	 *
	 * <p><b>为什么不用纯白图</b>：实测纯白图 det 检出 <b>0 个框</b>
	 * ⇒ rec 分支根本不会执行到，等于只预热了一半（rec 的图优化 / 内存池仍留在首个真实请求上）。
	 *
	 * <p><b>为什么不用 AWT 文本</b>：文本渲染依赖系统字体，极简容器里可能渲染成空白，
	 * 同样会让 det 检出 0 框。这里改用纯几何笔画（短竖 + 短横）拼出「字」，
	 * <b>零字体依赖</b>，无头 / 极简环境同样可靠。
	 *
	 * <p><b>为什么是 64×64（而不是更小 / 更大）</b>：预热图越大越贵，
	 * 而「消掉首调惩罚」的收益却有天花板。实测（tiny 档，福袋码图 1200×1500，各 7 次独立 JVM）：
	 * <table border="1">
	 *   <caption>预热图规格对照（tiny 档，福袋码图 1200×1500）</caption>
	 *   <tr><th>预热图</th><th>det 输入</th><th>预热成本</th><th>预热后残留惩罚</th></tr>
	 *   <tr><td>不预热</td><td>—</td><td>0</td><td>113ms</td></tr>
	 *   <tr><td>320×320 / 6 框</td><td>320×320</td><td>188ms</td><td>17ms</td></tr>
	 *   <tr><td>120×120 / 2 框</td><td>128×128</td><td>72ms</td><td>44ms</td></tr>
	 *   <tr><td><b>64×64 / 1 框</b></td><td><b>64×64</b></td><td><b>60ms</b></td><td><b>23ms</b></td></tr>
	 * </table>
	 * 320×320 只多消掉约 20ms 惩罚，却要多付 120ms 成本 ⇒ 对「稳态请求 ≤ 500ms」的快接口，
	 * 大预热图是净负的；64×64 用 60ms 消掉了 90ms 惩罚（消掉 80%）。
	 *
	 * <p><b>为什么正好取 det 的 limit_side_len（64）</b>：det 预处理是
	 * {@code limitType=min, limitSideLen=64} —— <b>短边 &lt; 64 才放大到 64，短边 ≥ 64 原样进模型</b>。
	 * 取 64×64 ⇒ 预热图尺寸 = det 实际输入尺寸，<b>零插值、笔画逐像素精确</b>，
	 * 且不依赖「小图会被放大」这条容易被改掉的实现细节（60×60 虽也落到 64×64，
	 * 但要靠 1.067 倍放大才凑到，笔画被重采样过）。
	 *
	 * <p><b>为什么不能再小（30×30 为什么不行）</b>：30×30 靠「被放大到 64」才能被检出，
	 * det 参数一换成 PaddleOCR 常见的 {@code limitType=max, limitSideLen=960}，
	 * 30×30 会原样进模型 ⇒ <b>检出 0 框</b> ⇒ rec 分支不被预热 ⇒ 静默退化成「只预热一半」。
	 * 实测两种 det 参数下的检出框数：30×30 = 1 / <b>0</b>，60×60 = 1 / 1，
	 * 64×64 = 1 / 1，120×120 = 2 / 2。
	 * ⇒ 该约束由
	 * {@code PPOcrV6EngineWarmUpTest#warmUpImageIsDetectableUnderAnyDetLimitConfig()} 锁住。
	 *
	 * <p>只用 OpenCV 绘图，不引入 AWT / 字体依赖。
	 *
	 * <p>包级可见，便于测试断言「预热图能被 det 检出」，从而保证 rec 分支确实被预热到。
	 *
	 * @return BGR Mat，由调用方负责 release
	 */
	static Mat createWarmUpImage() {
		// OpenCV 原生库已在构造期由 ensureOpenCvLoaded() 加载（幂等），此处无需再加载
		int width = 64;
		int height = 64;
		Mat image = new Mat(height, width, CvType.CV_8UC3, new Scalar(255, 255, 255));
		// 1 行 2 个「字位」，每个字位 = 一竖一横两笔（实测 det 检出 1 个框）
		for (int col = 0; col < 2; col++) {
			int x = 9 + col * 32;
			int y = 24;
			Imgproc.rectangle(image, new Point(x, y), new Point(x + 1, y + 15), new Scalar(0, 0, 0), -1);
			Imgproc.rectangle(image, new Point(x, y + 8), new Point(x + 13, y + 9), new Scalar(0, 0, 0), -1);
		}
		return image;
	}

	/**
	 * 当前持有生命周期读锁的线程数（即正在进行或即将进行推理的调用数）。
	 *
	 * <p>包级可见，<b>仅供测试观察并发语义</b>，不属于公开 API。
	 * 测试用它精确判断「推理线程是否已经进入引擎内部」，从而避免用 sleep 猜时机。
	 *
	 * @return 当前读锁持有者数量
	 */
	int activeReaderCount() {
		return lifecycleLock.getReadLockCount();
	}

	/**
	 * 设置关闭时等待在途推理的最长时间（毫秒）。
	 *
	 * <p><b>包级可见，仅供测试缩短，不属于公开 API。</b>
	 *
	 * <p>测试用它把等待压到毫秒级，从而<b>确定性地</b>覆盖「超时 → 延迟释放」路径，
	 * 而不必依赖「让推理跑够久」来碰运气。
	 *
	 * @param closeWaitTimeoutMillis 等待毫秒数（不可为负）
	 */
	void closeWaitTimeoutMillis(long closeWaitTimeoutMillis) {
		if (closeWaitTimeoutMillis < 0) {
			throw new IllegalArgumentException("closeWaitTimeoutMillis must be >= 0, got " + closeWaitTimeoutMillis);
		}
		this.closeWaitTimeoutMillis = closeWaitTimeoutMillis;
	}

	/**
	 * 「延迟释放」是否已完成。
	 *
	 * <p>包级可见，仅供测试观测，不属于公开 API。
	 *
	 * @return 超时路径下 native 会话已被守护线程释放返回 true；未走超时路径返回 false
	 */
	boolean isDeferredReleaseCompleted() {
		return deferredReleaseCompleted;
	}

	/**
	 * 依次关闭 det / rec / doc_ori 三个 ONNX 会话，跳过 null 项；每个会话的关闭异常交由 {@code onError} 处理。
	 *
	 * @param onError 关闭异常回调（不会为 null）
	 */
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

	/**
	 * 校验引擎未关闭。所有公开推理入口都应在第一行调用此方法。
	 *
	 * @throws IllegalStateException 引擎已 {@link #close()} 关闭
	 */
	private void requireOpen() {
		if (closed) {
			throw new IllegalStateException("PPOcrV6Engine has been closed and can no longer be used.");
		}
	}

	@Override
	public String toString() {
		return "PPOcrV6Engine(det=" + detPre + ", rec=" + recPre
			+ ", vocab=" + recPost.vocabSize() + ", docOri=" + (docOriEnabled ? "enabled" : "disabled")
			+ ", closed=" + closed + ")";
	}

	/**
	 * 完整 OCR 流程：检测 → 排序 → 裁剪 → 识别。
	 *
	 * <p>内部自动解码为 BGR Mat 并在方法返回时 release，调用方无需管理 native 内存。
	 *
	 * <p>如果文件内容以 {@code %PDF-} 魔数开头，自动按 PDF 双通道处理并平铺所有页结果。
	 *
	 * @param imagePath 图片或 PDF 路径
	 * @return 识别结果列表（按阅读顺序排列）
	 * @throws IllegalArgumentException 路径为空、文件不存在或解码失败
	 */
	public List<PPOcrV6Result> run(String imagePath) {
		if (imagePath == null || imagePath.isEmpty()) {
			throw new IllegalArgumentException("imagePath must not be empty");
		}
		return run(CollUtil.pathOf(imagePath), null);
	}

	/**
	 * 完整 OCR 流程：检测 → 排序 → 裁剪 → 识别。
	 *
	 * <p>内部自动解码为 BGR Mat 并在方法返回时 release，调用方无需管理 native 内存。
	 *
	 * <p>如果文件内容以 {@code %PDF-} 魔数开头，自动按 PDF 双通道处理。
	 *
	 * @param imagePath 图片或 PDF 路径
	 * @param detParams DB 参数；{@code null} 表示使用引擎默认
	 * @return 识别结果列表（按阅读顺序排列）
	 * @throws IllegalArgumentException 路径为空、文件不存在或解码失败
	 */
	public List<PPOcrV6Result> run(String imagePath, DbDetParams detParams) {
		if (imagePath == null || imagePath.isEmpty()) {
			throw new IllegalArgumentException("imagePath must not be empty");
		}
		return run(CollUtil.pathOf(imagePath), detParams);
	}

	/**
	 * 完整 OCR 流程：检测 → 排序 → 裁剪 → 识别。
	 *
	 * <p>内部自动加载为 BGR Mat 并在方法返回时 release，调用方无需管理 native 内存。
	 *
	 * <p>如果文件内容以 {@code %PDF-} 魔数开头，自动按 PDF 双通道处理并平铺所有页结果。
	 *
	 * @param imageFile 图片或 PDF 文件
	 * @return 识别结果列表（按阅读顺序排列）
	 * @throws IllegalArgumentException 文件不存在或解码失败
	 */
	public List<PPOcrV6Result> run(File imageFile) {
		if (imageFile == null) {
			throw new IllegalArgumentException("imageFile must not be null");
		}
		return run(imageFile.toPath(), null);
	}

	/**
	 * 完整 OCR 流程：检测 → 排序 → 裁剪 → 识别。
	 *
	 * <p>内部自动加载为 BGR Mat 并在方法返回时 release，调用方无需管理 native 内存。
	 *
	 * <p>如果文件内容以 {@code %PDF-} 魔数开头，自动按 PDF 双通道处理并平铺所有页结果。
	 *
	 * @param imageFile 图片或 PDF 文件
	 * @param detParams DB 参数；{@code null} 表示使用引擎默认
	 * @return 识别结果列表（按阅读顺序排列）
	 * @throws IllegalArgumentException 文件不存在或解码失败
	 */
	public List<PPOcrV6Result> run(File imageFile, DbDetParams detParams) {
		if (imageFile == null) {
			throw new IllegalArgumentException("imageFile must not be null");
		}
		return run(imageFile.toPath(), detParams);
	}

	/**
	 * 完整 OCR 流程：检测 → 排序 → 裁剪 → 识别。
	 *
	 * <p>内部自动加载为 BGR Mat 并在方法返回时 release，调用方无需管理 native 内存。
	 *
	 * <p>如果文件内容以 {@code %PDF-} 魔数开头，自动按 PDF 双通道处理并平铺所有页结果。
	 *
	 * @param imagePath 图片或 PDF 路径
	 * @return 识别结果列表（按阅读顺序排列）
	 * @throws IllegalArgumentException 路径为 null、文件不存在或解码失败
	 * @throws UncheckedIOException 读取字节时发生 IO 异常
	 */
	public List<PPOcrV6Result> run(Path imagePath) {
		return run(imagePath, null);
	}

	/**
	 * 完整 OCR 流程：检测 → 排序 → 裁剪 → 识别。
	 *
	 * <p>内部自动加载为 BGR Mat 并在方法返回时 release，调用方无需管理 native 内存。
	 *
	 * <p>如果文件内容以 {@code %PDF-} 魔数开头，自动按 PDF 双通道处理并平铺所有页结果。
	 *
	 * @param imagePath 图片或 PDF 路径
	 * @param detParams DB 参数；{@code null} 表示使用引擎默认
	 * @return 识别结果列表（按阅读顺序排列）
	 * @throws IllegalArgumentException 路径为 null、文件不存在或解码失败
	 * @throws UncheckedIOException 读取字节时发生 IO 异常
	 */
	public List<PPOcrV6Result> run(Path imagePath, DbDetParams detParams) {
		if (imagePath == null) {
			throw new IllegalArgumentException("imagePath must not be null");
		}
		lifecycleReadLock.lock();
		try {
			requireOpen();
			// 默认 FileSystem：先读头部字节嗅探 PDF，避免 OpenCV 解析 PDF 失败
			if (imagePath.getFileSystem().equals(FileSystems.getDefault())) {
				byte[] head;
				try {
					head = readHeadBytes(imagePath, 1024 + 8);
				} catch (IOException ignore) {
					// 嗅探失败时按图片走（保持向后兼容）
					head = null;
				}
				if (PdfMagicDetector.isPdf(head)) {
					try {
						return flattenPdfPages(runPdfBytes(Files.readAllBytes(imagePath), pdfOcrConfig, detParams));
					} catch (IOException e) {
						throw new UncheckedIOException(e);
					}
				}
			}
			Mat mat = loadMat(imagePath);
			try {
				return runMat(mat, detParams);
			} finally {
				mat.release();
			}
		} finally {
			lifecycleReadLock.unlock();
		}
	}

	/**
	 * 完整 OCR 流程：检测 → 排序 → 裁剪 → 识别。
	 *
	 * <p>内部自动解码为 BGR Mat 并在方法返回时 release，调用方无需管理 native 内存。
	 * 典型场景：Spring Boot 上传 {@code MultipartFile.getBytes()}。
	 *
	 * <p>自动嗅探输入：字节流以 {@code %PDF-} 魔数开头时，自动按 PDF 双通道处理并
	 * 平铺所有页的文本框列表。其它格式按图片走。
	 *
	 * <p>PDF 解析失败时抛 {@link UncheckedIOException}（unchecked），避免强制 try-catch。
	 *
	 * @param imgBytes 图片或 PDF 字节（PNG / JPG / BMP / PDF）
	 * @return 识别结果列表（按阅读顺序排列，PDF 多页平铺）
	 * @throws IllegalArgumentException 字节为空或解码失败
	 */
	public List<PPOcrV6Result> run(byte[] imgBytes) {
		return run(imgBytes, null);
	}

	/**
	 * 完整 OCR 流程：检测 → 排序 → 裁剪 → 识别。
	 *
	 * <p>典型场景：Spring Boot 上传 {@code MultipartFile.getBytes()}。
	 *
	 * <p>自动嗅探输入：字节流以 {@code %PDF-} 魔数开头时，自动按 PDF 双通道处理并
	 * 平铺所有页的文本框列表。其它格式按图片走。
	 *
	 * <p>PDF 解析失败时抛 {@link UncheckedIOException}（unchecked），避免强制 try-catch。
	 *
	 * @param imgBytes  图片或 PDF 字节（PNG / JPG / BMP / PDF）
	 * @param detParams DB 参数；{@code null} 表示使用引擎默认
	 * @return 识别结果列表（按阅读顺序排列，PDF 多页平铺）
	 * @throws IllegalArgumentException 字节为空或解码失败
	 */
	public List<PPOcrV6Result> run(byte[] imgBytes, DbDetParams detParams) {
		if (imgBytes == null || imgBytes.length == 0) {
			throw new IllegalArgumentException("imgBytes must not be empty");
		}
		lifecycleReadLock.lock();
		try {
			requireOpen();
			if (PdfMagicDetector.isPdf(imgBytes)) {
				try {
					return flattenPdfPages(runPdfBytes(imgBytes, pdfOcrConfig, detParams));
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			}
			Mat mat = decodeMat(imgBytes);
			try {
				return runMat(mat, detParams);
			} finally {
				mat.release();
			}
		} finally {
			lifecycleReadLock.unlock();
		}
	}

	/**
	 * 完整 OCR 流程：检测 → 排序 → 裁剪 → 识别。
	 *
	 * <p>内部读取全部流为 byte[] 后转发到 {@link #run(byte[])}。
	 * 流由调用方负责关闭（{@code CollUtil.readAllBytes(InputStream)} 会读到 EOF 但不 close）。
	 *
	 * <p>自动嗅探输入：若为 PDF，按 PDF 双通道处理。
	 *
	 * <p>流读取失败时包为 {@link UncheckedIOException} 抛出，调用方免 try-catch。
	 *
	 * @param in 图片或 PDF 输入流
	 * @return 识别结果列表（按阅读顺序排列，PDF 多页平铺）
	 * @throws IllegalArgumentException 输入流为 null
	 */
	public List<PPOcrV6Result> run(InputStream in) {
		return run(in, null);
	}

	/**
	 * 完整 OCR 流程：检测 → 排序 → 裁剪 → 识别。
	 *
	 * <p>内部读取全部流为 byte[] 后转发到 {@link #run(byte[], DbDetParams)}。
	 * 流由调用方负责关闭（{@code CollUtil.readAllBytes(InputStream)} 会读到 EOF 但不 close）。
	 *
	 * <p>自动嗅探输入：若为 PDF，按 PDF 双通道处理。
	 *
	 * <p>流读取失败时包为 {@link UncheckedIOException} 抛出，调用方免 try-catch。
	 *
	 * @param in        图片或 PDF 输入流
	 * @param detParams DB 参数；{@code null} 表示使用引擎默认
	 * @return 识别结果列表（按阅读顺序排列，PDF 多页平铺）
	 * @throws IllegalArgumentException 输入流为 null
	 */
	public List<PPOcrV6Result> run(InputStream in, DbDetParams detParams) {
		if (in == null) {
			throw new IllegalArgumentException("InputStream must not be null");
		}
		try {
			return run(CollUtil.readAllBytes(in), detParams);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * 从 {@link Path} 读取头部 N 个字节用于 PDF 魔数嗅探。
	 *
	 * @param path  文件路径
	 * @param limit 最大读取字节数
	 * @return 头部字节（可能短于 limit）
	 * @throws IOException 读取失败
	 */
	private static byte[] readHeadBytes(Path path, int limit) throws IOException {
		try (InputStream in = Files.newInputStream(path)) {
			byte[] buf = new byte[limit];
			int total = 0;
			while (total < limit) {
				int n = in.read(buf, total, limit - total);
				if (n < 0) {
					break;
				}
				total += n;
			}
			if (total == buf.length) {
				return buf;
			}
			byte[] out = new byte[total];
			System.arraycopy(buf, 0, out, 0, total);
			return out;
		}
	}

	/**
	 * 文本检测（仅检测，不识别）。
	 *
	 * <p>内部自动加载为 BGR Mat 并在方法返回时 release，调用方无需管理 native 内存。
	 *
	 * @param imagePath 图片路径
	 * @return boxes 形状 (N, 4, 2) int，scores 长度 N
	 * @throws IllegalArgumentException 路径为空、文件不存在或解码失败
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
	 * @throws IllegalArgumentException 文件不存在或解码失败
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
	 * @param imagePath 图片路径
	 * @return boxes 形状 (N, 4, 2) int，scores 长度 N
	 * @throws IllegalArgumentException 路径为 null、文件不存在或解码失败
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
	 * @throws IllegalArgumentException 字节为空或解码失败
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
	 * 文本检测（Mat 版，使用引擎默认 DB 参数）。
	 *
	 * <p>仅适用于「已持有 Mat 并需复用」的高级场景（如同一图跑多次推理）；
	 * Mat 的 release 由调用方负责。一般场景请使用 {@link #detect(String)} / {@link #detect(byte[])} 等重载。
	 *
	 * @param imgBgr BGR 格式图像 (H, W, 3) uint8
	 * @return boxes 形状 (N, 4, 2) int，scores 长度 N
	 */
	public DetectResult detectMat(Mat imgBgr) {
		return detectMat(imgBgr, null);
	}

	/**
	 * 文本检测（Mat 版，按调用覆盖 DB 参数）。
	 *
	 * <p>用于证件反光/弱对比等需要临时放宽 det 阈值的场景：传入 {@code null} 等价于
	 * {@link #detectMat(Mat)}（使用引擎构造期固定的默认 DB 参数）；传入非 null 时
	 * 本调用临时构造一个 {@link DbPostProcessor}，<strong>不修改引擎共享状态</strong>，
	 * 线程安全，可并发调用。
	 *
	 * @param imgBgr    BGR 格式图像 (H, W, 3) uint8
	 * @param detParams DB 参数；{@code null} 表示使用引擎默认
	 * @return boxes 形状 (N, 4, 2) int，scores 长度 N
	 */
	public DetectResult detectMat(Mat imgBgr, DbDetParams detParams) {
		lifecycleReadLock.lock();
		try {
			DbPostProcessor post = detParams == null ? detPost : new DbPostProcessor(detParams);
			return detectMatInternal(imgBgr, post);
		} finally {
			lifecycleReadLock.unlock();
		}
	}

	/**
	 * 实际执行 det 推理 + 后处理。{@code dbPost} 已由调用方选定（默认或临时构造），不可为 null。
	 *
	 * <p>本方法分配并自动释放以下资源：det 输入/输出 {@link OnnxTensor}、
	 * 由 {@link #readProbToMat} 转换得到的概率 Mat；{@code imgBgr} 的生命周期由调用方负责。
	 *
	 * @param imgBgr BGR 格式图像 (H, W, 3) uint8
	 * @param dbPost 已确定的后处理器（非 null）
	 * @return 检测结果
	 * @throws RuntimeException 推理失败（包装 {@link OrtException}）
	 */
	private DetectResult detectMatInternal(Mat imgBgr, DbPostProcessor dbPost) {
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
				DbPostProcessor.Result post = dbPost.call(probMat, prep.imgShape());
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
		lifecycleReadLock.lock();
		try {
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
		} finally {
			lifecycleReadLock.unlock();
		}
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
		return runMat(imgBgr, null);
	}

	/**
	 * 完整 OCR 流程（Mat 版，按调用覆盖 DB 参数）：检测 → 排序 → 裁剪 → 识别。
	 *
	 * <p>用于证件反光/弱对比等需要临时放宽 det 阈值的场景：传入 {@code null} 等价于
	 * {@link #runMat(Mat)}（使用引擎构造期固定的默认 DB 参数）；传入非 null 时
	 * 本调用临时构造一个 {@link DbPostProcessor}，<strong>不修改引擎共享状态</strong>，
	 * 线程安全，可并发调用。
	 *
	 * <p>仅适用于「已持有 Mat 并需复用」的高级场景（如同一图跑多次推理）；
	 * Mat 的 release 由调用方负责。一般场景请使用 {@link #run(String, DbDetParams)} /
	 * {@link #run(byte[], DbDetParams)} / {@link #run(Path, DbDetParams)} 等重载。
	 *
	 * @param imgBgr    BGR 格式图像 (H, W, 3) uint8
	 * @param detParams DB 参数；{@code null} 表示使用引擎默认
	 * @return 识别结果列表（按阅读顺序排列）；
	 * 启用 doc_ori 时每个 {@link PPOcrV6Result#rotatedDegrees()} 记录
	 * doc_ori 应用到原图的顺时针旋转角度（0/90/180/270）
	 */
	public List<PPOcrV6Result> runMat(Mat imgBgr, DbDetParams detParams) {
		lifecycleReadLock.lock();
		try {
			requireOpen();
			// 文档方向分类（可选）：根据整图方向把图片旋转到正向，再走检测
			DocOriRotated rotatedInfo = classifyAndRotateDocOrientation(imgBgr);
			Mat rotated = rotatedInfo.mat();
			try {
				List<PPOcrV6Result> results = runOnMat(rotated, detParams);
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
		} finally {
			lifecycleReadLock.unlock();
		}
	}

	/**
	 * 在已正向化的 Mat 上跑核心 OCR 流水线（检测 → 排序 → 裁剪 → 识别）。
	 *
	 * <p>本方法负责：
	 * <ul>
	 *     <li>调 det 推理并按 {@code detParams} 后处理；</li>
	 *     <li>把检测框排序成阅读顺序；</li>
	 *     <li>按四边形框裁剪出 crop Mat，<strong>内部负责释放</strong>；</li>
	 *     <li>对非空 crop 跑 rec 推理并组装 {@link PPOcrV6Result}。</li>
	 * </ul>
	 *
	 * <p>{@link CropUtil#cropByPolys} 对退化的多边形会返回 {@code null}，对应位置的 box 会被跳过；
	 * 本方法不抛异常，整体返回空列表表示无文本。
	 *
	 * @param imgBgr    正向化后的 BGR 图像
	 * @param detParams DB 参数；{@code null} 表示使用引擎默认
	 * @return 识别结果列表（已按阅读顺序排列）
	 */
	private List<PPOcrV6Result> runOnMat(Mat imgBgr, DbDetParams detParams) {
		DetectResult dr = detectMat(imgBgr, detParams);
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
			releaseCrops(crops);
		}
	}

	/**
	 * 释放裁剪 Mat 列表中所有非空元素。
	 *
	 * <p>允许列表中含 {@code null}（来自 {@link CropUtil#cropByPolys} 对退化多边形的无效裁剪），
	 * 调用 {@link Mat#release()} 前会先判空。
	 *
	 * @param crops 裁剪 Mat 列表（可含 null）
	 */
	private static void releaseCrops(List<Mat> crops) {
		for (Mat crop : crops) {
			if (crop != null) {
				crop.release();
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

	/**
	 * 把 {@code int[]} 形状转换为 ONNX Runtime 要求的 {@code long[]}。
	 *
	 * <p>ONNX Runtime Java API 要求 tensor shape 为 {@code long[]}；常见预处理输出是
	 * {@code int[]}，此处做一次零拷贝转换。
	 *
	 * @param arr 输入形状（不可为 null）
	 * @return 等价的 long 数组
	 */
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
	 *
	 * <p>调用方负责释放返回值；本方法内部仅在 {@link Mat#put} 抛出时回收尚未交付的 Mat。
	 *
	 * @param tensor det 输出张量 [1, 1, H, W]
	 * @return 概率图 Mat（H, W, CV_32F）
	 * @throws OrtException 读取张量数据失败
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
	 * 检测结果（仅 det 推理）。
	 */
	@Getter
	@ToString
	@EqualsAndHashCode
	@RequiredArgsConstructor
	@Accessors(fluent = true)
	public static class DetectResult {
		/**
		 * 文本框 (N, 4, 2) int，顶点顺序：左上、右上、右下、左下。
		 */
		private final int[][][] boxes;
		/**
		 * 每框分数，与 {@code boxes} 一一对应。
		 */
		private final float[] scores;
	}

	/**
	 * 识别结果（仅 rec 推理）。
	 */
	@Getter
	@ToString
	@EqualsAndHashCode
	@RequiredArgsConstructor
	@Accessors(fluent = true)
	public static class RecognizeResult {
		/**
		 * 识别文本数组，与输入图像列表一一对应。
		 */
		private final String[] texts;
		/**
		 * 每条文本的置信度，与 {@code texts} 一一对应。
		 */
		private final float[] scores;
	}

	// ==================================================================
	// PDF 双通道入口：每页文本层命中走坐标抽取；否则降级渲染 + OCR
	// 全部为 private —— 公开 API 只走 run(...) 系列，PDF 自动嗅探转发到此处
	// ==================================================================

	/**
	 * PDF 双通道解析（核心入口）。
	 *
	 * <p>按 {@link PdfOcrConfig} 配置：每页先尝试文本层坐标抽取，
	 * 文本质量不达标时降级到渲染 + OCR。
	 *
	 * @param pdfBytes PDF 字节
	 * @param config   PDF 配置（不可为 null）
	 * @param detParams DB 参数；{@code null} 表示使用引擎默认
	 * @return per-page 结果列表
	 * @throws IOException PDF 解析失败
	 */
	private List<PdfPageResult> runPdfBytes(byte[] pdfBytes, PdfOcrConfig config, DbDetParams detParams) throws IOException {
		if (config == null) {
			throw new IllegalArgumentException("config must not be null");
		}
		if (pdfBytes == null || pdfBytes.length == 0) {
			throw new IllegalArgumentException("pdfBytes must not be empty");
		}
		if (!PdfMagicDetector.isPdf(pdfBytes)) {
			throw new IllegalArgumentException(
				"input bytes are not a PDF (missing %PDF- magic); use run(byte[]) for images");
		}
		try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
			return runPdfPages(doc, config, detParams);
		}
	}

	/**
	 * 将 PDF 多页结果平铺为单层 {@link PPOcrV6Result} 列表（按页顺序拼接）。
	 *
	 * @param pages 按页结果列表（不可为 null）
	 * @return 平铺后的结果列表
	 */
	private static List<PPOcrV6Result> flattenPdfPages(List<PdfPageResult> pages) {
		return pages.stream()
			.flatMap(page -> page.results().stream())
			.collect(Collectors.toList());
	}

	/**
	 * 按 {@code config} 遍历所有页：文本层质量达标时直接走 {@link PdfTextExtractor}，
	 * 否则降级到渲染 + OCR。{@code forceOcr=true} 时跳过文本层抽取，全走 OCR。
	 *
	 * @param doc       已加载的 PDF 文档（不可为 null）
	 * @param config    PDF 配置（不可为 null）
	 * @param detParams DB 参数；{@code null} 表示使用引擎默认
	 * @return per-page 结果列表
	 * @throws IOException 渲染失败
	 */
	private List<PdfPageResult> runPdfPages(PDDocument doc, PdfOcrConfig config, DbDetParams detParams) throws IOException {
		requireOpen();
		int pageCount = doc.getNumberOfPages();
		List<PdfPageResult> pages = new ArrayList<>(pageCount);
		PDFRenderer renderer = null;
		for (int i = 0; i < pageCount; i++) {
			if (config.isForceOcr()) {
				renderer = ensureRenderer(doc, renderer);
				pages.add(ocrPdfPage(i, renderer, config, detParams));
				continue;
			}
			List<PPOcrV6Result> textResults = pdfExtractor.extract(doc, i);
			PdfTextQuality quality = pdfExtractor.quality(textResults);
			if (quality.usable(config.getMinTextChars(), config.getMinReadableRatio())) {
				pages.add(new PdfPageResult(i, false, textResults));
			} else {
				renderer = ensureRenderer(doc, renderer);
				pages.add(ocrPdfPage(i, renderer, config, detParams));
			}
		}
		return pages;
	}

	/**
	 * 惰性构造 {@link PDFRenderer}：同一文档只需创建一个 renderer 即可复用，避免重复绑定底层资源。
	 *
	 * @param doc      PDF 文档
	 * @param renderer 已有 renderer（可为空）
	 * @return 已存在或新创建的 renderer
	 */
	private static PDFRenderer ensureRenderer(PDDocument doc, PDFRenderer renderer) {
		return renderer != null ? renderer : new PDFRenderer(doc);
	}

	/**
	 * 渲染通道：按配置 DPI 渲染页面为 BGR Mat，走完整 OCR 链路。
	 *
	 * @param pageIndex 0-based 页索引
	 * @param renderer  PDF 渲染器（不可为 null）
	 * @param config    PDF 配置（不可为 null）
	 * @param detParams DB 参数；{@code null} 表示使用引擎默认
	 * @return 该页 OCR 结果，{@code viaOcr=true}
	 * @throws IOException 渲染失败
	 */
	private PdfPageResult ocrPdfPage(int pageIndex, PDFRenderer renderer, PdfOcrConfig config, DbDetParams detParams) throws IOException {
		// OpenCV 原生库已在构造期由 ensureOpenCvLoaded() 加载（幂等），此处无需再加载
		BufferedImage image = renderer.renderImageWithDPI(pageIndex, config.getRenderDpi(), ImageType.RGB);
		Mat mat = BufferedImageUtils.toBgrMat(image);
		List<PPOcrV6Result> results;
		try {
			results = runMat(mat, detParams);
		} finally {
			mat.release();
		}
		return new PdfPageResult(pageIndex, true, results);
	}
}
