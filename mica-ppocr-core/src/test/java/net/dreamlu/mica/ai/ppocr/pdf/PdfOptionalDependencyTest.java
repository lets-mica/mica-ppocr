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

package net.dreamlu.mica.ai.ppocr.pdf;

import net.dreamlu.mica.ai.ppocr.config.PPOcrV6Config;
import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Engine;
import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Result;
import net.dreamlu.mica.ai.ppocr.utils.CollUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static net.dreamlu.mica.ai.ppocr.pdf.TestPdfFactory.electronicInvoiceStylePdf;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「PDF 依赖可选」的行为契约测试。
 *
 * <p>三条红线，每条一个测试：
 * <ol>
 *   <li><b>缺依赖时报错要可读</b>：引擎收到 PDF 输入时抛
 *       {@link IllegalStateException}，消息里指明要补哪个依赖 ——
 *       而不是让调用方去追 {@code NoClassDefFoundError}。</li>
 *   <li><b>缺依赖不影响图片</b>：只做图片 OCR 的项目不引入 PDFBox，
 *       引擎的构造与图片推理必须完全不受影响。</li>
 *   <li><b>引擎字节码不含 PDFBox</b>：从 class 文件层面锁死
 *       「引擎不引用 PDFBox 类型」——否则缺依赖时连引擎自身都链接不了。</li>
 * </ol>
 *
 * <p>「PDFBox 不在 classpath」这一状态无法在单测里真实构造（core 自身的测试
 * 需要 pdfbox），因此用包内测试钩子 {@link PdfDependency#forcedAvailable} 模拟。
 */
class PdfOptionalDependencyTest {

	@AfterEach
	void resetForcedAvailability() {
		PdfDependency.forcedAvailable = null;
	}

	@Test
	void pdfBoxIsDetectedOnClasspath() {
		assertTrue(PdfDependency.isAvailable(), "core 测试 classpath 上带 pdfbox，探测应命中");
	}

	@Test
	void engineThrowsActionableErrorWhenPdfBoxMissing() throws Exception {
		Path modelDir = tinyModelDir();
		Assumptions.assumeTrue(modelDir != null, "tiny 模型缺失，跳过集成测试");

		PdfDependency.forcedAvailable = Boolean.FALSE;
		try (PPOcrV6Engine engine = newEngine(modelDir)) {
			IllegalStateException ex = assertThrows(IllegalStateException.class,
				() -> engine.run(electronicInvoiceStylePdf()));
			assertTrue(ex.getMessage().contains("pdfbox"),
				"报错消息应指明要补的依赖，实际：" + ex.getMessage());
		}
	}

	@Test
	void engineStillRunsImageOcrWhenPdfBoxMissing() throws Exception {
		Path modelDir = tinyModelDir();
		Assumptions.assumeTrue(modelDir != null, "tiny 模型缺失，跳过集成测试");

		PdfDependency.forcedAvailable = Boolean.FALSE;
		try (PPOcrV6Engine engine = newEngine(modelDir)) {
			// 只做图片 OCR 的项目不引入 pdfbox —— 这条路径必须毫发无损
			List<PPOcrV6Result> results = assertDoesNotThrow(() -> engine.run(strokedPng()));
			assertNotNull(results, "图片推理结果不应为 null");
		}
	}

	@Test
	void engineClassFileHasNoPdfBoxClassReference() throws Exception {
		// 结构性护栏：引擎 class 文件里不能出现 PDFBox 的<b>类引用</b>
		// （常量池里的类引用是 "org/apache/pdfbox/..." 斜杠形式；
		//   错误文案里的依赖坐标 "org.apache.pdfbox:pdfbox:3.0.8" 是点分字符串常量，
		//   被 javac 内联进引擎也无害 —— 字符串常量不会触发类加载）
		String engineClass = classFileAsString(PPOcrV6Engine.class);
		assertFalse(engineClass.contains("org/apache/pdfbox"),
			"PPOcrV6Engine 不应引用 PDFBox 类型，PDF 调用应全部收敛到 PdfRuntime");
	}

	@Test
	void pdfDependencyClassOnlyMentionsPdfBoxAsStringLiteral() throws Exception {
		String dependencyClass = classFileAsString(PdfDependency.class);
		assertTrue(dependencyClass.contains("org.apache.pdfbox.Loader"),
			"探测类应以字符串字面量形式记录探测标记类");
		assertFalse(dependencyClass.contains("org/apache/pdfbox"),
			"探测类不得以类引用形式引用 PDFBox，否则缺依赖时探测自身会失败");
	}

	@Test
	void pdfRuntimeClassIsTheOnlyHolderOfPdfBoxReferences() throws Exception {
		// PdfRuntime 是唯一允许直接触碰 PDFBox API 的类
		String runtimeClass = classFileAsString(PdfRuntime.class);
		assertTrue(runtimeClass.contains("org/apache/pdfbox"),
			"PdfRuntime 应直接引用 PDFBox API");
	}

	private static String classFileAsString(Class<?> clazz) throws IOException {
		String resource = clazz.getName().replace('.', '/') + ".class";
		ClassLoader loader = clazz.getClassLoader();
		try (InputStream in = loader.getResourceAsStream(resource)) {
			assertNotNull(in, "找不到 class 文件资源：" + resource);
			return new String(CollUtil.readAllBytes(in), StandardCharsets.ISO_8859_1);
		}
	}

	/**
	 * 生成一张带笔画的 PNG：用于验证「缺 pdfbox 时图片链路不受影响」。
	 */
	private static byte[] strokedPng() throws IOException {
		BufferedImage image = new BufferedImage(160, 60, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		try {
			g.setColor(Color.WHITE);
			g.fillRect(0, 0, 160, 60);
			g.setColor(Color.BLACK);
			for (int x = 10; x < 150; x += 12) {
				g.drawLine(x, 10, x + 6, 50);
			}
		} finally {
			g.dispose();
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "png", out);
		return out.toByteArray();
	}

	private static PPOcrV6Engine newEngine(Path modelDir) {
		PPOcrV6Config config = PPOcrV6Config.builder()
			.detModelPath(modelDir.resolve("det.onnx").toString())
			.recModelPath(modelDir.resolve("rec.onnx").toString())
			.recCharDictPath(modelDir.resolve("dict.txt").toString())
			.build();
		return new PPOcrV6Engine(config);
	}

	private static Path tinyModelDir() {
		Path root = findRepositoryRoot();
		if (root == null) {
			return null;
		}
		Path modelDir = root.resolve("models/ppocr-v6/tiny");
		boolean ok = Files.isRegularFile(modelDir.resolve("det.onnx"))
			&& Files.isRegularFile(modelDir.resolve("rec.onnx"))
			&& Files.isRegularFile(modelDir.resolve("dict.txt"));
		return ok ? modelDir : null;
	}

	private static Path findRepositoryRoot() {
		String multiModuleDir = System.getProperty("maven.multiModuleProjectDirectory");
		if (multiModuleDir != null && Files.isDirectory(CollUtil.pathOf(multiModuleDir).resolve("models"))) {
			return CollUtil.pathOf(multiModuleDir);
		}
		Path current = CollUtil.pathOf("").toAbsolutePath();
		while (current != null && !Files.isDirectory(current.resolve("models"))) {
			current = current.getParent();
		}
		return current;
	}
}
