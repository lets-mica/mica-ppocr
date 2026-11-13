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

import lombok.Getter;
import lombok.experimental.Accessors;

/**
 * PDF 页面几何：把文本层的 <b>point 坐标</b> 映射到 <b>渲染图像素坐标</b>。
 *
 * <p><b>为什么需要它</b>：PDF 双通道的两条腿产出的坐标原本不在同一个坐标系里 ——
 * 文本层走 {@code TextPosition.getXDirAdj/getYDirAdj}，单位是 PDF 的 point（1/72 英寸），
 * 且<b>已经按裁剪框（crop box）归一</b>（左上原点、y 向下）；渲染通道走 DB 检测，
 * 单位是渲染位图的像素。两者混在同一个结果列表里（同一份 PDF 里既有电子页又有扫描页）
 * 就会得到两套坐标系的框，下游拿框回填位置时会整体错位。
 *
 * <p><b>映射关系</b>：文本层坐标 (x, y) 是裁剪框内的 point 坐标，
 * 而渲染图是「裁剪框按 {@code renderDpi} 缩放、并按页面 {@code /Rotate} 旋转后」的位图，
 * 因此映射 = <b>旋转 → 缩放</b>：
 * <pre>
 *   /Rotate = 0   → (x, y)                 显示尺寸 = cropW × cropH
 *   /Rotate = 90 →  (cropH - y, x)   显示尺寸 = cropH × cropW
 *   /Rotate = 180 → (cropW - x, cropH - y)  显示尺寸 = cropW × cropH
 *   /Rotate = 270 → (y, cropW - x)   显示尺寸 = cropH × cropW
 * </pre>
 * 之后再统一乘以 {@code renderDpi / 72} 换成像素。
 *
 * <p><b>关于 {@code /Rotate}</b>：PDFBox 的 {@code getXDirAdj/getYDirAdj} 是
 * 「文本方向调整」坐标，<b>不含页面旋转</b>（页面旋转调整的是 {@code getX/getY}），
 * 所以旋转必须由本类显式补上 —— 否则旋转页的文本层框会与渲染图整体错位。
 *
 * <p>本类<b>不引用任何 PDFBox 类型</b>：几何参数以纯数值传入，
 * 由 {@link PdfRuntime} 从 {@code PDPage} 取出后组装。
 *
 * @author L.cm
 */
@Getter
@Accessors(fluent = true)
public final class PdfPageGeometry {

	/**
	 * PDF point / 英寸 的换算基准（PDF 用户空间单位）。
	 */
	private static final float POINTS_PER_INCH = 72f;

	/**
	 * 归一化后的页面旋转角：0 / 90 / 180 / 270（其它值按 0 处理，与 PDFBox 渲染行为一致）。
	 */
	private final int rotation;

	/**
	 * 裁剪框宽（point）。
	 */
	private final float cropWidth;

	/**
	 * 裁剪框高（point）。
	 */
	private final float cropHeight;

	/**
	 * point → 像素的缩放系数（{@code renderDpi / 72}）。
	 */
	private final float scale;

	/**
	 * 构造页面几何。
	 *
	 * @param rotation   页面旋转角（0 / 90 / 180 / 270，其它值按 0 处理）
	 * @param cropWidth  裁剪框宽（point）
	 * @param cropHeight 裁剪框高（point）
	 * @param scale      point → 像素缩放系数
	 */
	private PdfPageGeometry(int rotation, float cropWidth, float cropHeight, float scale) {
		this.rotation = rotation;
		this.cropWidth = cropWidth;
		this.cropHeight = cropHeight;
		this.scale = scale;
	}

	/**
	 * 按页面几何参数组装。
	 *
	 * @param cropWidth  裁剪框宽（point）
	 * @param cropHeight 裁剪框高（point）
	 * @param rotation   页面旋转角（0 / 90 / 180 / 270，其它值按 0 处理）
	 * @param renderDpi  渲染 DPI（决定 point → 像素的缩放）
	 * @return 页面几何
	 */
	public static PdfPageGeometry of(float cropWidth, float cropHeight, int rotation, int renderDpi) {
		return new PdfPageGeometry(normalizeRotation(rotation), cropWidth, cropHeight, renderDpi / POINTS_PER_INCH);
	}

	/**
	 * 归一化旋转角：只认 0 / 90 / 180 / 270，其余按 0 处理。
	 *
	 * <p>与 PDFBox 渲染器保持一致 —— 它对非 90 倍数的 {@code /Rotate} 同样按不旋转渲染，
	 * 两边不一致会让文本层框与渲染图错位。
	 *
	 * @param rotation 原始旋转角
	 * @return 归一化后的旋转角
	 */
	private static int normalizeRotation(int rotation) {
		int normalized = rotation % 360;
		if (normalized < 0) {
			normalized += 360;
		}
		if (normalized == 90 || normalized == 180 || normalized == 270) {
			return normalized;
		}
		return 0;
	}

	/**
	 * 渲染图宽（像素）。
	 *
	 * <p>与 PDFBox 的 {@code PDFRenderer} 一致：先按 {@code floor(cropW × scale)} 取整，
	 * {@code /Rotate} 为 90 / 270 时宽高互换。
	 *
	 * @return 渲染图宽（像素）
	 */
	public int widthPx() {
		return isSwapped() ? heightPxRaw() : widthPxRaw();
	}

	/**
	 * 渲染图高（像素）。
	 *
	 * @return 渲染图高（像素）
	 */
	public int heightPx() {
		return isSwapped() ? widthPxRaw() : heightPxRaw();
	}

	/**
	 * 渲染图宽是否与高互换（{@code /Rotate} 为 90 / 270）。
	 *
	 * @return true 表示宽高互换
	 */
	private boolean isSwapped() {
		return rotation == 90 || rotation == 270;
	}

	/**
	 * 未旋转时的渲染图宽（像素）。
	 *
	 * @return 宽（像素）
	 */
	private int widthPxRaw() {
		return (int) Math.max(Math.floor(cropWidth * scale), 1f);
	}

	/**
	 * 未旋转时的渲染图高（像素）。
	 *
	 * @return 高（像素）
	 */
	private int heightPxRaw() {
		return (int) Math.max(Math.floor(cropHeight * scale), 1f);
	}

	/**
	 * 把文本层的 point 框映射为渲染图像素框。
	 *
	 * <p>输出为<b>轴对齐</b>矩形（四顶点顺序：左上、右上、右下、左下）——
	 * 与渲染通道 DB 检测产出的框语义一致（DB 对竖排文本行同样给出轴对齐外接框）。
	 *
	 * @param ptBox 文本层 point 框（四顶点）
	 * @return 像素坐标下的轴对齐框；入参为 null 时返回 null
	 */
	public int[][] toPixelBox(int[][] ptBox) {
		if (ptBox == null || ptBox.length == 0) {
			return ptBox;
		}
		float minX = Float.MAX_VALUE;
		float minY = Float.MAX_VALUE;
		float maxX = -Float.MAX_VALUE;
		float maxY = -Float.MAX_VALUE;
		for (int[] vertex : ptBox) {
			if (vertex == null || vertex.length < 2) {
				continue;
			}
			float rotatedX;
			float rotatedY;
			if (rotation == 90) {
				rotatedX = cropHeight - vertex[1];
				rotatedY = vertex[0];
			} else if (rotation == 180) {
				rotatedX = cropWidth - vertex[0];
				rotatedY = cropHeight - vertex[1];
			} else if (rotation == 270) {
				rotatedX = vertex[1];
				rotatedY = cropWidth - vertex[0];
			} else {
				rotatedX = vertex[0];
				rotatedY = vertex[1];
			}
			float pixelX = rotatedX * scale;
			float pixelY = rotatedY * scale;
			minX = Math.min(minX, pixelX);
			minY = Math.min(minY, pixelY);
			maxX = Math.max(maxX, pixelX);
			maxY = Math.max(maxY, pixelY);
		}
		if (minX > maxX || minY > maxY) {
			return ptBox;
		}
		int left = Math.round(minX);
		int top = Math.round(minY);
		int right = Math.round(maxX);
		int bottom = Math.round(maxY);
		return new int[][] {
			{left, top},
			{right, top},
			{right, bottom},
			{left, bottom}
		};
	}
}
