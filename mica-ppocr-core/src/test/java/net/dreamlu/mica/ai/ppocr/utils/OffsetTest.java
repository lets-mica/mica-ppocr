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

package net.dreamlu.mica.ai.ppocr.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Offset（多边形偏移）单元测试。
 */
class OffsetTest {

	@Test
	void area_square() {
		// 10x10 正方形
		float[][] square = {{0, 0}, {10, 0}, {10, 10}, {0, 10}};
		assertEquals(100.0, Offset.area(square), 0.01);
	}

	@Test
	void area_triangle() {
		// 直角三角形 3-4-5
		float[][] triangle = {{0, 0}, {3, 0}, {0, 4}};
		assertEquals(6.0, Offset.area(triangle), 0.01);
	}

	@Test
	void area_lessThan3Points() {
		assertEquals(0.0, Offset.area(new float[][]{{0, 0}, {1, 1}}), 0.01);
		assertEquals(0.0, Offset.area(null), 0.01);
	}

	@Test
	void perimeter_square() {
		float[][] square = {{0, 0}, {10, 0}, {10, 10}, {0, 10}};
		assertEquals(40.0, Offset.perimeter(square), 0.01);
	}

	@Test
	void perimeter_lessThan2Points() {
		assertEquals(0.0, Offset.perimeter(new float[][]{{0, 0}}), 0.01);
		assertEquals(0.0, Offset.perimeter(null), 0.01);
	}

	@Test
	void unclipDistance_square() {
		// 10x10 正方形, area=100, perimeter=40, ratio=1.5 → distance = 100*1.5/40 = 3.75
		float[][] square = {{0, 0}, {10, 0}, {10, 10}, {0, 10}};
		assertEquals(3.75, Offset.unclipDistance(square, 1.5), 0.01);
	}

	@Test
	void unclipDistance_zeroPerimeter() {
		float[][] degenerate = {{0, 0}, {0, 0}};
		assertEquals(0.0, Offset.unclipDistance(degenerate, 1.5), 0.01);
	}

	@Test
	void unclip_nullInput() {
		assertNull(Offset.unclip(null, 3.0));
	}

	@Test
	void unclip_lessThan3Points() {
		float[][] line = {{0, 0}, {1, 1}};
		float[][] result = Offset.unclip(line, 3.0);
		assertEquals(2, result.length);
	}

	@Test
	void unclip_squareExpands() {
		// 10x10 正方形外扩 2px 后应大致为 14x14
		float[][] square = {{5, 5}, {15, 5}, {15, 15}, {5, 15}};
		float[][] expanded = Offset.unclip(square, 2.0);
		// 扩展后应有 3 个或更多顶点（JTS 可能加入圆角分段）
		assertTrue(expanded.length >= 3, "扩展后应至少有3个顶点");
	}
}
