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
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * PDF 文本层坐标抽取器：把页面内容流的字符绘制指令还原成「文本行 + 坐标矩形」。
 *
 * <p>与 OCR 的关键差异：OCR 是"光栅化 → 模型识别"，字符本身可能识别错
 * （如 淮→准、I→1）；本类直接读取 PDF 内嵌文本，字符 <strong>100% 无损</strong>，
 * 坐标来自绘制矩阵而非视觉检测，无 OCR 框合并/断裂问题。
 *
 * <p>产出为 {@link PPOcrV6Result}（score 恒为 1.0），与 {@code PPOcrV6Engine.run}
 * 的返回元素同构——下游结构化解析层（LabelMatcher / InvoiceTableParser 等）
 * 无需任何适配即可复用；针对 OCR 噪声的容错逻辑（残缺标签模糊匹配、合并框
 * 处理等）在文本层输入下自然退化为不触发。
 *
 * <h3>坐标语义</h3>
 * 基于 {@link TextPosition#getXDirAdj()} / {@link TextPosition#getYDirAdj()}：
 * 页面<strong>视觉坐标系</strong>（左上原点，y 向下增长），与 OpenCV 图像坐标系
 * 一致，无需翻转。行粒度对齐 OCR det 框语义：同一基线上间距超过 2 倍字高的
 * 文本块（如票面的"发票号码: xxx"与"开票日期: xxx"）拆分为独立结果，
 * 与 DB 检测按视觉连通域切框的行为一致，保证下游结构化解析零适配。
 *
 * <h3>适用边界</h3>
 * 假定横排文本且页面无 {@code /Rotate} 旋转（发票 / 票据 / 报表场景全覆盖）。
 * 竖排或旋转版式文本层坐标不可信，应通过
 * {@link PdfOcrConfig#isForceOcr()} 强制走渲染 + OCR 通道。
 *
 * <p>无状态，可单例共享。
 */
public class PdfTextExtractor {

	/**
	 * 行聚类最小容差（pt）：跨字号兜底，避免极小字号文本行被拆散。
	 */
	private static final float MIN_LINE_TOLERANCE = 2.0f;
	/**
	 * 行内拆分阈值（× 字高）：chunk 间水平间隙超过该倍数时视为视觉独立的
	 * 文本块（与 OCR det 检测按连通域切框的行为对齐），拆分为多条结果。
	 */
	private static final float MAX_WORD_GAP_RATIO = 2.0f;
	/**
	 * 下伸部（descender）高度比例：baseline 之下按字高的 25% 估计行底，
	 * 容纳 g/y/p 等下伸字形，行矩形略高于真实字形无碍结构化解析。
	 */
	private static final float DESCENDER_RATIO = 0.25f;
	/**
	 * 文本层结果的固定置信度：字符直接来自内嵌文本，非模型识别。
	 */
	private static final float EXACT_SCORE = 1.0f;

	/**
	 * 抽取指定页的文本行，按阅读顺序（自上而下、行内从左到右）返回。
	 *
	 * @param doc       PDF 文档
	 * @param pageIndex 页码，从 0 开始
	 * @return 文本行列表（与 OCR 结果同构）；无文本层时返回空列表
	 * @throws IOException               读取页面内容流失败
	 * @throws IndexOutOfBoundsException 页码越界
	 */
	public List<PPOcrV6Result> extract(PDDocument doc, int pageIndex) throws IOException {
		if (doc == null) {
			throw new IllegalArgumentException("doc must not be null");
		}
		if (pageIndex < 0 || pageIndex >= doc.getNumberOfPages()) {
			throw new IndexOutOfBoundsException("pageIndex " + pageIndex + " out of range [0, " + doc.getNumberOfPages() + ")");
		}
		LineCollector collector = new LineCollector(pageIndex);
		collector.getText(doc);
		return collector.buildLines();
	}

	/**
	 * 统计文本层质量：非空白字符总数 + 可读字符数。
	 *
	 * <p>判定规则见 {@link PdfTextQuality#isReadableChar(char)}；
	 * 配合 {@link PdfTextQuality#usable(int, double)} 做双通道分流。
	 *
	 * @param results 抽取结果
	 * @return 质量评分
	 */
	public PdfTextQuality quality(List<PPOcrV6Result> results) {
		int totalChars = 0;
		int readableChars = 0;
		if (results != null) {
			for (PPOcrV6Result result : results) {
				String text = result.text();
				for (int i = 0; i < text.length(); i++) {
					char c = text.charAt(i);
					if (Character.isWhitespace(c)) {
						continue;
					}
					totalChars++;
					if (PdfTextQuality.isReadableChar(c)) {
						readableChars++;
					}
				}
			}
		}
		return new PdfTextQuality(totalChars, readableChars);
	}

	/**
	 * 单页字符收集器：PDFTextStripper 逐字符块（word chunk）回调，
	 * 聚成视觉行后转 PPOcrV6Result。
	 */
	private static final class LineCollector extends PDFTextStripper {

		private final List<Chunk> chunks = new ArrayList<>();

		LineCollector(int pageIndexZeroBased) throws IOException {
			setSortByPosition(true);
			setStartPage(pageIndexZeroBased + 1);
			setEndPage(pageIndexZeroBased + 1);
		}

		@Override
		protected void writeString(String text, List<TextPosition> textPositions) {
			if (text == null || textPositions == null || textPositions.isEmpty()) {
				return;
			}
			String trimmed = text.trim();
			if (trimmed.isEmpty()) {
				return;
			}
			// 同一 chunk 内假定同 baseline（横排正常版式成立）；取首个字符位代表
			TextPosition first = textPositions.get(0);
			float minX = Float.MAX_VALUE;
			float maxX = -Float.MAX_VALUE;
			for (TextPosition tp : textPositions) {
				float x0 = tp.getXDirAdj();
				float x1 = x0 + tp.getWidthDirAdj();
				minX = Math.min(minX, x0);
				maxX = Math.max(maxX, x1);
			}
			float height = Math.max(first.getHeightDir(), 1.0f);
			chunks.add(new Chunk(trimmed, minX, maxX, first.getYDirAdj(), height));
		}

		/**
		 * 基线聚类成行：baseline 差 &le; max(2pt, 0.5 × 字高) 归入同一行。
		 *
		 * <p>同行字符 baseline 相同（正常排版）；不同字号混排（如标签小字 +
		 * 值大字）时以两者较大字高的一半为容差。
		 */
		List<PPOcrV6Result> buildLines() {
			if (chunks.isEmpty()) {
				return new ArrayList<>();
			}
			chunks.sort(Comparator.comparingDouble(c -> c.baselineY));
			List<TextLine> lines = new ArrayList<>();
			for (Chunk chunk : chunks) {
				TextLine last = lines.isEmpty() ? null : lines.get(lines.size() - 1);
				if (last != null && belongsTo(last, chunk)) {
					last.add(chunk);
				} else {
					lines.add(new TextLine(chunk));
				}
			}
			List<PPOcrV6Result> results = new ArrayList<>();
			for (TextLine line : lines) {
				line.collectResults(results);
			}
			return results;
		}

		private static boolean belongsTo(TextLine line, Chunk chunk) {
			float tolerance = Math.max(MIN_LINE_TOLERANCE, 0.5f * Math.max(line.maxHeight, chunk.height));
			return Math.abs(chunk.baselineY - line.baselineY) <= tolerance;
		}
	}

	/**
	 * 字符块：一次 writeString 回调的文本片段 + 包围盒。
	 */
	private static final class Chunk {

		final String text;
		final float minX;
		final float maxX;
		final float baselineY;
		final float height;

		Chunk(String text, float minX, float maxX, float baselineY, float height) {
			this.text = text;
			this.minX = minX;
			this.maxX = maxX;
			this.baselineY = baselineY;
			this.height = height;
		}
	}

	/**
	 * 视觉行：baseline 相近的 chunk 集合，输出为一条 PPOcrV6Result。
	 */
	private static final class TextLine {

		private final List<Chunk> chunks = new ArrayList<>();
		private final float baselineY;
		private float maxHeight;

		TextLine(Chunk first) {
			this.baselineY = first.baselineY;
			add(first);
		}

		void add(Chunk chunk) {
			chunks.add(chunk);
			maxHeight = Math.max(maxHeight, chunk.height);
		}

		/**
		 * 行内按 x 升序扫描：间隙超过 {@link #MAX_WORD_GAP_RATIO} × 字高的相邻
		 * chunk 拆为独立结果（对齐 OCR det 切框语义），其余直接拼接（不插空格，
		 * 与 OCR 行文本行为一致）。每段输出一条 PPOcrV6Result，box 取该段包围矩形。
		 */
		void collectResults(List<PPOcrV6Result> out) {
			chunks.sort(Comparator.comparingDouble(c -> c.minX));
			List<Chunk> segment = new ArrayList<>();
			for (Chunk chunk : chunks) {
				if (!segment.isEmpty() && isFarGap(segment.get(segment.size() - 1), chunk)) {
					addSegment(out, segment);
					segment = new ArrayList<>();
				}
				segment.add(chunk);
			}
			addSegment(out, segment);
		}

		private static boolean isFarGap(Chunk current, Chunk next) {
			return (next.minX - current.maxX) > MAX_WORD_GAP_RATIO * Math.max(current.height, next.height);
		}

		private void addSegment(List<PPOcrV6Result> out, List<Chunk> segment) {
			if (segment.isEmpty()) {
				return;
			}
			StringBuilder sb = new StringBuilder();
			float top = Float.MAX_VALUE;
			float bottom = -Float.MAX_VALUE;
			float minX = Float.MAX_VALUE;
			float maxX = -Float.MAX_VALUE;
			for (Chunk c : segment) {
				sb.append(c.text);
				top = Math.min(top, c.baselineY - c.height);
				bottom = Math.max(bottom, c.baselineY + DESCENDER_RATIO * c.height);
				minX = Math.min(minX, c.minX);
				maxX = Math.max(maxX, c.maxX);
			}
			String text = sb.toString().trim();
			if (text.isEmpty()) {
				return;
			}
			int[][] box = {
				{Math.round(minX), Math.round(top)},
				{Math.round(maxX), Math.round(top)},
				{Math.round(maxX), Math.round(bottom)},
				{Math.round(minX), Math.round(bottom)}
			};
			out.add(new PPOcrV6Result(text, EXACT_SCORE, box));
		}
	}
}
