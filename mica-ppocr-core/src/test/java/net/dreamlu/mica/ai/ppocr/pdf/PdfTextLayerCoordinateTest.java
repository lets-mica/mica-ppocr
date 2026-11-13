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
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PDF 双通道坐标一致性回归测试。
 *
 * <p><b>锁住什么</b>：文本层通道与渲染通道产出的框必须落在<b>同一个坐标系</b>
 * （渲染图像素），否则同一份 PDF 里「电子页 + 扫描页」混排时，
 * 下游拿框回填位置会整体错位。
 *
 * <p><b>怎么验</b>：文本层通道的框不是模型算出来的，没法自证；
 * 但渲染通道的框就是渲染位图上的像素 —— 于是把同一页渲染出来，
 * 找出页面上的<b>墨迹包围盒</b>（真值），要求文本层框（换算后）与它重合。
 * 页面 {@code /Rotate}、裁剪框偏移都会改变墨迹落点，因此一并覆盖。
 */
class PdfTextLayerCoordinateTest {

	/**
	 * 渲染 DPI：与 {@code PdfOcrConfig} 的默认值一致。
	 */
	private static final int DPI = 200;

	/**
	 * 页面文本：字符数须超过默认 {@code minTextChars=20}，否则该页会被判为「无文本层」
	 * 而走渲染 + OCR 通道，测的就不是文本层坐标了。
	 */
	private static final String SAMPLE_TEXT = "ELECTRONIC INVOICE SAMPLE";

	/**
	 * 框边与墨迹边的允许偏差（像素）。
	 *
	 * <p>文本层框取自字体度量（{@code getHeightDir}），与实际字形墨迹本就略有出入
	 * （字高估算偏小 + 下伸部预留），实测偏差在 6 px 内；
	 * 而映射写错时偏差是数百 px 量级 —— 10 px 的容差足以区分二者。
	 */
	private static final int TOLERANCE = 10;

	/**
	 * 混合页测试会走到渲染通道（{@code BufferedImageUtils.toBgrMat}），
	 * 引擎的构造期自动加载管不到这里，需自行加载 native 库。
	 */
	@BeforeAll
	static void loadOpenCv() {
		nu.pattern.OpenCV.loadLocally();
	}

	@Test
	void textLayerBox_landsOnRenderedText_plainPage() throws IOException {
		byte[] pdf = TestPdfFactory.textPdfWithGeometry(0, null, TestPdfFactory.spec(SAMPLE_TEXT, 100, 700, 12));
		assertBoxMatchesInk(pdf, 0);
	}

	@Test
	void textLayerBox_landsOnRenderedText_rotated90() throws IOException {
		byte[] pdf = TestPdfFactory.textPdfWithGeometry(90, null, TestPdfFactory.spec(SAMPLE_TEXT, 100, 700, 12));
		assertBoxMatchesInk(pdf, 0);
	}

	@Test
	void textLayerBox_landsOnRenderedText_rotated180() throws IOException {
		byte[] pdf = TestPdfFactory.textPdfWithGeometry(180, null, TestPdfFactory.spec(SAMPLE_TEXT, 100, 700, 12));
		assertBoxMatchesInk(pdf, 0);
	}

	@Test
	void textLayerBox_landsOnRenderedText_rotated270() throws IOException {
		byte[] pdf = TestPdfFactory.textPdfWithGeometry(270, null, TestPdfFactory.spec(SAMPLE_TEXT, 100, 700, 12));
		assertBoxMatchesInk(pdf, 0);
	}

	@Test
	void textLayerBox_landsOnRenderedText_cropBoxOffset() throws IOException {
		// 裁剪框左下角 (20, 30)，即文本层坐标以裁剪框左上角为原点
		byte[] pdf = TestPdfFactory.textPdfWithGeometry(0, new float[]{20, 30, 520, 730},
			TestPdfFactory.spec(SAMPLE_TEXT, 100, 700, 12));
		assertBoxMatchesInk(pdf, 0);
	}

	@Test
	void mixedChannels_shareSameCoordinateSpace() throws IOException {
		byte[] pdf = TestPdfFactory.mixedTextAndImagePdf();
		AtomicBoolean bridgeCalled = new AtomicBoolean(false);
		Canvas canvas = new Canvas();

		List<PdfPageResult> pages = new PdfRuntime().run(pdf, PdfOcrConfig.builder().renderDpi(DPI).build(), null,
			(mat, detParams) -> {
				bridgeCalled.set(true);
				canvas.cols = mat.cols();
				canvas.rows = mat.rows();
				return Collections.emptyList();
			});

		assertEquals(2, pages.size());
		// 第 1 页走文本层，第 2 页无文本层走渲染 + OCR
		assertFalse(pages.get(0).viaOcr(), "第 1 页应走文本层通道");
		assertTrue(pages.get(1).viaOcr(), "第 2 页应走渲染 + OCR 通道");
		assertTrue(bridgeCalled.get(), "渲染通道应被调用");

		// 渲染通道的画布 = A4 @ 200dpi
		assertEquals(1653, canvas.cols);
		assertEquals(2338, canvas.rows);

		// 文本层页的框必须落在这张画布内 —— 与 OCR 页同一坐标系
		int[] box = bounds(pages.get(0).results().get(0).box());
		assertTrue(box[0] >= 0 && box[1] >= 0, "文本层框不应落在画布外: " + box[0] + "," + box[1]);
		assertTrue(box[2] <= canvas.cols && box[3] <= canvas.rows,
			"文本层框超出渲染画布: " + box[2] + "," + box[3] + " vs " + canvas.cols + "x" + canvas.rows);

		// 且与该页渲染图上的墨迹重合
		assertInkMatchesBox(pages, pdf, 0);
	}

	/**
	 * 跑一遍双通道，断言文本层页的框与该页渲染图上的墨迹包围盒重合。
	 *
	 * @param pdf       PDF 字节
	 * @param pageIndex 页索引
	 * @throws IOException PDF 解析或渲染失败
	 */
	private static void assertBoxMatchesInk(byte[] pdf, int pageIndex) throws IOException {
		List<PdfPageResult> pages = new PdfRuntime().run(pdf, PdfOcrConfig.builder().renderDpi(DPI).build(), null,
			(mat, detParams) -> Collections.emptyList());
		assertFalse(pages.get(pageIndex).viaOcr(), "该页应走文本层通道");
		assertInkMatchesBox(pages, pdf, pageIndex);
	}

	/**
	 * 断言文本层通道产出的框与该页渲染图上的墨迹包围盒重合。
	 *
	 * @param pages     双通道结果
	 * @param pdf       PDF 字节
	 * @param pageIndex 页索引
	 * @throws IOException PDF 解析或渲染失败
	 */
	private static void assertInkMatchesBox(List<PdfPageResult> pages, byte[] pdf, int pageIndex) throws IOException {
		assertEquals(1, pages.get(pageIndex).results().size());
		int[] box = bounds(pages.get(pageIndex).results().get(0).box());
		try (PDDocument doc = Loader.loadPDF(pdf)) {
			BufferedImage image = new PDFRenderer(doc).renderImageWithDPI(pageIndex, DPI, ImageType.RGB);
			int[] ink = inkBox(image);
			assertNotNull(ink, "渲染页上应有墨迹");
			assertClose("left", box[0], ink[0]);
			assertClose("top", box[1], ink[1]);
			assertClose("right", box[2], ink[2]);
			assertClose("bottom", box[3], ink[3]);
		}
	}

	/**
	 * 断言两个坐标在容差内相等。
	 *
	 * @param label 断言标签
	 * @param actual   实际值
	 * @param expected 期望值
	 */
	private static void assertClose(String label, int actual, int expected) {
		assertTrue(Math.abs(actual - expected) <= TOLERANCE,
			label + " 偏差过大: 框=" + actual + " 墨迹=" + expected + " 容差=" + TOLERANCE);
	}

	/**
	 * 取框的轴对齐外接：返回 {left, top, right, bottom}。
	 *
	 * @param box 四顶点框
	 * @return {left, top, right, bottom}
	 */
	private static int[] bounds(int[][] box) {
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;
		for (int[] vertex : box) {
			minX = Math.min(minX, vertex[0]);
			minY = Math.min(minY, vertex[1]);
			maxX = Math.max(maxX, vertex[0]);
			maxY = Math.max(maxY, vertex[1]);
		}
		return new int[]{minX, minY, maxX, maxY};
	}

	/**
	 * 扫描渲染图，取非白像素的包围盒（页面上的文字墨迹）。
	 *
	 * @param image 渲染图
	 * @return {left, top, right, bottom}；无墨迹时返回 null
	 */
	private static int[] inkBox(BufferedImage image) {
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;
		for (int y = 0; y < image.getHeight(); y++) {
			for (int x = 0; x < image.getWidth(); x++) {
				int rgb = image.getRGB(x, y);
				int sum = ((rgb >> 16) & 0xFF) + ((rgb >> 8) & 0xFF) + (rgb & 0xFF);
				if (sum < 400) {
					minX = Math.min(minX, x);
					minY = Math.min(minY, y);
					maxX = Math.max(maxX, x);
					maxY = Math.max(maxY, y);
				}
			}
		}
		return maxX == Integer.MIN_VALUE ? null : new int[]{minX, minY, maxX, maxY};
	}

	/**
	 * 简单可变容器（Java 8 无 record，且 lambda 捕获需 effectively final）。
	 */
	private static final class Canvas {

		private int cols;

		private int rows;
	}
}
