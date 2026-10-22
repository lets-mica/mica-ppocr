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

package net.dreamlu.mica.ai.ppocr.structured.parser.batch;

import net.dreamlu.mica.ai.ppocr.config.PPOcrV6Config;
import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Engine;
import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Result;
import net.dreamlu.mica.ai.ppocr.structured.parser.bankcard.BankCardResult;
import net.dreamlu.mica.ai.ppocr.structured.parser.business.BusinessLicenseResult;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.BaseStructuredParser;
import net.dreamlu.mica.ai.ppocr.structured.parser.driver.DriverLicenseResult;
import net.dreamlu.mica.ai.ppocr.structured.parser.household.HouseholdRegisterResult;
import net.dreamlu.mica.ai.ppocr.structured.parser.idcard.IdCardResult;
import net.dreamlu.mica.ai.ppocr.structured.parser.idcard.IdCardSide;
import net.dreamlu.mica.ai.ppocr.structured.parser.invoice.InvoiceItem;
import net.dreamlu.mica.ai.ppocr.structured.parser.invoice.InvoiceResult;
import net.dreamlu.mica.ai.ppocr.structured.parser.pdd.PddLuckyBagResult;
import net.dreamlu.mica.ai.ppocr.structured.parser.pdd.PddLuckyBagType;
import net.dreamlu.mica.ai.ppocr.structured.parser.taxi.TaxiReceiptResult;
import net.dreamlu.mica.ai.ppocr.structured.parser.train.TrainTicketResult;
import net.dreamlu.mica.ai.ppocr.structured.parser.vehicle.VehicleLicenseResult;
import net.dreamlu.mica.ai.ppocr.utils.CollUtil;
import org.opencv.core.Mat;
import org.opencv.imgcodecs.Imgcodecs;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 图片批量结构化识别入口。
 *
 * <p>扫描一个目录下的所有图片（默认 png/jpg/jpeg/bmp/webp），逐张调用
 * OCR 推理 + 结构化解析，把每张图的 OCR 文字框列表和结构化字段写入
 * 一个 txt 文本文件，方便一次性核对大量样本的识别效果。
 *
 * <p>运行方式：直接执行 {@link #main(String[])}，所有可调项都在类顶部的
 * <b>静态配置区</b>，改完重新运行即可。
 *
 * <p>输出文件结构（多张图片用空行 + 标题分隔）：
 * <pre>
 * ================================================================================
 * File: idcard1.jpg
 * Path: test_images/idcard/idcard1.jpg
 * Size: 2700x1800
 * Parser: 身份证
 * ================================================================================
 *
 * Detected 10 text regions (elapsed 2377 ms):
 *
 *   [ 1] text="徐乐"  score=0.999967  box=[(604,431),(964,432),(964,575),(604,573)]
 *   [ 2] text="姓名"  score=0.999983  box=[(350,465),(632,465),(632,564),(350,564)]
 *   ...
 *
 * side:         FRONT
 * name:         徐乐
 * ...
 * </pre>
 */
public final class BatchOcrMain {

	// ========================================================================
	// 静态配置区（按需修改后直接运行 main）
	// ========================================================================

	/**
	 * 解析器 key。可选值见 {@link ParserSpec#registry()}：
	 * <ul>
	 *   <li>idcard —— 身份证（正反面合一）</li>
	 *   <li>vehicle —— 行驶证</li>
	 *   <li>driver —— 驾驶证</li>
	 *   <li>bankcard —— 银行卡</li>
	 *   <li>business —— 营业执照</li>
	 *   <li>invoice —— 增值税发票</li>
	 *   <li>train —— 火车票</li>
	 *   <li>taxi —— 出租车票</li>
	 *   <li>household —— 户口本</li>
	 *   <li>pdd —— 拼多多福袋</li>
	 * </ul>
	 */
	private static final String PARSER_KEY = "idcard";

	/**
	 * 待批量识别的图片目录（相对路径相对 user.dir，或绝对路径）。
	 * 留空时使用 {@link ParserSpec#defaultDir()} 提供的默认目录
	 * （例如 {@code idcard} 对应 {@code test_images/idcard/}）。
	 */
	private static final String IMAGE_DIR = "test_images/idcard";

	/**
	 * 模型档位：tiny / small / medium。
	 *
	 * <p>tiny 速度快、精度一般；small 平衡档；medium 精度最高、模型大。
	 * 对应模型文件位于 {@code models/ppocr-v6/<tier>/}。
	 */
	private static final String TIER = "tiny";

	/**
	 * 要识别的图片扩展名（小写、含点）。会按文件后缀过滤目录中的文件。
	 */
	private static final Set<String> IMAGE_EXTS = parseExts("png,jpg,jpeg,bmp,webp");

	private BatchOcrMain() {
	}

	// ========================================================================
	// 入口
	// ========================================================================

	public static void main(String[] args) {
		ParserSpec spec = ParserSpec.of(PARSER_KEY);

		// 解析目录 & 输出路径
		Path dirPath = CollUtil.pathOf(emptyToDefault(IMAGE_DIR, spec.defaultDir()));
		// 输出到 项目下的 target 目录
		Path outputPath = CollUtil.pathOf( "target/batch-ocr-" + spec.key() + "-" + TIER + ".txt");

		// 列出图片
		List<Path> images;
		try {
			images = listImages(dirPath, IMAGE_EXTS);
		} catch (IOException e) {
			System.err.println("Error: cannot list images in '" + dirPath + "': " + e.getMessage());
			System.exit(1);
			return;
		}
		if (images.isEmpty()) {
			System.err.println("Error: no images found in '" + dirPath
				+ "' (extensions: " + IMAGE_EXTS + ")");
			System.exit(1);
			return;
		}

		// 确保输出目录存在
		try {
			Path parent = outputPath.getParent();
			if (parent != null && !Files.exists(parent)) {
				Files.createDirectories(parent);
			}
		} catch (IOException e) {
			System.err.println("Error: cannot create output directory: " + e.getMessage());
			System.exit(1);
			return;
		}

		// 加载 OpenCV 原生库
		nu.pattern.OpenCV.loadLocally();

		System.out.println("Batch OCR start");
		System.out.println("  Parser:    " + spec.displayName() + " (key=" + spec.key() + ")");
		System.out.println("  Tier:      " + TIER);
		System.out.println("  Dir:       " + dirPath.toAbsolutePath());
		System.out.println("  Images:    " + images.size());
		System.out.println("  Output:    " + outputPath.toAbsolutePath());
		System.out.println();

		// 构建引擎（一次性，所有图片共享）
		PPOcrV6Config config = PPOcrV6Config.builder()
			.detModelPath("models/ppocr-v6/" + TIER + "/det.onnx")
			.recModelPath("models/ppocr-v6/" + TIER + "/rec.onnx")
			.recCharDictPath("models/ppocr-v6/" + TIER + "/dict.txt")
			.useDocOrientationClassify(true)
			.docOrientationModelPath("models/ppocr-v6/doc_ori/doc_ori.onnx")
			.intraOpNumThreads(Runtime.getRuntime().availableProcessors())
			.build();

		long totalStart = System.currentTimeMillis();
		int success = 0;
		int failed = 0;
		List<Long> perImageElapsed = new ArrayList<>();

		try (PPOcrV6Engine engine = new PPOcrV6Engine(config);
			 PrintWriter pw = new PrintWriter(Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8))) {

			// 解析器实例只创建一次（绑定 engine）
			BaseStructuredParser<?> parser = spec.factory().apply(engine);

			// 写文件抬头（含解析器类信息 + AI 提示词，方便后续把本文件直接给大模型分析）
			writeHeader(pw, spec, dirPath, images.size());
			pw.println();

			for (int i = 0; i < images.size(); i++) {
				Path imagePath = images.get(i);
				System.out.println("[" + (i + 1) + "/" + images.size() + "] " + imagePath.getFileName());
				if (i > 0) {
					pw.println();
				}
				try {
					long elapsed = processOne(imagePath, engine, parser, spec, pw);
					perImageElapsed.add(elapsed);
					success++;
				} catch (Exception e) {
					failed++;
					pw.println();
					pw.println("====== File: " + imagePath.getFileName() + " ======");
					pw.println("Path:        " + imagePath);
					pw.println();
					pw.println("[ERROR] " + e.getClass().getSimpleName() + ": " + e.getMessage());
					System.err.println("    FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage());
				}
			}

			long totalElapsed = System.currentTimeMillis() - totalStart;
			pw.println();
			writeBanner(pw, null, 80);
			pw.println("Summary");
			pw.println("  Total:    " + (success + failed) + " images, " + totalElapsed + " ms");
			pw.println("  Success:  " + success);
			pw.println("  Failed:   " + failed);
			if (!perImageElapsed.isEmpty()) {
				long avg = avg(perImageElapsed);
				long min = Collections.min(perImageElapsed);
				long max = Collections.max(perImageElapsed);
				pw.println("  Avg:      " + avg + " ms/image");
				pw.println("  Min:      " + min + " ms");
				pw.println("  Max:      " + max + " ms");
			}
			writeBanner(pw, null, 80);
		} catch (IOException e) {
			System.err.println("Error: cannot open output file '" + outputPath + "': " + e.getMessage());
			System.exit(1);
			return;
		} catch (Exception e) {
			System.err.println("Error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
			System.exit(1);
			return;
		}

		System.out.println();
		System.out.println("Done. " + success + " succeeded, " + failed + " failed.");
		System.out.println("Output: " + outputPath.toAbsolutePath());
	}

	// ========================================================================
	// 单图处理
	// ========================================================================

	/**
	 * 处理单张图片：读取 → OCR 推理 → 结构化解析 → 写出。
	 *
	 * @param imagePath 图片路径
	 * @param engine    推理引擎（与 {@code parser} 共用同一实例）
	 * @param parser    已绑定 engine 的解析器
	 * @param spec      解析器规格
	 * @param pw        输出 writer
	 * @return OCR 耗时（毫秒）
	 */
	private static long processOne(Path imagePath,
								  PPOcrV6Engine engine,
								  BaseStructuredParser<?> parser,
								  ParserSpec spec,
								  PrintWriter pw) {
		Mat img = Imgcodecs.imread(imagePath.toString());
		if (img == null || img.empty()) {
			throw new IllegalStateException("cannot read image: " + imagePath);
		}
		try {
			int imgW = img.cols();
			int imgH = img.rows();

			// OCR 推理
			long t0 = System.currentTimeMillis();
			List<PPOcrV6Result> results = engine.runMat(img);
			long elapsed = System.currentTimeMillis() - t0;

			// 写每张图的小节头
			writeBanner(pw, null, 80);
			pw.println("File:        " + imagePath.getFileName());
			pw.println("Path:        " + imagePath.toAbsolutePath());
			pw.println("Size:        " + imgW + "x" + imgH);
			pw.println("Parser:      " + spec.displayName());
			writeBanner(pw, null, 80);
			pw.println();
			pw.println("Detected " + results.size() + " text regions (elapsed " + elapsed + " ms):");
			pw.println();

			// 写 OCR 文字框
			for (int j = 0; j < results.size(); j++) {
				PPOcrV6Result r = results.get(j);
				// 投影回原始图坐标系（与 BaseTest.saveVis 一致；doc_ori=0 时退化为原 box）
				int[][] b = r.boxInOriginalImg(imgW, imgH);
				pw.printf("  [%2d] text=\"%s\"  score=%.6f  box=[(%d,%d),(%d,%d),(%d,%d),(%d,%d)]%n",
					j + 1, r.text(), r.score(),
					b[0][0], b[0][1], b[1][0], b[1][1], b[2][0], b[2][1], b[3][0], b[3][1]);
			}

			// 结构化解析
			Object structured = parser.parseResults(results);
			pw.println();
			spec.writer().accept(structured, pw);

			return elapsed;
		} finally {
			img.release();
		}
	}

	// ========================================================================
	// 工具方法
	// ========================================================================

	/**
	 * {@code null} 或空字符串时回退到默认值。
	 */
	private static String emptyToDefault(String value, String defaultValue) {
		return (value == null || value.isEmpty()) ? defaultValue : value;
	}

	/**
	 * 扫描目录下指定扩展名的图片，按文件名排序。
	 *
	 * @param dir  目录
	 * @param exts 扩展名集合（小写、含点，如 {@code .png}）
	 * @return 图片路径列表
	 * @throws IOException 目录读取失败
	 */
	private static List<Path> listImages(Path dir, Set<String> exts) throws IOException {
		if (!Files.isDirectory(dir)) {
			return Collections.emptyList();
		}
		List<Path> result = new ArrayList<>();
		try (java.util.stream.Stream<Path> stream = Files.list(dir)) {
			stream
				.filter(Files::isRegularFile)
				.forEach(p -> {
					String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
					int dot = name.lastIndexOf('.');
					if (dot < 0) {
						return;
					}
					String ext = name.substring(dot);
					if (exts.contains(ext)) {
						result.add(p);
					}
				});
		}
		// 文件名升序，保证多次运行结果稳定
		result.sort((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()));
		return result;
	}

	/**
	 * 把逗号分隔的扩展名列表解析为 {@code Set}。元素统一为小写、含点形式（如 {@code .png}）。
	 */
	private static Set<String> parseExts(String csv) {
		Set<String> set = new LinkedHashSet<>();
		if (csv == null) {
			return set;
		}
		for (String s : csv.split(",")) {
			String t = s.trim().toLowerCase(Locale.ROOT);
			if (t.isEmpty()) {
				continue;
			}
			if (!t.startsWith(".")) {
				t = "." + t;
			}
			set.add(t);
		}
		return set;
	}

	/**
	 * 解析器类所在的 Maven 模块源码根（相对仓库根）。
	 *
	 * <p>本批处理只覆盖 {@code mica-ppocr-structured} 模块下的 10 个内置解析器，
	 * 故这里硬编码；新增解析器若跨模块，需扩展为按包名推导。
	 */
	private static final String MODULE_SOURCE_ROOT = "mica-ppocr-structured/src/main/java";

	/**
	 * 把 Java 类 FQN 转成项目内源文件相对路径（相对仓库根）。
	 *
	 * <p>例：{@code net.dreamlu.mica.ai.ppocr.structured.parser.idcard.IdCardParser}
	 * → {@code mica-ppocr-structured/src/main/java/net/dreamlu/mica/ai/ppocr/structured/parser/idcard/IdCardParser.java}
	 *
	 * @param fqn 类全限定名
	 * @return 源文件相对路径
	 */
	private static String classNameToSourcePath(String fqn) {
		return MODULE_SOURCE_ROOT + "/" + fqn.replace('.', '/') + ".java";
	}

	/**
	 * 按命名约定从解析器类 FQN 推导 Result 类 FQN。
	 *
	 * <p>约定：与解析器同包、类名 {@code XxxParser → XxxResult}。本项目 10 个
	 * 内置解析器全部遵循此约定。
	 *
	 * @param parserClassName 解析器类 FQN
	 * @return 推导出的 Result 类 FQN；解析器类名不以 {@code Parser} 结尾时返回原 FQN
	 */
	private static String deriveResultClassName(String parserClassName) {
		int dot = parserClassName.lastIndexOf('.');
		String pkg = dot < 0 ? "" : parserClassName.substring(0, dot);
		String simple = dot < 0 ? parserClassName : parserClassName.substring(dot + 1);
		if (simple.endsWith("Parser")) {
			simple = simple.substring(0, simple.length() - "Parser".length()) + "Result";
		}
		return pkg.isEmpty() ? simple : pkg + "." + simple;
	}

	/**
	 * 写输出文件的抬头：解析器元信息 + AI 诊断提示词。
	 *
	 * <p>抬头分两段：
	 * <ol>
	 *   <li><b>元信息段</b> —— 解析器类 FQN / 源文件路径 / Result 类 FQN / 源文件路径
	 *       / 档位 / 文档方向 / 目录 / 图片数 / 生成时间，方便大模型快速定位源码</li>
	 *   <li><b>AI 提示词段</b> —— 一段可直接复制粘贴给大模型的中文 prompt，
	 *       明确"对比 OCR vs 结构化字段"和"不要改 core LabelMatcher"两条约束</li>
	 * </ol>
	 *
	 * @param pw        writer
	 * @param spec      解析器规格
	 * @param dirPath   图片目录
	 * @param imageNum  图片数量
	 */
	private static void writeHeader(PrintWriter pw, ParserSpec spec, Path dirPath, int imageNum) {
		String parserName = spec.parserClassName();
		// 取简单类名作为提示词中的代称（如 "IdCardParser"）
		String parserSimpleName = parserName.substring(parserName.lastIndexOf('.') + 1);
		String resultName = deriveResultClassName(parserName);
		String resultSimpleName = resultName.substring(resultName.lastIndexOf('.') + 1);

		// 1) 元信息段
		writeBanner(pw, "PP-OCRv6 Batch Recognition Result", 80);
		pw.printf("Parser:          %s (key=%s)%n", spec.displayName(), spec.key());
		pw.printf("ParserClass:     %s%n", parserName);
		pw.printf("ParserFile:      %s%n", classNameToSourcePath(parserName));
		pw.printf("ResultClass:     %s%n", resultName);
		pw.printf("ResultFile:      %s%n", classNameToSourcePath(resultName));
		pw.printf("Tier:            %s%n", TIER);
		pw.printf("DocOri:          %s%n", true);
		pw.printf("Dir:             %s%n", dirPath.toAbsolutePath());
		pw.printf("Images:          %d%n", imageNum);
		pw.printf("Generated:       %s%n", new java.util.Date());
		writeBanner(pw, null, 80);
		pw.println();

		// 2) AI 提示词段
		writeBanner(pw, "AI 提示词（复制下方整段，连同本文件交给大模型分析）", 80);
		pw.println("你是 PP-OCRv6 + Java 结构化解析调优专家。");
		pw.println("本文件是 " + parserSimpleName + " 的批量识别结果，");
		pw.println("每张图片包含 [OCR 原始文字框]（含坐标/置信度）");
		pw.println("和 [结构化字段]（key: value）两段。");
		pw.println();
		pw.println("## 任务");
		pw.println();
		pw.println("逐张图片核对：");
		pw.println("1. 对比每张图的 [OCR 文字框] 段（第一段）和 [结构化字段] 段（末尾）：");
		pw.println("   - 字段值是否能在 OCR 框中找到对应文字");
		pw.println("   - 是否漏检（OCR 有标签如 \"姓名\" 但对应字段为 null）");
		pw.println("   - 是否错检（值匹配到错误位置/错误文字）");
		pw.println();
		pw.println("2. 找出 bug 后修复 " + parserSimpleName + "：");
		pw.println("   - 优先复用 LabelMatcher 的现有方法（matchValue / matchPattern /");
		pw.println("     findLabelBox / matchSubstring / matchValueFromPrefix 等），");
		pw.println("     不要重复造轮子");
		pw.println("   - 不要修改 mica-ppocr-core 模块的 LabelMatcher");
		pw.println("     （核心公共匹配器，跨多个解析器共享，修改会影响其它场景）");
		pw.println("   - 可在 " + parserSimpleName + " 内部新增私有方法解决具体场景");
		pw.println("   - 保持与现有 " + resultSimpleName + " 字段一致，不新增 result 字段");
		pw.println();
		pw.println("3. 输出格式：");
		pw.println("   a. Bug 清单：");
		pw.println("      | 图片 | 字段 | 期望值 | 实际值 | OCR 中能匹配到的文字 |");
		pw.println("      |------|------|--------|--------|----------------------|");
		pw.println("   b. 修改后的 " + parserSimpleName + " 代码片段");
		pw.println("      （含 import / 新增方法 / 修改的方法）");
		pw.println("   c. 每处修改的理由（为什么这样改能解决 bug）");
		writeBanner(pw, null, 80);
		pw.println();
	}

	/**
	 * 写一行 80 字符的等号横幅；中间可夹一段标题文字。
	 *
	 * @param pw    writer
	 * @param title 标题文字；{@code null} 表示纯横线
	 * @param width 总宽度
	 */
	private static void writeBanner(PrintWriter pw, String title, int width) {
		if (title == null || title.isEmpty()) {
			pw.println(CollUtil.repeat("=", width));
			return;
		}
		String sep = "  ";
		String full = sep + title + sep;
		int remaining = width - full.length();
		int left = remaining / 2;
		int right = remaining - left;
		StringBuilder sb = new StringBuilder(width);
		for (int i = 0; i < left; i++) {
			sb.append('=');
		}
		sb.append(full);
		for (int i = 0; i < right; i++) {
			sb.append('=');
		}
		pw.println(sb);
	}

	/**
	 * 计算平均值。
	 */
	private static long avg(List<Long> values) {
		long sum = 0L;
		for (long v : values) {
			sum += v;
		}
		return sum / values.size();
	}

	// ========================================================================
	// 各解析器的结构化结果写出器
	// ========================================================================

	/**
	 * 各解析器结构化结果写出器集中地。
	 *
	 * <p>每个方法签名统一为 {@code (Object result, PrintWriter pw)}，内部
	 * 做强制类型转换后按字段顺序输出。字段对齐参考各自已有的 {@code *Main} 类。
	 */
	static final class Writers {

		private Writers() {
		}

		/** 身份证 */
		static void writeIdCard(Object o, PrintWriter pw) {
			IdCardResult r = (IdCardResult) o;
			pw.println("side:         " + r.getSide());
			pw.println("name:         " + r.getName());
			pw.println("gender:       " + r.getGender());
			pw.println("nation:       " + r.getNation());
			pw.println("birthDate:    " + r.getBirthDate());
			pw.println("address:      " + r.getAddress());
			pw.println("idNumber:     " + r.getIdNumber());
			pw.println("issuingAuthority: " + r.getIssuingAuthority());
			pw.println("validFrom:    " + r.getValidFrom());
			pw.println("validTo:      " + r.getValidTo());
			if (r.getSide() == IdCardSide.UNKNOWN) {
				pw.println("(warning: 未识别到正反面特征标签)");
			}
		}

		/** 行驶证 */
		static void writeVehicle(Object o, PrintWriter pw) {
			VehicleLicenseResult r = (VehicleLicenseResult) o;
			pw.println("plateNo:      " + r.getPlateNo());
			pw.println("owner:        " + r.getOwner());
			pw.println("vehicleType:  " + r.getVehicleType());
			pw.println("vin:          " + r.getVin());
			pw.println("issueDate:    " + r.getIssueDate());
		}

		/** 驾驶证 */
		static void writeDriver(Object o, PrintWriter pw) {
			DriverLicenseResult r = (DriverLicenseResult) o;
			pw.println("licenseNumber:  " + r.getLicenseNumber());
			pw.println("name:           " + r.getName());
			pw.println("gender:         " + r.getGender());
			pw.println("nationality:    " + r.getNationality());
			pw.println("address:        " + r.getAddress());
			pw.println("birthDate:      " + r.getBirthDate());
			pw.println("issueDate:      " + r.getIssueDate());
			pw.println("vehicleClass:   " + r.getVehicleClass());
			pw.println("issuingAuthority: " + r.getIssuingAuthority());
			pw.println("validFrom:      " + r.getValidFrom());
			pw.println("validTo:        " + r.getValidTo());
		}

		/** 银行卡 */
		static void writeBankCard(Object o, PrintWriter pw) {
			BankCardResult r = (BankCardResult) o;
			pw.println("cardNumber:   " + r.getCardNumber());
			pw.println("validDate:    " + r.getValidDate());
			pw.println("holderName:   " + r.getHolderName());
			pw.println("bankName:     " + r.getBankName());
			pw.println("cardType:     " + r.getCardType());
		}

		/** 营业执照 */
		static void writeBusiness(Object o, PrintWriter pw) {
			BusinessLicenseResult r = (BusinessLicenseResult) o;
			pw.println("creditCode:         " + r.getCreditCode());
			pw.println("name:               " + r.getName());
			pw.println("type:               " + r.getType());
			pw.println("legalPerson:        " + r.getLegalPerson());
			pw.println("registeredCapital:  " + r.getRegisteredCapital());
			pw.println("establishDate:      " + r.getEstablishDate());
			pw.println("operatingPeriod:    " + r.getOperatingPeriod());
			pw.println("address:            " + r.getAddress());
			pw.println("businessScope:      " + r.getBusinessScope());
		}

		/** 增值税发票 */
		static void writeInvoice(Object o, PrintWriter pw) {
			InvoiceResult r = (InvoiceResult) o;
			pw.println("invoiceCode:        " + r.getInvoiceCode());
			pw.println("invoiceNo:          " + r.getInvoiceNo());
			pw.println("invoiceDate:        " + r.getInvoiceDate());
			pw.println();
			pw.println("buyerName:          " + r.getBuyerName());
			pw.println("buyerTaxNo:         " + r.getBuyerTaxNo());
			pw.println("buyerAddressPhone:  " + r.getBuyerAddressPhone());
			pw.println("buyerBankAccount:   " + r.getBuyerBankAccount());
			pw.println();
			pw.println("sellerName:         " + r.getSellerName());
			pw.println("sellerTaxNo:        " + r.getSellerTaxNo());
			pw.println("sellerAddressPhone: " + r.getSellerAddressPhone());
			pw.println("sellerBankAccount:  " + r.getSellerBankAccount());
			pw.println();
			for (InvoiceItem item : r.getItems()) {
				pw.println("goodsName:          " + item.getGoodsName());
				pw.println("amount:             " + item.getAmount());
				pw.println("taxRate:            " + item.getTaxRate());
				pw.println("taxAmount:          " + item.getTaxAmount());
			}
			pw.println();
			pw.println("totalAmountUpper:   " + r.getTotalAmountUpper());
			pw.println("totalAmountLower:   " + r.getTotalAmountLower());
			pw.println();
			pw.println("payee:              " + r.getPayee());
			pw.println("reviewer:           " + r.getReviewer());
			pw.println("issuer:             " + r.getIssuer());
		}

		/** 火车票 */
		static void writeTrain(Object o, PrintWriter pw) {
			TrainTicketResult r = (TrainTicketResult) o;
			pw.println("departure:        " + r.getDeparture());
			pw.println("arrival:          " + r.getArrival());
			pw.println("trainNumber:      " + r.getTrainNumber());
			pw.println("departureDate:    " + r.getDepartureDate());
			pw.println("departureTime:    " + r.getDepartureTime());
			pw.println("seatNumber:       " + r.getSeatNumber());
			pw.println("seatClass:        " + r.getSeatClass());
			pw.println();
			pw.println("passengerName:    " + r.getPassengerName());
			pw.println("idNumber:         " + r.getIdNumber());
			pw.println();
			pw.println("amount:           " + r.getAmount());
			pw.println("amountExcludingTax: " + r.getAmountExcludingTax());
			pw.println();
			pw.println("ticketNo:         " + r.getTicketNo());
			pw.println("invoiceNo:        " + r.getInvoiceNo());
			pw.println("eTicketNo:        " + r.getETicketNo());
			pw.println();
			pw.println("invoiceDate:      " + r.getInvoiceDate());
			pw.println("sellStation:      " + r.getSellStation());
			pw.println("serialNumber:     " + r.getSerialNumber());
			pw.println("changedFlag:      " + r.getChangedFlag());
		}

		/** 出租车票 */
		static void writeTaxi(Object o, PrintWriter pw) {
			TaxiReceiptResult r = (TaxiReceiptResult) o;
			pw.println("invoiceCode:    " + r.getInvoiceCode());
			pw.println("invoiceNo:      " + r.getInvoiceNo());
			pw.println();
			pw.println("plateNumber:    " + r.getPlateNumber());
			pw.println("date:           " + r.getDate());
			pw.println("boardingTime:   " + r.getBoardingTime());
			pw.println("alightingTime:  " + r.getAlightingTime());
			pw.println("mileage:        " + r.getMileage());
			pw.println();
			pw.println("amount:         " + r.getAmount());
			pw.println("fuelSurcharge:  " + r.getFuelSurcharge());
			pw.println("bookingFee:     " + r.getBookingFee());
			pw.println("totalAmount:    " + r.getTotalAmount());
			pw.println();
			pw.println("city:           " + r.getCity());
		}

		/** 户口本 */
		static void writeHousehold(Object o, PrintWriter pw) {
			HouseholdRegisterResult r = (HouseholdRegisterResult) o;
			pw.println("householdNo:        " + r.getHouseholdNo());
			pw.println("name:               " + r.getName());
			pw.println("relationship:       " + r.getRelationship());
			pw.println("gender:             " + r.getGender());
			pw.println("birthPlace:         " + r.getBirthPlace());
			pw.println("ethnicity:          " + r.getEthnicity());
			pw.println("nativePlace:        " + r.getNativePlace());
			pw.println("birthDate:          " + r.getBirthDate());
			pw.println("idNumber:           " + r.getIdNumber());
			pw.println("height:             " + r.getHeight());
			pw.println("education:          " + r.getEducation());
			pw.println("workplace:          " + r.getWorkplace());
			pw.println("moveToCityDate:     " + r.getMoveToCityDate());
			pw.println("moveToAddress:      " + r.getMoveToAddress());
			pw.println("registrationDate:   " + r.getRegistrationDate());
		}

		/** 拼多多福袋 */
		static void writePdd(Object o, PrintWriter pw) {
			PddLuckyBagResult r = (PddLuckyBagResult) o;
			pw.println("luckyBagCode:  " + r.getLuckyBagCode());
			PddLuckyBagType type = r.getType();
			pw.println("type:          " + type + " / " + (type == null ? "" : type.getDesc()));
		}
	}
}
