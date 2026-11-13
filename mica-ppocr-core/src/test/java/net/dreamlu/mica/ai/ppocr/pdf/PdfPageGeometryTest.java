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

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link PdfPageGeometry} 单元测试：point → 像素的映射与页面旋转。
 *
 * <p>纯数学，不依赖 PDFBox / OpenCV。
 */
class PdfPageGeometryTest {

	/**
	 * 页面缩放系数：200 DPI 下 1 point = 200/72 像素。
	 */
	private static final float SCALE_200 = 200f / 72f;

	@Test
	void toPixelBox_scalesPointsByDpi() {
		PdfPageGeometry geometry = PdfPageGeometry.of(595.28f, 841.89f, 0, 200);

		// (100, 135) - (139, 144) point → ×(200/72)
		int[][] box = geometry.toPixelBox(new int[][] {
			{100, 135}, {139, 135}, {139, 144}, {100, 144}
		});

		assertEquals(Math.round(100 * SCALE_200), box[0][0]);
		assertEquals(Math.round(135 * SCALE_200), box[0][1]);
		assertEquals(Math.round(139 * SCALE_200), box[1][0]);
		assertEquals(Math.round(144 * SCALE_200), box[2][1]);
	}

	@Test
	void toPixelBox_rotation90_swapsAxes() {
		PdfPageGeometry geometry = PdfPageGeometry.of(595.28f, 841.89f, 90, 200);

		int[][] box = geometry.toPixelBox(new int[][] {
			{100, 135}, {139, 135}, {139, 144}, {100, 144}
		});

		// 旋转 90°：(x, y) → (cropH - y, x)
		assertEquals(Math.round((841.89f - 144) * SCALE_200), box[0][0]);
		assertEquals(Math.round(100 * SCALE_200), box[0][1]);
		assertEquals(Math.round((841.89f - 135) * SCALE_200), box[1][0]);
		assertEquals(Math.round(139 * SCALE_200), box[2][1]);
	}

	@Test
	void toPixelBox_rotation180_mirrorsBothAxes() {
		PdfPageGeometry geometry = PdfPageGeometry.of(595.28f, 841.89f, 180, 200);

		int[][] box = geometry.toPixelBox(new int[][] {
			{100, 135}, {139, 135}, {139, 144}, {100, 144}
		});

		// 旋转 180°：(x, y) → (cropW - x, cropH - y)
		assertEquals(Math.round((595.28f - 139) * SCALE_200), box[0][0]);
		assertEquals(Math.round((841.89f - 144) * SCALE_200), box[0][1]);
		assertEquals(Math.round((595.28f - 100) * SCALE_200), box[1][0]);
		assertEquals(Math.round((841.89f - 135) * SCALE_200), box[2][1]);
	}

	@Test
	void toPixelBox_rotation270_swapsAxesBack() {
		PdfPageGeometry geometry = PdfPageGeometry.of(595.28f, 841.89f, 270, 200);

		int[][] box = geometry.toPixelBox(new int[][] {
			{100, 135}, {139, 135}, {139, 144}, {100, 144}
		});

		// 旋转 270°：(x, y) → (y, cropW - x)
		assertEquals(Math.round(135 * SCALE_200), box[0][0]);
		assertEquals(Math.round((595.28f - 139) * SCALE_200), box[0][1]);
		assertEquals(Math.round(144 * SCALE_200), box[1][0]);
		assertEquals(Math.round((595.28f - 100) * SCALE_200), box[2][1]);
	}

	@Test
	void renderedSize_swapsOnQuarterTurn() {
		PdfPageGeometry plain = PdfPageGeometry.of(595.28f, 841.89f, 0, 200);
		assertEquals(1653, plain.widthPx());
		assertEquals(2338, plain.heightPx());

		PdfPageGeometry quarter = PdfPageGeometry.of(595.28f, 841.89f, 90, 200);
		assertEquals(2338, quarter.widthPx());
		assertEquals(1653, quarter.heightPx());
	}

	@Test
	void unsupportedRotation_treatedAsZero() {
		// 非 90 倍数的 /Rotate 非法但可能存在；PDFBox 按不旋转渲染，此处保持一致
		PdfPageGeometry geometry = PdfPageGeometry.of(595.28f, 841.89f, 45, 200);

		assertEquals(0, geometry.rotation());
		int[][] box = geometry.toPixelBox(new int[][] {
			{100, 135}, {139, 135}, {139, 144}, {100, 144}
		});
		assertEquals(Math.round(100 * SCALE_200), box[0][0]);
		assertEquals(Math.round(135 * SCALE_200), box[0][1]);
	}

	@Test
	void negativeRotation_normalized() {
		assertEquals(270, PdfPageGeometry.of(595.28f, 841.89f, -90, 200).rotation());
		assertEquals(90, PdfPageGeometry.of(595.28f, 841.89f, 450, 200).rotation());
	}
}
