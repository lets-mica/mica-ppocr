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
import net.dreamlu.mica.ai.ppocr.utils.CollUtil;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static net.dreamlu.mica.ai.ppocr.pdf.TestPdfFactory.electronicInvoiceStylePdf;
import static net.dreamlu.mica.ai.ppocr.pdf.TestPdfFactory.imageOnlyPdf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PPOcrV6Engine#runPdf} 端到端集成测试（真实模型 + 合成 PDF）。
 *
 * <p>依赖仓库根目录 {@code models/ppocr-v6/tiny/} 模型；模型缺失时通过
 * {@link Assumptions} 跳过（模型不随仓库分发）。
 *
 * <p>渲染通道样本为合成"扫描件"（整页位图 PDF）；真实数电票 / 扫描发票
 * PDF 样本验证请补充至 test_resources 后扩展本类。
 */
class PdfEngineRunPdfIntegrationTest {

	private static final String DEFAULT_TIER = "tiny";

	private static Path findRepositoryRoot() {
		String multiModuleDir = System.getProperty("maven.multiModuleProjectDirectory");
		if (multiModuleDir != null && Files.isDirectory(CollUtil.pathOf(multiModuleDir).resolve("models"))) {
			return CollUtil.pathOf(multiModuleDir);
		}
		Path current = CollUtil.pathOf("").toAbsolutePath();
		while (current != null && !Files.isDirectory(current.resolve("models"))) {
			current = current.getParent();
		}
		if (current == null) {
			throw new IllegalStateException("repository root not found");
		}
		return current;
	}

	@Test
	void textTypePdfGoesThroughTextLayerChannel() throws Exception {
		Path root = findRepositoryRoot();
		Path modelDir = root.resolve("models/ppocr-v6/" + DEFAULT_TIER);
		Assumptions.assumeTrue(Files.isRegularFile(modelDir.resolve("det.onnx"))
				&& Files.isRegularFile(modelDir.resolve("rec.onnx"))
				&& Files.isRegularFile(modelDir.resolve("dict.txt")),
			"tiny 模型缺失，跳过集成测试");

		try (PPOcrV6Engine engine = newEngine(modelDir)) {
			List<PdfPageResult> pages = engine.runPdf(electronicInvoiceStylePdf());

			assertEquals(1, pages.size());
			assertFalse(pages.get(0).viaOcr(), "text-type pdf must not consume inference");
			assertTrue(pages.get(0).results().size() >= 5);
		}
	}

	@Test
	void imageOnlyPdfGoesThroughOcrChannel() throws Exception {
		Path root = findRepositoryRoot();
		Path modelDir = root.resolve("models/ppocr-v6/" + DEFAULT_TIER);
		Assumptions.assumeTrue(Files.isRegularFile(modelDir.resolve("det.onnx"))
				&& Files.isRegularFile(modelDir.resolve("rec.onnx"))
				&& Files.isRegularFile(modelDir.resolve("dict.txt")),
			"tiny 模型缺失，跳过集成测试");

		nu.pattern.OpenCV.loadLocally();
		try (PPOcrV6Engine engine = newEngine(modelDir)) {
			List<PdfPageResult> pages = engine.runPdf(imageOnlyPdf());

			assertEquals(1, pages.size());
			assertTrue(pages.get(0).viaOcr(), "image-only pdf must go through ocr channel");
			assertEquals(0, pages.get(0).pageIndex());
		}
	}

	private static PPOcrV6Engine newEngine(Path modelDir) {
		nu.pattern.OpenCV.loadLocally();
		PPOcrV6Config config = PPOcrV6Config.builder()
			.detModelPath(modelDir.resolve("det.onnx").toString())
			.recModelPath(modelDir.resolve("rec.onnx").toString())
			.recCharDictPath(modelDir.resolve("dict.txt").toString())
			.build();
		return new PPOcrV6Engine(config);
	}
}
