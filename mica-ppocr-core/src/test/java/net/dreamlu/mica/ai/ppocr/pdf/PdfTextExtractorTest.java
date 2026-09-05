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

import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Result;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static net.dreamlu.mica.ai.ppocr.pdf.TestPdfFactory.electronicInvoiceStylePdf;
import static net.dreamlu.mica.ai.ppocr.pdf.TestPdfFactory.imageOnlyPdf;
import static net.dreamlu.mica.ai.ppocr.pdf.TestPdfFactory.multiPageTextPdf;
import static net.dreamlu.mica.ai.ppocr.pdf.TestPdfFactory.spec;
import static net.dreamlu.mica.ai.ppocr.pdf.TestPdfFactory.textPdf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PdfTextExtractor} 单元测试：合成 PDF 驱动，无需模型与 OpenCV。
 */
class PdfTextExtractorTest {

	private final PdfTextExtractor extractor = new PdfTextExtractor();

	@Test
	void extractsTextLinesInReadingOrder() throws IOException {
		try (PDDocument doc = Loader.loadPDF(electronicInvoiceStylePdf())) {
			List<PPOcrV6Result> results = extractor.extract(doc, 0);

			// "Invoice No"（x=60）与 "Issue Date"（x=380）同 baseline，
			// 间距远超 2 倍字高 → 按 OCR det 语义拆为独立结果，共 5 条
			assertEquals(5, results.size());

			// 阅读顺序：自上而下、行内从左到右
			assertEquals("ELECTRONIC INVOICE", results.get(0).text());
			assertEquals("Invoice No: 25317000001234567890", results.get(1).text());
			assertEquals("Issue Date: 2026-09-04", results.get(2).text());
			assertEquals("Amount: 12345.67", results.get(3).text());
			assertEquals("Tax: 678.90", results.get(4).text());

			// score 恒为 1.0（内嵌文本，非模型识别）
			for (PPOcrV6Result r : results) {
				assertEquals(1.0f, r.score(), 1e-6f);
				assertEquals(0, r.rotatedDegrees());
				assertEquals(4, r.box().length);
			}

			// y 单调递增（左上原点，自上而下）
			for (int i = 1; i < results.size(); i++) {
				assertTrue(results.get(i).box()[0][1] >= results.get(i - 1).box()[0][1],
					"line y should be monotonic increasing, line " + i);
			}

			// x 起点 ≈ 写入坐标（±3pt 容差）
			assertEquals(60f, results.get(0).box()[0][0], 3f);
			assertEquals(60f, results.get(1).box()[0][0], 3f);
			assertEquals(380f, results.get(2).box()[0][0], 3f);

			// 拆分验证：两条结果的 x 不相交
			assertTrue(results.get(1).box()[1][0] < results.get(2).box()[0][0],
				"'Invoice No' box should not overlap 'Issue Date' box");
		}
	}

	@Test
	void mergesCloseChunksOnSameLine() throws IOException {
		// 同一行两个相邻 chunk（间距 ≈10pt < 2 倍字高 24pt）应合并为一条；
		// 间距更大时会被拆分（拆分阈值语义对齐 OCR det 切框行为）
		byte[] pdf = textPdf(
			spec("Total:", 60, 700, 12),
			spec("USD 99.50", 100, 700, 12)
		);
		try (PDDocument doc = Loader.loadPDF(pdf)) {
			List<PPOcrV6Result> results = extractor.extract(doc, 0);
			assertEquals(1, results.size());
			assertEquals("Total:USD 99.50", results.get(0).text());
		}
	}

	@Test
	void extractsEachPageIndependently() throws IOException {
		byte[] pdf = multiPageTextPdf(2);
		try (PDDocument doc = Loader.loadPDF(pdf)) {
			assertEquals(2, doc.getNumberOfPages());
			List<PPOcrV6Result> page0 = extractor.extract(doc, 0);
			List<PPOcrV6Result> page1 = extractor.extract(doc, 1);
			assertEquals(1, page0.size());
			assertEquals(1, page1.size());
			assertEquals("PAGE 1 OF 2 SAMPLE ELECTRONIC INVOICE CONTENT", page0.get(0).text());
			assertEquals("PAGE 2 OF 2 SAMPLE ELECTRONIC INVOICE CONTENT", page1.get(0).text());
		}
	}

	@Test
	void imageOnlyPdfHasNoTextLayer() throws IOException {
		try (PDDocument doc = Loader.loadPDF(imageOnlyPdf())) {
			List<PPOcrV6Result> results = extractor.extract(doc, 0);
			assertTrue(results.isEmpty(), "image-only page should yield no text");

			PdfTextQuality quality = extractor.quality(results);
			assertEquals(0, quality.totalChars());
			assertFalse(quality.usable(20, 0.90));
		}
	}

	@Test
	void qualityCountsReadableAndUnreadableChars() {
		// 全可读
		List<PPOcrV6Result> readable = resultsOf("发票号码: 25317000001234567890");
		PdfTextQuality q1 = extractor.quality(readable);
		assertEquals(q1.totalChars(), q1.readableChars());
		assertEquals(1.0, q1.readableRatio(), 1e-9);

		// 含私有区乱码（模拟缺 ToUnicode 的 CID 字体抽取结果）
		List<PPOcrV6Result> garbled = resultsOf("\uE000\uE001\uE002\uE003\uE004\uE005\uE006\uE007"
			+ "\uE008\uE009\uE00A\uE00B\uE00C\uE00D\uE00E\uE00F"
			+ "\uE010\uE011\uE012\uE013\uE014\uE015\uE016\uE017"
			+ "normal");
		PdfTextQuality q2 = extractor.quality(garbled);
		assertEquals(30, q2.totalChars());
		assertEquals(6, q2.readableChars());
		assertFalse(q2.usable(20, 0.90), "garbled text layer should be unusable");
	}

	@Test
	void extractRejectsBadArguments() throws IOException {
		try (PDDocument doc = Loader.loadPDF(electronicInvoiceStylePdf())) {
			assertThrows(IllegalArgumentException.class, () -> extractor.extract(null, 0));
			assertThrows(IndexOutOfBoundsException.class, () -> extractor.extract(doc, -1));
			assertThrows(IndexOutOfBoundsException.class, () -> extractor.extract(doc, doc.getNumberOfPages()));
		}
	}

	private static List<PPOcrV6Result> resultsOf(String text) {
		List<PPOcrV6Result> results = new ArrayList<>();
		int[][] box = {{0, 0}, {100, 0}, {100, 20}, {0, 20}};
		results.add(new PPOcrV6Result(text, 1.0f, box));
		return results;
	}
}
