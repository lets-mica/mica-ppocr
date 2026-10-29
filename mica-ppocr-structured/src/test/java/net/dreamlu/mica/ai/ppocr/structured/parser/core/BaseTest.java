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

package net.dreamlu.mica.ai.ppocr.structured.parser.core;

import lombok.experimental.Accessors;
import net.dreamlu.mica.ai.ppocr.config.PPOcrV6Config;
import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Engine;
import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Result;
import net.dreamlu.mica.ai.ppocr.postprocessor.DbDetParams;
import net.dreamlu.mica.ai.ppocr.utils.CollUtil;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

/**
 * 结构化解析调试 demo 泛型基类。
 *
 * <p>封装 OpenCV 加载、模型初始化、OCR 推理、可视化等通用流程，
 * 子类只需指定：
 * <ul>
 *   <li>{@link #newParser(PPOcrV6Engine)} —— 返回绑定好泛型的 {@link BaseStructuredParser} 实例；</li>
 *   <li>{@link #printResult(Object)} —— 按证件类型输出字段；</li>
 * </ul>
 *
 * <p>默认 {@link #demo(String, String)} 会依次跑 {@link #DEMO_TIERS} 中的
 * 所有档位（默认 {@code tiny} + {@code small}）做对比，多档时可
 * 视化会自动加档位后缀（{@code vis.tiny.png} / {@code vis.small.png}）。
 *
 * <p>典型子类（{@code VehicleLicenseMain} 仅 30 行）：
 * <pre>{@code
 * public class VehicleLicenseMain extends BaseTest<VehicleLicenseParser, VehicleLicenseResult> {
 *
 *     private static final String IMAGE_PATH = "test_images/vehicle/vehicle1.png";
 *     private static final String VIS_PATH   = "test_images/vehicle/vis.png";
 *
 *     public static void main(String[] args) {
 *         new VehicleLicenseMain().demo(IMAGE_PATH, VIS_PATH);
 *     }
 *
 *     @Override protected VehicleLicenseParser newParser(PPOcrV6Engine engine) {
 *         return new VehicleLicenseParser(engine);
 *     }
 *
 *     @Override protected void printResult(VehicleLicenseResult r) {
 *         System.out.println("plateNo:     " + r.getPlateNo());
 *         // ... 其他字段
 *     }
 * }
 * }</pre>
 *
 * @param <P> 解析器类型
 * @param <R> 解析结果类型
 */
public abstract class BaseTest<P extends BaseStructuredParser<R>, R> {

	/**
	 * demo 默认要跑的模型档位列表。
	 *
	 * <p>默认 {@code {"tiny", "small"}}——单次跑两档做对比，方便排查
	 * "tiny 检不到 / 检错，但 small 能兜底" 这类典型调优场景。
	 *
	 * <p>子类若想自定义，可在自己的 main 里直接调
	 * {@link #demo(String, String, String...)} 显式传档位，例如：
	 * <pre>{@code
	 * new VehicleLicenseMain().demo(IMAGE_PATH, VIS_PATH, "small", "medium");
	 * }</pre>
	 */
	protected static final String[] DEMO_TIERS = {"tiny", "small"};
	/**
	 * 是否启用文档方向分类（PP-OCRv6 use_doc_orientation_classify）
	 */
	protected static final boolean USE_DOC_ORIENTATION = true;

	/**
	 * 本 demo 使用的 DB 检测参数（per-call 覆盖）。
	 *
	 * <p>默认 {@code null} = 沿用引擎构造期的默认 DB 参数，
	 * 适用于证件 / 票据等常规文档版面。
	 *
	 * <p>海报 / 分享图版面（如拼多多福袋邀请码）存在「大字号稀疏数字 + 背景水印」，
	 * 需要放宽 {@code boxThresh}、收紧 {@code unclipRatio} 才能检出，
	 * 由子类（如 {@code PddLuckyBagMain}）覆盖此钩子指定。
	 */
	protected DbDetParams detParams() {
		return null;
	}

	/**
	 * 模型档位：tiny / small / medium。
	 *
	 * <p>保留为实例字段（默认 {@code "tiny"}）是为了：
	 * <ul>
	 *   <li>向后兼容：原有 demo 入口和 {@code *Main} 子类仍能直接引用 {@code TIER}；</li>
	 *   <li>参数化：集成测试可通过 {@code setTier(String)} 或直接调 {@link #runOcr(Mat, String)} 切换档位。</li>
	 * </ul>
	 */
	protected String TIER = "tiny";

	/**
	 * 给可视化路径插入档位后缀。
	 *
	 * <p>多档时把档位名插入到扩展名前：{@code vis.png → vis.tiny.png}；
	 * 单档时原样返回，保持向后兼容。
	 *
	 * @param visPath   原始可视化路径
	 * @param tier      当前档位
	 * @param tierCount 总档位数（用于判定是否需要加后缀）
	 * @return 调整后的可视化路径
	 */
	private static String visPathWithTier(String visPath, String tier, int tierCount) {
		if (tierCount <= 1) {
			return visPath;
		}
		int dot = visPath.lastIndexOf('.');
		if (dot < 0) {
			return visPath + "." + tier;
		}
		return visPath.substring(0, dot) + "." + tier + visPath.substring(dot);
	}

	/**
	 * 扫描 {@code models/ppocr-v6/} 下模型文件齐全的档位，供 JUnit 5 {@code @MethodSource} 使用。
	 *
	 * <p>用法示例（在继承本类的集成测试中）：
	 * <pre>{@code
	 * @ParameterizedTest
	 * @MethodSource("tiers") // 直接引用父类的 static 方法
	 * void parseAllTiers(String tier) throws Exception {
	 *     setTier(tier);
	 *     runOcr(loadImage());
	 *     // 断言 ...
	 * }
	 * }</pre>
	 *
	 * <p>找不到任何可用档位时返回空流，由调用方通过 {@code Assumptions.assumeTrue(...)} 跳过。
	 *
	 * @return 可用档位流，按字母序排序
	 */
	protected static Stream<String> tiers() {
		Path modelsRoot = CollUtil.pathOf("models/ppocr-v6");
		if (!Files.isDirectory(modelsRoot)) {
			return Stream.empty();
		}
		try (Stream<Path> children = Files.list(modelsRoot)) {
			return children
				.filter(Files::isDirectory)
				.map(p -> p.getFileName().toString())
				.filter(name -> Files.isRegularFile(modelsRoot.resolve(name).resolve("det.onnx"))
					&& Files.isRegularFile(modelsRoot.resolve(name).resolve("rec.onnx"))
					&& Files.isRegularFile(modelsRoot.resolve(name).resolve("dict.txt")))
				.sorted();
		} catch (Exception e) {
			System.err.println("扫描模型档位失败: " + e.getMessage());
			return Stream.empty();
		}
	}

	/**
	 * 新建一个解析器实例（{@code engine} 传 null 即可，本基类已自行管理 OCR）。
	 *
	 * @return 解析器实例
	 */
	protected abstract P newParser(PPOcrV6Engine engine);

	/**
	 * 打印单个结构化结果（按证件类型输出字段）。
	 *
	 * @param result 解析结果
	 */
	protected abstract void printResult(R result);

	/**
	 * demo 入口：依次跑 {@link #DEMO_TIERS} 中所有档位，每档输出 OCR 结果 +
	 * 结构化字段 + 可视化图片。
	 *
	 * <p>多档时，可视化文件会自动带档位后缀（{@code vis.tiny.png} /
	 * {@code vis.small.png}）以便对比查看；单档时仍写到 {@code visPath} 原路径，
	 * 保持向后兼容。
	 *
	 * @param imagePath 推理图片路径
	 * @param visPath   可视化输出路径（可为 null 表示不保存可视化）
	 */
	public final void demo(String imagePath, String visPath) {
		demo(imagePath, visPath, DEMO_TIERS);
	}

	/**
	 * demo 入口：依次跑指定档位，每档输出 OCR 结果 + 结构化字段 + 可视化图片。
	 *
	 * <p>适用场景：子类希望自定义 demo 跑的档位集合，例如只跑 small + medium
	 * 排除 tiny；或临时追加第三档。传空数组或 {@code null} 等价于
	 * {@link #DEMO_TIERS}。
	 *
	 * <p>多档时，可视化文件会自动带档位后缀（{@code vis.tiny.png} /
	 * {@code vis.small.png}）以便对比查看；单档时仍写到 {@code visPath} 原路径，
	 * 保持向后兼容。
	 *
	 * @param imagePath 推理图片路径
	 * @param visPath   可视化输出路径（可为 null 表示不保存可视化）
	 * @param tiers     要跑的档位列表；为空时使用 {@link #DEMO_TIERS}
	 */
	public final void demo(String imagePath, String visPath, String... tiers) {
		String[] actualTiers = (tiers == null || tiers.length == 0) ? DEMO_TIERS : tiers.clone();
		// 加载 OpenCV 原生库（本方法在构造引擎之前就直接用 Imgcodecs 读图 / 画框）
		nu.pattern.OpenCV.loadLocally();
		// 读取图片
		System.out.println("Image:  " + imagePath);
		System.out.println("Tiers:  " + Arrays.toString(actualTiers));
		Mat img = Imgcodecs.imread(imagePath);
		if (img == null || img.empty()) {
			System.err.println("Error: cannot read image: " + imagePath);
			System.exit(1);
		}
		try {
			for (String tier : actualTiers) {
				if (actualTiers.length > 1) {
					System.out.println("\n========== Tier: " + tier + " ==========");
				}
				List<PPOcrV6Result> results = runOcr(img, tier);
				if (visPath != null) {
					String outPath = visPathWithTier(visPath, tier, actualTiers.length);
					saveVis(img, results, outPath);
				}
			}
		} finally {
			img.release();
		}
	}

	/**
	 * 解析 OCR 结果 + 打印结构化字段。
	 *
	 * <p>子类无需重写；如需自定义输出格式可重写 {@link #printResult(Object)}。
	 *
	 * @param results OCR 结果列表
	 */
	protected void printResults(PPOcrV6Engine engine, List<PPOcrV6Result> results) {
		P parser = newParser(engine);
		R result = parser.parseResults(results);
		System.out.println();
		printResult(result);
	}

	/**
	 * 加载 OpenCV 原生库 + 跑 OCR 推理（使用 {@link #TIER} 默认档位）。
	 *
	 * @param image 已读取的图片
	 * @return OCR 结果列表
	 */
	protected List<PPOcrV6Result> runOcr(Mat image) {
		return runOcr(image, TIER);
	}

	/**
	 * 加载 OpenCV 原生库 + 跑 OCR 推理（指定模型档位）。
	 *
	 * <p>这是参数化运行的实际入口——子类的 test 方法可以：
	 * <ul>
	 *   <li>通过 {@link #setTier(String)} 切档后再调 {@link #runOcr(Mat)}；或</li>
	 *   <li>直接调本方法传 tier。</li>
	 * </ul>
	 *
	 * @param image 已读取的图片
	 * @param tier  模型档位（tiny / small / medium）
	 * @return OCR 结果列表
	 */
	protected List<PPOcrV6Result> runOcr(Mat image, String tier) {
		String detModel = "models/ppocr-v6/" + tier + "/det.onnx";
		String recModel = "models/ppocr-v6/" + tier + "/rec.onnx";
		String dict = "models/ppocr-v6/" + tier + "/dict.txt";
		String docOriModel = "models/ppocr-v6/doc_ori/doc_ori.onnx";

		System.out.println("模型档位:   " + tier);
		System.out.println("Det:    " + detModel); // 检测模型
		System.out.println("Rec:    " + recModel); // 识别模型
		System.out.println("Dict:   " + dict);   // 字典文件
		System.out.println("DocOri: " + docOriModel + " (enabled=" + USE_DOC_ORIENTATION + ")");
		System.out.println("Size:   " + image.cols() + "x" + image.rows());

		PPOcrV6Config config = PPOcrV6Config.builder()
			.detModelPath(detModel)
			.recModelPath(recModel)
			.recCharDictPath(dict)
			.useDocOrientationClassify(USE_DOC_ORIENTATION)
			.docOrientationModelPath(docOriModel)
			.build();

		long t0 = System.currentTimeMillis();
		List<PPOcrV6Result> results;
		try (PPOcrV6Engine engine = new PPOcrV6Engine(config)) {
			System.out.println("Running OCR...");
			results = engine.runMat(image, detParams());

			// 打印 OCR 结果
			long elapsed = System.currentTimeMillis() - t0;
			System.out.println("\nDetected " + results.size() + " text regions (elapsed " + elapsed + " ms):\n");
			for (int i = 0; i < results.size(); i++) {
				PPOcrV6Result r = results.get(i);
				int[][] b = r.box();
				System.out.printf("  [%2d] text=\"%s\"  score=%.6f  box=[(%d,%d),(%d,%d),(%d,%d),(%d,%d)]%n",
					i + 1, r.text(), r.score(),
					b[0][0], b[0][1], b[1][0], b[1][1], b[2][0], b[2][1], b[3][0], b[3][1]);
			}

			// 打印结构化结果
			printResults(engine, results);
		}

		return results;
	}

	/**
	 * 设置默认模型档位（影响 {@link #runOcr(Mat)}，不影响 {@link #runOcr(Mat, String)}）。
	 *
	 * @param tier tiny / small / medium
	 */
	protected void setTier(String tier) {
		this.TIER = tier;
	}

	/**
	 * 一次性跑多档模型并对每档结果回调。
	 *
	 * <p>适用场景：单测里希望同一张图在 tiny 和 small 下都跑一遍做对比，但不想用 JUnit 参数化。
	 *
	 * @param image    已读取的图片
	 * @param onResult (tier, results) 每档跑完回调一次
	 * @param tiers    要跑的档位列表；为空时跑 {@link #tiers()} 扫描出的全部可用档位
	 * @return 各档 OCR 结果，按传入顺序
	 */
	protected List<OcrTierResult> runOcrForTiers(Mat image, BiConsumer<String, List<PPOcrV6Result>> onResult, String... tiers) {
		String[] tierArr = (tiers == null || tiers.length == 0)
			? tiers().toArray(String[]::new)
			: tiers;
		List<OcrTierResult> all = new ArrayList<>();
		for (String tier : tierArr) {
			List<PPOcrV6Result> results = runOcr(image, tier);
			all.add(new OcrTierResult(tier, results));
			if (onResult != null) {
				onResult.accept(tier, results);
			}
		}
		return all;
	}

	/**
	 * 在原图上绘制检测框并保存为 PNG。
	 *
	 * <p>如果 OCR 启用了 doc_ori（{@link PPOcrV6Result#rotatedDegrees()} 非 0），
	 * 会先通过 {@link PPOcrV6Result#boxInOriginalImg(int, int)} 把文本框投影回
	 * 原图坐标系再绘制，避免「原图 vs 旋转后 box」的错位。
	 *
	 * @param img     原图
	 * @param results OCR 结果列表
	 * @param out     输出 PNG 路径
	 */
	protected void saveVis(Mat img, List<PPOcrV6Result> results, String out) {
		Mat canvas = img.clone();
		int imgW = img.cols();
		int imgH = img.rows();
		for (PPOcrV6Result r : results) {
			int[][] box = r.boxInOriginalImg(imgW, imgH);
			Point[] pts = new Point[4];
			for (int i = 0; i < 4; i++) {
				pts[i] = new Point(box[i][0], box[i][1]);
			}
			MatOfPoint mop = new MatOfPoint(pts);
			List<MatOfPoint> list = new ArrayList<>();
			list.add(mop);
			Imgproc.polylines(canvas, list, true, new Scalar(0, 255, 0), 2);
		}
		boolean ok = Imgcodecs.imwrite(out, canvas);
		if (ok) {
			System.out.println("\nVisualization saved: " + out);
		} else {
			System.err.println("Warning: failed to save visualization: " + out);
		}
		canvas.release();
	}

	/**
	 * 一次跑完多档模型的结果包装（{@link #runOcrForTiers} 返回值）。
	 *
	 * @param tier    模型档位
	 * @param results 该档下的 OCR 结果
	 */
	@lombok.Value
	@Accessors(fluent = true)
	public static class OcrTierResult {
		private final String tier;
		private final List<PPOcrV6Result> results;
	}
}
