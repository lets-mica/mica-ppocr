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
import org.opencv.core.CvType;
import org.opencv.core.Mat;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;

/**
 * {@link BufferedImage} 与 OpenCV {@link Mat} 之间的转换工具。
 *
 * <p>Java 8 AWT 图像像素访问走 {@link java.awt.image.Raster} 的
 * {@link DataBufferByte#getData()} —— 在 {@link BufferedImage#TYPE_3BYTE_BGR}
 * 情形下数据天然按 BGR 字节序排列，可整块 {@link Mat#put(int, int, byte[])}
 * 直接拷贝，避免逐像素 {@link java.awt.image.Raster#getPixel(int, int, int[])}
 * 触发的多次 native call。
 *
 * <p>无状态工具，所有方法线程安全。
 */
@UtilityClass
public class BufferedImageUtils {

	/**
	 * BufferedImage → BGR Mat。
	 *
	 * <p>当源图非 TYPE_3BYTE_BGR（如 TYPE_INT_RGB 等）时，先用 {@link Graphics2D}
	 * 重绘到目标类型再整块 put，避免逐像素 getRGB。
	 *
	 * @param src 源图像（不可为 null）
	 * @return 新的 BGR Mat，由调用方负责 release
	 */
	public static Mat toBgrMat(BufferedImage src) {
		int width = src.getWidth();
		int height = src.getHeight();
		BufferedImage bgr = src;
		if (src.getType() != BufferedImage.TYPE_3BYTE_BGR) {
			bgr = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
			Graphics2D g = bgr.createGraphics();
			try {
				g.drawImage(src, 0, 0, null);
			} finally {
				g.dispose();
			}
		}
		byte[] data = ((DataBufferByte) bgr.getRaster().getDataBuffer()).getData();
		Mat mat = new Mat(height, width, CvType.CV_8UC3);
		mat.put(0, 0, data);
		return mat;
	}
}
