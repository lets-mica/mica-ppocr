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

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 测试用合成 PDF 工厂：内存生成文字型 / 全图型 / 多页 PDF，无外部资源依赖。
 *
 * <p>文本内容使用标准 14 字体 Helvetica（ASCII），不嵌入 CJK 字体文件——
 * 文本层抽取与行聚类的核心逻辑与字符集无关，中文数电票真实样本验证
 * 放在集成阶段。
 */
final class TestPdfFactory {

	private TestPdfFactory() {
	}

	/**
	 * 单条文本绘制规格（PDF 坐标系：左下原点，baselineY 向上）。
	 */
	static final class TextSpec {

		final String text;
		final float x;
		final float baselineY;
		final float fontSize;

		private TextSpec(String text, float x, float baselineY, float fontSize) {
			this.text = text;
			this.x = x;
			this.baselineY = baselineY;
			this.fontSize = fontSize;
		}
	}

	static TextSpec spec(String text, float x, float baselineY, float fontSize) {
		return new TextSpec(text, x, baselineY, fontSize);
	}

	/**
	 * 数电票风格单页文字型 PDF（模拟票面：标题 / 发票号码 / 开票日期 / 金额 / 税额）。
	 *
	 * <p>"Invoice No"（x=60）与 "Issue Date"（x=380）同一 baseline，二者间距
	 * 远超 2 倍字高——用于验证行内大间距拆分（对齐 OCR det 切框语义）。
	 */
	static byte[] electronicInvoiceStylePdf() throws IOException {
		return textPdf(
			spec("ELECTRONIC INVOICE", 60, 780, 14),
			spec("Invoice No: 25317000001234567890", 60, 750, 11),
			spec("Issue Date: 2026-09-04", 380, 750, 11),
			spec("Amount: 12345.67", 60, 720, 11),
			spec("Tax: 678.90", 60, 690, 11)
		);
	}

	/**
	 * 多页文字型 PDF，每页标注页码文本（长度超过默认 minTextChars=20 阈值）。
	 */
	static byte[] multiPageTextPdf(int pageCount) throws IOException {
		List<TextSpec> specs = new ArrayList<>();
		for (int i = 0; i < pageCount; i++) {
			specs.add(spec("PAGE " + (i + 1) + " OF " + pageCount + " SAMPLE ELECTRONIC INVOICE CONTENT", 60, 750, 12));
		}
		return textPdf(pageCount, specs);
	}

	/**
	 * 生成文字型 PDF：所有文本写在同一页。
	 */
	static byte[] textPdf(TextSpec... specs) throws IOException {
		return textPdf(1, Arrays.asList(specs));
	}

	/**
	 * 生成文字型 PDF：specs 按页顺序均分。
	 */
	private static byte[] textPdf(int pageCount, List<TextSpec> specs) throws IOException {
		int perPage = specs.size() / pageCount;
		try (PDDocument doc = new PDDocument()) {
			for (int p = 0; p < pageCount; p++) {
				PDPage page = new PDPage(PDRectangle.A4);
				doc.addPage(page);
				try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
					for (int i = p * perPage; i < (p + 1) * perPage; i++) {
						TextSpec s = specs.get(i);
						cs.beginText();
						// PDFBox 3.x：标准 14 字体常量已移除，改用 Standard14Fonts.FontName 枚举
						cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), s.fontSize);
						cs.newLineAtOffset(s.x, s.baselineY);
						cs.showText(s.text);
						cs.endText();
					}
				}
			}
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			doc.save(out);
			return out.toByteArray();
		}
	}

	/**
	 * 生成全图型 PDF（模拟扫描件）：整页仅一张位图，无任何文本层。
	 */
	static byte[] imageOnlyPdf() throws IOException {
		BufferedImage image = new BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		try {
			g.setColor(Color.WHITE);
			g.fillRect(0, 0, 400, 200);
			g.setColor(Color.BLACK);
			g.drawLine(10, 10, 390, 190);
			g.drawLine(390, 10, 10, 190);
			g.drawRect(20, 20, 360, 160);
		} finally {
			g.dispose();
		}
		try (PDDocument doc = new PDDocument()) {
			PDPage page = new PDPage(PDRectangle.A4);
			doc.addPage(page);
			PDImageXObject pdImage = LosslessFactory.createFromImage(doc, image);
			try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
				cs.drawImage(pdImage, 50, 600, 400, 200);
			}
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			doc.save(out);
			return out.toByteArray();
		}
	}
}
