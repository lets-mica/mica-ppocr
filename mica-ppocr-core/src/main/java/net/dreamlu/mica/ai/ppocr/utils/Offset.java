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

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.operation.buffer.BufferOp;
import org.locationtech.jts.operation.buffer.BufferParameters;

/**
 * 多边形偏移（unclip）。
 *
 * <p>对应 Python 端的 pyclipper PyclipperOffset：
 * <pre>
 *     po = pyclipper.PyclipperOffset()
 *     po.AddPath(box, pyclipper.JT_ROUND, pyclipper.ET_CLOSEDPOLYGON)
 *     expanded = np.asarray(po.Execute(distance))
 * </pre>
 *
 * <p>Java 实现基于 JTS（Java Topology Suite）的 BufferOp，使用
 * BufferParameters.JOIN_ROUND 圆角连接，与 pyclipper 行为接近。
 *
 * <p>注意：pyclipper 使用整数内部表示（带 scale 因子），而 JTS 使用 double 浮点。
 * 对典型 100~4000 像素范围的文本框，结果像素差异在 1px 以内。
 */
@Slf4j
@UtilityClass
public class Offset {
	private static final GeometryFactory GF = new GeometryFactory();

	/**
	 * 按指定距离对多边形进行外扩（unclip）。
	 *
	 * @param polygon  (N, 2) 顶点列表
	 * @param distance 扩展距离（像素），正数外扩，负数内缩
	 * @return 扩展后多边形顶点 (M, 2)；若扩展失败或结果为空，回退返回原多边形
	 */
	public static float[][] unclip(float[][] polygon, double distance) {
		if (polygon == null || polygon.length < 3) {
			return polygon;
		}

		Coordinate[] coords = new Coordinate[polygon.length + 1];
		for (int i = 0; i < polygon.length; i++) {
			coords[i] = new Coordinate(polygon[i][0], polygon[i][1]);
		}
		coords[polygon.length] = coords[0]; // 闭合

		Polygon poly;
		try {
			poly = GF.createPolygon(coords);
		} catch (Exception e) {
			log.debug("Offset: 无法构造多边形, 跳过: {}", e.getMessage());
			return polygon;
		}

		BufferParameters params = new BufferParameters();
		params.setJoinStyle(BufferParameters.JOIN_ROUND);

		Geometry result;
		try {
			result = BufferOp.bufferOp(poly, distance, params);
		} catch (Exception e) {
			log.debug("Offset: BufferOp 失败, 尝试简化输入: {}", e.getMessage());
			result = BufferOp.bufferOp(poly, distance);
		}

		if (result == null || result.isEmpty()) {
			log.debug("Offset: 结果为空, 保留原多边形");
			return polygon;
		}

		// 选择面积最大的多边形
		Polygon best = null;
		double bestArea = -1.0;
		if (result instanceof Polygon) {
			Polygon p = (Polygon) result;
			best = p;
			bestArea = p.getArea();
		} else if (result instanceof org.locationtech.jts.geom.MultiPolygon) {
			org.locationtech.jts.geom.MultiPolygon mp = (org.locationtech.jts.geom.MultiPolygon) result;
			for (int i = 0; i < mp.getNumGeometries(); i++) {
				Polygon g = (Polygon) mp.getGeometryN(i);
				double a = g.getArea();
				if (a > bestArea) {
					bestArea = a;
					best = g;
				}
			}
		} else {
			// 兜底：取凸包
			Geometry convex = result.convexHull();
			if (convex instanceof Polygon) {
				Polygon cp = (Polygon) convex;
				best = cp;
				bestArea = cp.getArea();
			} else {
				return polygon;
			}
		}

		if (best == null) {
			return polygon;
		}

		Coordinate[] exterior = best.getExteriorRing().getCoordinates();
		// 去除尾部的重复闭合点
		int n = exterior.length;
		if (n > 1 && exterior[0].equals(exterior[n - 1])) {
			n--;
		}
		float[][] out = new float[n][2];
		for (int i = 0; i < n; i++) {
			out[i][0] = (float) exterior[i].x;
			out[i][1] = (float) exterior[i].y;
		}
		return out;
	}

	/**
	 * 计算多边形面积（鞋带公式 / shoelace）。
	 *
	 * <p><b>与 OpenCV {@code Imgproc.contourArea} 逐位等价</b>：顶点坐标同为 float32，
	 * 累加顺序同为「上一点 × 当前点 − 当前点 × 上一点」的环绕遍历（{@code j = i-1,
	 * j<0 时回绕到 n-1}），最后乘 0.5 并取绝对值。
	 *
	 * <p>原实现走 OpenCV：每次调用都要构造 {@code MatOfPoint2f} 并跨 JNI 进入 native，
	 * 单次约 550ns，且<b>隐式依赖 native 库</b> —— 调用方测试看不见 {@code org.opencv}
	 * 却会在 {@code new MatOfPoint2f()} 处抛 {@code UnsatisfiedLinkError}。
	 * 改为纯 Java 后单次约 12ns，零分配、零 native 触达。
	 *
	 * @param polygon (N, 2) 顶点列表
	 * @return 多边形面积
	 */
	public static double area(float[][] polygon) {
		if (polygon == null || polygon.length < 3) {
			return 0.0;
		}
		double sum = 0.0;
		int n = polygon.length;
		for (int i = 0, j = n - 1; i < n; j = i++) {
			sum += (double) polygon[j][0] * polygon[i][1] - (double) polygon[i][0] * polygon[j][1];
		}
		return Math.abs(sum) * 0.5;
	}

	/**
	 * 计算闭合多边形周长（各边长度之和）。
	 *
	 * <p><b>与 OpenCV {@code Imgproc.arcLength(curve, true)} 逐位等价</b>：累加顺序同为
	 * 「先逐条相邻边（{@code i → i+1}），最后补一条闭合边（{@code n-1 → 0}）」，
	 * 边长用 {@link Math#hypot(double, double)}（对应 OpenCV 的 {@code std::hypot}）。
	 *
	 * <p>累加顺序与浮点加法顺序强相关 —— 换成"环绕"写法（首尾相接逐条累加）
	 * 会得到 1 ULP 级差异，故此处严格保持 OpenCV 的顺序。
	 *
	 * @param polygon (N, 2) 顶点列表
	 * @return 多边形周长
	 */
	public static double perimeter(float[][] polygon) {
		if (polygon == null || polygon.length < 2) {
			return 0.0;
		}
		int n = polygon.length;
		double sum = 0.0;
		for (int i = 0; i < n - 1; i++) {
			sum += Math.hypot(polygon[i + 1][0] - polygon[i][0], polygon[i + 1][1] - polygon[i][1]);
		}
		// 闭合边：末点 → 首点
		sum += Math.hypot(polygon[0][0] - polygon[n - 1][0], polygon[0][1] - polygon[n - 1][1]);
		return sum;
	}

	/**
	 * 计算 unclip 距离：area * unclipRatio / length，与 pyclipper 等价。
	 *
	 * @param polygon     (N, 2) 顶点列表
	 * @param unclipRatio unclip 比率
	 * @return unclip 距离
	 */
	public static double unclipDistance(float[][] polygon, double unclipRatio) {
		double a = area(polygon);
		double l = perimeter(polygon);
		if (l < 1e-6) {
			return 0.0;
		}
		return a * unclipRatio / l;
	}
}
