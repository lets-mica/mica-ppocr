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

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * {@link BoxUtil#orderMinAreaBoxPoints(float[][])} 的精度回归测试。
 *
 * <p>锁住的核心语义：<b>float 多边形进入 minAreaRect 前不得被整数化</b>。
 * 旧实现经 {@link MatOfPoint}（CV_32S）中转，坐标在进入 minAreaRect 之前就被截断，
 * 与 Python 参考实现（直接在 float 多边形上算 minAreaRect）产生系统性偏差。
 *
 * <p>本测试直接使用 {@code org.opencv.*}（不经过 {@code PPOcrV6Engine}），
 * 引擎的构造期自动加载管不到这里，需自行加载 native 库。
 */
class BoxUtilMinAreaRectTest {

	/**
	 * 坐标比较容差（float 精度）。
	 */
	private static final float EPS = 1e-4f;

	@BeforeAll
	static void loadOpenCv() {
		nu.pattern.OpenCV.loadLocally();
	}

	/**
	 * 带亚像素的小数多边形，计算结果必须保留小数 —— 不得被整数化。
	 */
	@Test
	void orderMinAreaBoxPoints_floatPolygon_keepsSubPixelPrecision() {
		float[][] poly = {
			{0.4f, 0.6f},
			{10.4f, 0.6f},
			{10.4f, 20.6f},
			{0.4f, 20.6f}
		};
		float[][] box = BoxUtil.orderMinAreaBoxPoints(poly).asFloatArray();
		// 顶点顺序为 [左上, 右上, 右下, 左下]
		assertEquals(0.4f, box[0][0], EPS, "左上 x 应保留小数");
		assertEquals(0.6f, box[0][1], EPS, "左上 y 应保留小数");
		assertEquals(10.4f, box[1][0], EPS, "右上 x 应保留小数");
		assertEquals(0.6f, box[1][1], EPS, "右上 y 应保留小数");
		assertEquals(10.4f, box[2][0], EPS, "右下 x 应保留小数");
		assertEquals(20.6f, box[2][1], EPS, "右下 y 应保留小数");
		assertEquals(0.4f, box[3][0], EPS, "左下 x 应保留小数");
		assertEquals(20.6f, box[3][1], EPS, "左下 y 应保留小数");
	}

	/**
	 * 与「整数截断」的结果必须不同 —— 证明截断确实被去掉了（而不是两条路径恰好一致）。
	 */
	@Test
	void orderMinAreaBoxPoints_floatPolygon_differsFromIntegerTruncated() {
		float[][] poly = {
			{0.4f, 0.6f},
			{10.4f, 0.6f},
			{10.4f, 20.6f},
			{0.4f, 20.6f}
		};
		float[][] viaFloat = BoxUtil.orderMinAreaBoxPoints(poly).asFloatArray();

		// 模拟旧实现：先经 MatOfPoint（CV_32S）整数化
		MatOfPoint truncated = new MatOfPoint(
			new Point(0, 0), new Point(10, 0), new Point(10, 20), new Point(0, 20));
		float[][] viaInt;
		try {
			viaInt = BoxUtil.orderMinAreaBoxPoints(truncated).asFloatArray();
		} finally {
			truncated.release();
		}

		assertNotEquals(viaInt[0][0], viaFloat[0][0], "float 路径不应与整数截断路径同值");
		assertNotEquals(viaInt[0][1], viaFloat[0][1], "float 路径不应与整数截断路径同值");
	}

	/**
	 * 整数值多边形：float 路径与整数轮廓路径必须逐位一致（det 输出的框走的就是这条）。
	 */
	@Test
	void orderMinAreaBoxPoints_integerPolygon_matchesIntContourPath() {
		float[][] poly = {
			{0f, 0f},
			{10f, 0f},
			{10f, 20f},
			{0f, 20f}
		};
		float[][] viaFloat = BoxUtil.orderMinAreaBoxPoints(poly).asFloatArray();

		MatOfPoint contour = new MatOfPoint(
			new Point(0, 0), new Point(10, 0), new Point(10, 20), new Point(0, 20));
		float[][] viaInt;
		try {
			viaInt = BoxUtil.orderMinAreaBoxPoints(contour).asFloatArray();
		} finally {
			contour.release();
		}

		for (int i = 0; i < 4; i++) {
			assertArrayEquals(viaInt[i], viaFloat[i], EPS, "顶点 " + i + " 应一致");
		}
	}

	/**
	 * 旋转多边形同样不得被整数化。
	 */
	@Test
	void orderMinAreaBoxPoints_rotatedFloatPolygon_keepsSubPixelPrecision() {
		// 一个绕中心旋转的小矩形，顶点带小数
		float[][] poly = {
			{5.37f, 2.84f},
			{7.63f, 1.02f},
			{8.41f, 3.28f},
			{6.15f, 5.10f}
		};
		float[][] box = BoxUtil.orderMinAreaBoxPoints(poly).asFloatArray();
		for (int i = 0; i < 4; i++) {
			// 至少有一个坐标带小数分量（不是整数）
			boolean hasFraction = (box[i][0] != Math.floor(box[i][0])) || (box[i][1] != Math.floor(box[i][1]));
			org.junit.jupiter.api.Assertions.assertTrue(hasFraction,
				"顶点 " + i + " 被整数化了：" + box[i][0] + "," + box[i][1]);
		}
	}
}
