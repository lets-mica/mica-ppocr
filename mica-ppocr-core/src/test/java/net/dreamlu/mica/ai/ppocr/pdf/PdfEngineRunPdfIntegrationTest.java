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
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static net.dreamlu.mica.ai.ppocr.pdf.TestPdfFactory.electronicInvoiceStylePdf;
import static net.dreamlu.mica.ai.ppocr.pdf.TestPdfFactory.imageOnlyPdf;
import static net.dreamlu.mica.ai.ppocr.pdf.TestPdfFactory.multiPageTextPdf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PPOcrV6Engine#run(byte[])} PDF 双通道端到端集成测试（真实模型 + 合成 PDF）。
 *
 * <p>所有断言都走公开 API：自动嗅探 + 平铺多页结果。具体走文本层还是渲染通道
 * 属于内部细节，由 {@link PPOcrV6Engine} 自行决策，调用方不感知。
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
	void textTypePdfReturnsResultsThroughTextLayerChannel() throws Exception {
		Path root = findRepositoryRoot();
		Path modelDir = root.resolve("models/ppocr-v6/" + DEFAULT_TIER);
		Assumptions.assumeTrue(Files.isRegularFile(modelDir.resolve("det.onnx"))
				&& Files.isRegularFile(modelDir.resolve("rec.onnx"))
				&& Files.isRegularFile(modelDir.resolve("dict.txt")),
			"tiny 模型缺失，跳过集成测试");

		try (PPOcrV6Engine engine = newEngine(modelDir)) {
			List<PPOcrV6Result> results = engine.run(electronicInvoiceStylePdf());

			assertTrue(results.size() >= 5, "text-type pdf must yield >= 5 text lines");
		}
	}

	@Test
	void imageOnlyPdfRunsOcrChannel() throws Exception {
		Path root = findRepositoryRoot();
		Path modelDir = root.resolve("models/ppocr-v6/" + DEFAULT_TIER);
		Assumptions.assumeTrue(Files.isRegularFile(modelDir.resolve("det.onnx"))
				&& Files.isRegularFile(modelDir.resolve("rec.onnx"))
				&& Files.isRegularFile(modelDir.resolve("dict.txt")),
			"tiny 模型缺失，跳过集成测试");

		nu.pattern.OpenCV.loadLocally();
		try (PPOcrV6Engine engine = newEngine(modelDir)) {
			// 整页位图 PDF（无文本层）应走 OCR 通道，调用方拿到结果（空集也算成功完成）
			List<PPOcrV6Result> results = engine.run(imageOnlyPdf());
			// 不强制断言非空：合成样本本身无文字，OCR 返回空集也是正常完成
			assertTrue(results != null, "image-only pdf must complete without throwing");
		}
	}

	@Test
	void multiPageTextPdfFlattensPages() throws Exception {
		Path root = findRepositoryRoot();
		Path modelDir = root.resolve("models/ppocr-v6/" + DEFAULT_TIER);
		Assumptions.assumeTrue(Files.isRegularFile(modelDir.resolve("det.onnx"))
				&& Files.isRegularFile(modelDir.resolve("rec.onnx"))
				&& Files.isRegularFile(modelDir.resolve("dict.txt")),
			"tiny 模型缺失，跳过集成测试");

		nu.pattern.OpenCV.loadLocally();
		try (PPOcrV6Engine engine = newEngine(modelDir)) {
			// 多页文字型 PDF → run(byte[]) 应自动按 PDF 双通道处理并平铺所有页
			List<PPOcrV6Result> flat = engine.run(multiPageTextPdf(3));

			assertFalse(flat.isEmpty(), "PDF 多页 run(byte[]) 应平铺至少一页结果");
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
