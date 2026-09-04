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
import net.dreamlu.mica.ai.ppocr.structured.parser.invoice.InvoiceParser;
import net.dreamlu.mica.ai.ppocr.structured.parser.invoice.InvoiceResult;
import net.dreamlu.mica.ai.ppocr.utils.CollUtil;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真样本端到端对照：同一份 PDF（{@code E:\如萌59976.pdf}）分别走
 * <ol>
 *   <li>文本层通道 → InvoiceParser；</li>
 *   <li>OCR 通道（forceOcr=true）→ InvoiceParser。</li>
 * </ol>
 * 对比两份结构化结果，量化"文本层零模型误差"相对 OCR 通道的字段差异。
 *
 * <p>不强制依赖 tiny 模型——文本层通道零推理；
 * OCR 通道对照需要 tiny 模型，缺失时该 case 自动 skip。
 */
class RealSampleInvoiceParseTest {

	private static final String SAMPLE_PDF = "E:\\如萌59976.pdf";

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
	void textLayerChannelVsOcrChannelInvoiceParse() throws Exception {
		File sample = new File(SAMPLE_PDF);
		Assumptions.assumeTrue(sample.isFile(),
			"真实样本 PDF 不存在: " + SAMPLE_PDF);

		Path root = findRepositoryRoot();
		Path modelDir = root.resolve("models/ppocr-v6/tiny");
		Assumptions.assumeTrue(Files.isRegularFile(modelDir.resolve("det.onnx"))
				&& Files.isRegularFile(modelDir.resolve("rec.onnx"))
				&& Files.isRegularFile(modelDir.resolve("dict.txt")),
			"tiny 模型缺失，跳过端到端对照");

		byte[] pdfBytes = Files.readAllBytes(sample.toPath());

		// === 文本层通道 ===
		List<PPOcrV6Result> textLayerResults = collectAllPages(pdfBytes, null);
		InvoiceParser textParser = new InvoiceParser(null);
		InvoiceResult textResult = textParser.parseResults(textLayerResults);

		// === OCR 通道（forceOcr=true）===
		nu.pattern.OpenCV.loadLocally();
		PPOcrV6Config config = PPOcrV6Config.builder()
			.detModelPath(modelDir.resolve("det.onnx").toString())
			.recModelPath(modelDir.resolve("rec.onnx").toString())
			.recCharDictPath(modelDir.resolve("dict.txt").toString())
			.build();
		try (PPOcrV6Engine engine = new PPOcrV6Engine(config)) {
			List<PPOcrV6Result> ocrResults = collectAllPages(pdfBytes,
				new PdfOcrSupport(engine, PdfOcrConfig.builder().forceOcr(true).build()));
			InvoiceParser ocrParser = new InvoiceParser(null);
			InvoiceResult ocrResult = ocrParser.parseResults(ocrResults);

			// 字段对照报告写到临时文件（stdout 经 GBK 转码会丢字符）
			StringBuilder report = new StringBuilder();
			report.append("=== 真样本端到端对照 (E:\\如萌59976.pdf) ===\n");
			report.append(String.format("%-22s | %-50s | %-50s%n", "field", "text-layer", "ocr"));
			report.append(CollUtil.repeat("-", 130)).append('\n');

			compare(report, "version", String.valueOf(textResult.getVersion()), String.valueOf(ocrResult.getVersion()));
			compare(report, "invoiceNo", textResult.getInvoiceNo(), ocrResult.getInvoiceNo());
			compare(report, "invoiceDate", textResult.getInvoiceDate(), ocrResult.getInvoiceDate());
			compare(report, "buyerName", textResult.getBuyerName(), ocrResult.getBuyerName());
			compare(report, "buyerTaxNo", textResult.getBuyerTaxNo(), ocrResult.getBuyerTaxNo());
			compare(report, "sellerName", textResult.getSellerName(), ocrResult.getSellerName());
			compare(report, "sellerTaxNo", textResult.getSellerTaxNo(), ocrResult.getSellerTaxNo());
			compare(report, "totalAmountUpper", textResult.getTotalAmountUpper(), ocrResult.getTotalAmountUpper());
			compare(report, "totalAmountLower", textResult.getTotalAmountLower(), ocrResult.getTotalAmountLower());
			compare(report, "issuer", textResult.getIssuer(), ocrResult.getIssuer());

			// 明细行数
			int textItemCount = textResult.getItems() == null ? 0 : textResult.getItems().size();
			int ocrItemCount = ocrResult.getItems() == null ? 0 : ocrResult.getItems().size();
			compare(report, "items.size", String.valueOf(textItemCount), String.valueOf(ocrItemCount));
			report.append("\n--- 明细对照 ---\n");
			int maxItems = Math.max(textItemCount, ocrItemCount);
			for (int i = 0; i < maxItems; i++) {
				String tName = (textResult.getItems() != null && i < textItemCount)
					? textResult.getItems().get(i).getGoodsName() : "<none>";
				String oName = (ocrResult.getItems() != null && i < ocrItemCount)
					? ocrResult.getItems().get(i).getGoodsName() : "<none>";
				compare(report, "items[" + i + "].goodsName", tName, oName);
			}

			Path reportFile = new File(System.getProperty("java.io.tmpdir"), "pdf-invoice-compare.txt").toPath();
			Files.write(reportFile, report.toString().getBytes(StandardCharsets.UTF_8));
			assertNotNull(reportFile);

			// 基本断言：文本层应解析出关键字段（OCR 通道不强求，因 tiny 模型已知存在字符误识）
			assertNotNull(textResult.getInvoiceNo(), "文本层应抽取到发票号码");
			assertEquals(20, textResult.getInvoiceNo().length(), "发票号码应为 20 位");
			assertEquals("26332000007137286396", textResult.getInvoiceNo(), "发票号码字符无损");
			assertTrue(textResult.getInvoiceDate() != null && textResult.getInvoiceDate().contains("2026"),
				"开票日期应包含 2026 年");
		}
	}

	private static void compare(StringBuilder sb, String field, String text, String ocr) {
		boolean equal = (text == null && ocr == null) || (text != null && text.equals(ocr));
		String marker = equal ? "  " : "✗ ";
		sb.append(String.format("%s%-20s | %-50s | %-50s%n",
			marker, field,
			safe(text), safe(ocr)));
	}

	private static String safe(String s) {
		return s == null ? "<null>" : s;
	}

	private static List<PPOcrV6Result> collectAllPages(byte[] pdfBytes, PdfOcrSupport support) throws java.io.IOException {
		List<PPOcrV6Result> all = new ArrayList<>();
		List<PdfPageResult> pages;
		if (support == null) {
			pages = new PdfOcrSupport(null).run(pdfBytes);
		} else {
			pages = support.run(pdfBytes);
		}
		for (PdfPageResult page : pages) {
			all.addAll(page.results());
		}
		return all;
	}
}
