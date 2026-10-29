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
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link PdfOcrConfig} 接线测试。
 *
 * <p>覆盖两层：
 * <ol>
 *   <li><b>契约层</b>：{@link PdfOcrConfig#from(PdfOcrConfig.Source)} 的字段映射与空值回落</li>
 *   <li><b>功能层</b>：{@link PPOcrV6Engine} 的 PDF 入口<b>真的消费</b>
 *       {@link PPOcrV6Config#getPdfOcrConfig()}，而不是继续用硬编码的
 *       {@link PdfOcrConfig#defaults()}</li>
 * </ol>
 *
 * <p>功能层的证明手法：构造一份「白字白底」的文字型 PDF ——
 * 文本层能无损抽出文字（PDF 内容流里有字），而渲染通道渲染出的是一张全白图，
 * OCR 必然识别不出任何文字。于是：
 * <ul>
 *   <li>默认配置（走文本层）→ 结果含标记串</li>
 *   <li>{@code forceOcr=true}（走渲染通道）→ 结果为空</li>
 * </ul>
 * 若引擎仍在用硬编码默认值，第二条会拿到标记串而失败 —— 配置是否被消费由此确定。
 */
class PdfOcrConfigWiringTest {

	private static final String MARKER = "WIREPROOFMARKER12345";

	// ==================================================================
	// 契约层：from(Source)
	// ==================================================================

	/**
	 * 最简 {@link PdfOcrConfig.Source} 实现。
	 */
	private static final class FixedSource implements PdfOcrConfig.Source {
		private final int renderDpi;
		private final int minTextChars;
		private final double minReadableRatio;
		private final boolean forceOcr;

		private FixedSource(int renderDpi, int minTextChars, double minReadableRatio, boolean forceOcr) {
			this.renderDpi = renderDpi;
			this.minTextChars = minTextChars;
			this.minReadableRatio = minReadableRatio;
			this.forceOcr = forceOcr;
		}

		@Override
		public int getRenderDpi() {
			return renderDpi;
		}

		@Override
		public int getMinTextChars() {
			return minTextChars;
		}

		@Override
		public double getMinReadableRatio() {
			return minReadableRatio;
		}

		@Override
		public boolean isForceOcr() {
			return forceOcr;
		}
	}

	@Test
	void from_mapsAllFields() {
		PdfOcrConfig cfg = PdfOcrConfig.from(new FixedSource(300, 50, 0.8, true));
		assertEquals(300, cfg.getRenderDpi());
		assertEquals(50, cfg.getMinTextChars());
		assertEquals(0.8, cfg.getMinReadableRatio(), 1e-9);
		assertTrue(cfg.isForceOcr());
	}

	@Test
	void from_nullFallsBackToDefaults() {
		PdfOcrConfig cfg = PdfOcrConfig.from(null);
		assertEquals(PdfOcrConfig.defaults().getRenderDpi(), cfg.getRenderDpi());
		assertEquals(PdfOcrConfig.defaults().getMinTextChars(), cfg.getMinTextChars());
		assertEquals(PdfOcrConfig.defaults().getMinReadableRatio(), cfg.getMinReadableRatio(), 1e-9);
		assertEquals(PdfOcrConfig.defaults().isForceOcr(), cfg.isForceOcr());
	}

	@Test
	void defaults_areStable() {
		// 默认值是引擎「接通前」的实际行为，接通后必须保持一致（不改变默认运行结果）
		PdfOcrConfig d = PdfOcrConfig.defaults();
		assertEquals(200, d.getRenderDpi());
		assertEquals(20, d.getMinTextChars());
		assertEquals(0.90, d.getMinReadableRatio(), 1e-9);
		assertFalse(d.isForceOcr());
	}

	@Test
	void configBuilder_defaultIsDefaultsInstance() {
		// 未显式设置 pdfOcrConfig 时，PPOcrV6Config 应回落到 PdfOcrConfig.defaults()
		PPOcrV6Config config = PPOcrV6Config.builder()
			.detModelPath("a")
			.recModelPath("b")
			.recCharDictPath("c")
			.build();
		assertNotNull(config.getPdfOcrConfig());
		assertEquals(PdfOcrConfig.defaults().getRenderDpi(), config.getPdfOcrConfig().getRenderDpi());
		assertEquals(PdfOcrConfig.defaults().getMinTextChars(), config.getPdfOcrConfig().getMinTextChars());
	}

	// ==================================================================
	// 功能层：引擎真的消费配置
	// ==================================================================

	@Test
	void engine_consumesConfiguredPdfOcrConfig() throws Exception {
		Path modelDir = tinyModelDir();
		Assumptions.assumeTrue(modelDir != null, "tiny 模型缺失，跳过集成测试");

		byte[] whiteTextPdf = whiteTextPdf(MARKER);

		// 默认配置 → 走文本层，无损抽出标记串
		try (PPOcrV6Engine engine = newEngine(modelDir, PdfOcrConfig.defaults())) {
			List<PPOcrV6Result> results = engine.run(whiteTextPdf);
			assertTrue(containsMarker(results),
				"默认配置应走文本层通道，结果应包含标记串");
		}

		// forceOcr=true → 走渲染通道，页面渲染为全白图，OCR 必然识别不到文字
		try (PPOcrV6Engine engine = newEngine(modelDir,
			PdfOcrConfig.builder().forceOcr(true).build())) {
			List<PPOcrV6Result> results = engine.run(whiteTextPdf);
			assertFalse(containsMarker(results),
				"forceOcr=true 时应走渲染通道（页面全白），结果不应包含标记串");
		}
	}

	@Test
	void engine_exposesEffectivePdfOcrConfig() throws Exception {
		Path modelDir = tinyModelDir();
		Assumptions.assumeTrue(modelDir != null, "tiny 模型缺失，跳过集成测试");

		PdfOcrConfig custom = PdfOcrConfig.builder()
			.renderDpi(300)
			.minTextChars(50)
			.minReadableRatio(0.8)
			.forceOcr(true)
			.build();
		try (PPOcrV6Engine engine = newEngine(modelDir, custom)) {
			assertEquals(300, engine.getPdfOcrConfig().getRenderDpi());
			assertEquals(50, engine.getPdfOcrConfig().getMinTextChars());
			assertEquals(0.8, engine.getPdfOcrConfig().getMinReadableRatio(), 1e-9);
			assertTrue(engine.getPdfOcrConfig().isForceOcr());
		}
	}

	@Test
	void engine_nullPdfConfigFallsBackToDefaults() throws Exception {
		Path modelDir = tinyModelDir();
		Assumptions.assumeTrue(modelDir != null, "tiny 模型缺失，跳过集成测试");

		PPOcrV6Config config = PPOcrV6Config.builder()
			.detModelPath(modelDir.resolve("det.onnx").toString())
			.recModelPath(modelDir.resolve("rec.onnx").toString())
			.recCharDictPath(modelDir.resolve("dict.txt").toString())
			.pdfOcrConfig(null)
			.build();
		try (PPOcrV6Engine engine = new PPOcrV6Engine(config)) {
			assertNotNull(engine.getPdfOcrConfig());
			assertEquals(PdfOcrConfig.defaults().getRenderDpi(), engine.getPdfOcrConfig().getRenderDpi());
		}
	}

	private static boolean containsMarker(List<PPOcrV6Result> results) {
		for (PPOcrV6Result r : results) {
			if (r.text() != null && r.text().contains(MARKER)) {
				return true;
			}
		}
		return false;
	}

	private static PPOcrV6Engine newEngine(Path modelDir, PdfOcrConfig pdfOcrConfig) {
		PPOcrV6Config config = PPOcrV6Config.builder()
			.detModelPath(modelDir.resolve("det.onnx").toString())
			.recModelPath(modelDir.resolve("rec.onnx").toString())
			.recCharDictPath(modelDir.resolve("dict.txt").toString())
			.pdfOcrConfig(pdfOcrConfig)
			.build();
		return new PPOcrV6Engine(config);
	}

	/**
	 * 生成「白字白底」文字型 PDF：内容流里有文字（文本层可抽取），
	 * 但渲染出来是一张全白图（渲染通道 OCR 识别不到任何文字）。
	 */
	private static byte[] whiteTextPdf(String text) throws IOException {
		try (PDDocument doc = new PDDocument()) {
			PDPage page = new PDPage(PDRectangle.A4);
			doc.addPage(page);
			try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
				cs.beginText();
				cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 18);
				cs.newLineAtOffset(60, 700);
				// 白色描边：渲染到白底上不可见
				cs.setNonStrokingColor(1f, 1f, 1f);
				cs.showText(text);
				cs.endText();
			}
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			doc.save(out);
			return out.toByteArray();
		}
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
