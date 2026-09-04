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

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static net.dreamlu.mica.ai.ppocr.pdf.TestPdfFactory.electronicInvoiceStylePdf;
import static net.dreamlu.mica.ai.ppocr.pdf.TestPdfFactory.imageOnlyPdf;
import static net.dreamlu.mica.ai.ppocr.pdf.TestPdfFactory.multiPageTextPdf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PdfOcrSupport} 单元测试：engine 传 null，全部走文本层通道或参数校验，
 * 不依赖模型与 OpenCV。渲染通道的真 OCR 链路见 {@code PdfOcrSupportIntegrationTest}。
 */
class PdfOcrSupportTest {

	private final PdfOcrSupport support = new PdfOcrSupport(null);

	@Test
	void isPdfMagicSniffing() {
		assertTrue(PdfOcrSupport.isPdf("%PDF-1.7\n%âãÏÓ\n".getBytes(StandardCharsets.ISO_8859_1)));
		assertTrue(PdfOcrSupport.isPdf("%PDF-1.4".getBytes(StandardCharsets.ISO_8859_1)));
		// header 前垃圾数据（1024 字节窗口内）
		byte[] withJunk = new byte[100 + "%PDF-1.7".length()];
		new java.util.Random(42).nextBytes(withJunk);
		byte[] magic = "%PDF-1.7".getBytes(StandardCharsets.ISO_8859_1);
		System.arraycopy(magic, 0, withJunk, 100, magic.length);
		assertTrue(PdfOcrSupport.isPdf(withJunk));
		// 非 PDF
		assertFalse(PdfOcrSupport.isPdf(null));
		assertFalse(PdfOcrSupport.isPdf(new byte[0]));
		assertFalse(PdfOcrSupport.isPdf(new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A})); // PNG
		assertFalse(PdfOcrSupport.isPdf("PDF-1.7".getBytes(StandardCharsets.ISO_8859_1))); // 缺 %
	}

	@Test
	void runRejectsNonPdfBytes() {
		assertThrows(IllegalArgumentException.class, () -> support.run((byte[]) null));
		assertThrows(IllegalArgumentException.class, () -> support.run(new byte[0]));
		byte[] png = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
		assertThrows(IllegalArgumentException.class, () -> support.run(png));
	}

	@Test
	void runTextLayerChannel() throws IOException {
		List<PdfPageResult> pages = support.run(electronicInvoiceStylePdf());

		assertEquals(1, pages.size());
		PdfPageResult page = pages.get(0);
		assertEquals(0, page.pageIndex());
		assertFalse(page.viaOcr(), "text-type pdf should go through text layer channel");
		assertEquals(5, page.results().size());
		assertEquals("ELECTRONIC INVOICE", page.results().get(0).text());
		assertEquals("Invoice No: 25317000001234567890", page.results().get(1).text());
	}

	@Test
	void runMultiPageTextPdf() throws IOException {
		List<PdfPageResult> pages = support.run(multiPageTextPdf(2));

		assertEquals(2, pages.size());
		assertEquals(0, pages.get(0).pageIndex());
		assertEquals("PAGE 1 OF 2 SAMPLE ELECTRONIC INVOICE CONTENT", pages.get(0).results().get(0).text());
		assertEquals(1, pages.get(1).pageIndex());
		assertEquals("PAGE 2 OF 2 SAMPLE ELECTRONIC INVOICE CONTENT", pages.get(1).results().get(0).text());
	}

	@Test
	void runImageOnlyPdfWithoutEngineFails() throws IOException {
		// 全图 PDF 无文本层 → 需要 OCR 通道 → engine 为 null 应明确报错
		IllegalStateException ex = assertThrows(IllegalStateException.class,
			() -> support.run(imageOnlyPdf()));
		assertTrue(ex.getMessage().contains("PPOcrV6Engine is null"));
	}

	@Test
	void forceOcrRequiresEngine() throws IOException {
		PdfOcrSupport forced = new PdfOcrSupport(null,
			PdfOcrConfig.builder().forceOcr(true).build());
		IllegalStateException ex = assertThrows(IllegalStateException.class,
			() -> forced.run(electronicInvoiceStylePdf()));
		assertTrue(ex.getMessage().contains("PPOcrV6Engine is null"));
	}

	@Test
	void runInputStreamEntry() throws IOException {
		List<PdfPageResult> pages = support.run(new ByteArrayInputStream(electronicInvoiceStylePdf()));
		assertEquals(1, pages.size());
		assertFalse(pages.get(0).viaOcr());
	}

	@Test
	void constructorRejectsNullConfig() {
		assertThrows(IllegalArgumentException.class, () -> new PdfOcrSupport(null, null));
	}
}
