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
 *
 * <p><b>本测试不需要加载 OpenCV 原生库</b>：{@link Offset#area(float[][])} /
 * {@link Offset#perimeter(float[][])} 与 {@link Offset#unclip} 均为纯 Java 实现
 * —— 整条链路不再触达 {@code org.opencv.*}，也不再有第三方几何库依赖。
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
	void unclip_zeroDistanceReturnsOriginal() {
		float[][] square = {{0, 0}, {10, 0}, {10, 10}, {0, 10}};
		float[][] result = Offset.unclip(square, 0.0);
		assertEquals(4, result.length);
		assertEquals(0f, result[0][0], 1e-6);
		assertEquals(10f, result[2][1], 1e-6);
	}

	@Test
	void unclip_squareExpands() {
		// 10x10 正方形外扩 2px 后应大致为 14x14
		float[][] square = {{5, 5}, {15, 5}, {15, 15}, {5, 15}};
		float[][] expanded = Offset.unclip(square, 2.0);
		// 扩展后应有 3 个或更多顶点（圆角离散会加入分段）
		assertTrue(expanded.length >= 3, "扩展后应至少有3个顶点");
	}

	@Test
	void unclip_squareProduces36Vertices() {
		// 正方形 4 个角，每个角 8 段圆弧 + 1 个顶点 = 4 × 9 = 36
		float[][] square = {{5, 5}, {15, 5}, {15, 15}, {5, 15}};
		float[][] expanded = Offset.unclip(square, 2.0);
		assertEquals(36, expanded.length);
	}

	/**
	 * 锁住圆弧段数的<b>四舍五入</b>口径。
	 *
	 * <p>等腰梯形的两个下角外角约 116.6°，对应段数 10.36 —— 四舍五入取 10、
	 * 上取整取 11，两者会让顶点数差 2（36 vs 38），进而让下游检测框产生 1~2px 抖动。
	 */
	@Test
	void unclip_trapezoidArcSegmentsUseRounding() {
		float[][] trapezoid = {{0, 0}, {20, 0}, {15, 10}, {5, 10}};
		assertEquals(36, Offset.unclip(trapezoid, 2.0).length);
		assertEquals(36, Offset.unclip(trapezoid, 1.0).length);
	}

	@Test
	void unclip_squareEdgeOffsetEqualsDistance() {
		// 各直边的偏移段端点应恰好落在原边外侧 d 处
		float[][] square = {{5, 5}, {15, 5}, {15, 15}, {5, 15}};
		float[][] expanded = Offset.unclip(square, 2.0);
		assertTrue(containsPoint(expanded, 5f, 3f), "上边左端点应偏移到 (5,3)");
		assertTrue(containsPoint(expanded, 15f, 3f), "上边右端点应偏移到 (15,3)");
		assertTrue(containsPoint(expanded, 17f, 5f), "右边端点应偏移到 (17,5)");
		assertTrue(containsPoint(expanded, 17f, 15f), "右边端点应偏移到 (17,15)");
		assertTrue(containsPoint(expanded, 15f, 17f), "下边端点应偏移到 (15,17)");
		assertTrue(containsPoint(expanded, 5f, 17f), "下边端点应偏移到 (5,17)");
		assertTrue(containsPoint(expanded, 3f, 15f), "左边端点应偏移到 (3,15)");
		assertTrue(containsPoint(expanded, 3f, 5f), "左边端点应偏移到 (3,5)");
	}

	@Test
	void unclip_nonConvexReturnsOriginal() {
		// 非凸多边形无法用「每边外法向平移」表达，应原样返回
		float[][] concave = {{0, 0}, {10, 0}, {5, 5}, {10, 10}, {0, 10}};
		float[][] result = Offset.unclip(concave, 2.0);
		assertSame(concave, result);
	}

	private static boolean containsPoint(float[][] polygon, float x, float y) {
		for (float[] p : polygon) {
			if (Math.abs(p[0] - x) < 1e-4 && Math.abs(p[1] - y) < 1e-4) {
				return true;
			}
		}
		return false;
	}
}
