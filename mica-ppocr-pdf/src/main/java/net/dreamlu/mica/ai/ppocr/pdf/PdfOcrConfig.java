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

import lombok.Builder;
import lombok.Getter;

/**
 * PDF 双通道 OCR 配置。
 *
 * <p>控制文本层通道的可用性判定与渲染通道的输出精度：
 * <ul>
 *   <li>{@link #minTextChars} / {@link #minReadableRatio}：文本层可用性双阈值。
 *       页面抽取的非空白字符数达到 {@code minTextChars} 且可读字符占比达到
 *       {@code minReadableRatio} 时，判定文本层可用，走"坐标抽取"通道；
 *       否则降级"渲染 + OCR"通道（CID 字体缺 ToUnicode、Type3 字形、整页图片
 *       等"伪文字型 PDF"都会落在不可用分支）。</li>
 *   <li>{@link #renderDpi}：渲染通道的输出 DPI。扫描件内嵌位图有原始分辨率，
 *       超过原图信息量的重采样只会放大噪声，发票/证件类 200~300 为宜。</li>
 *   <li>{@link #forceOcr}：跳过文本层探测，所有页面强制走"渲染 + OCR"。
 *       用于竖排、页面旋转等文本层坐标不可信的版式兜底。</li>
 * </ul>
 */
@Getter
@Builder
public class PdfOcrConfig {

	/**
	 * 渲染通道输出 DPI（默认 200，发票/证件类建议 200~300）。
	 */
	@Builder.Default
	private final int renderDpi = 200;
	/**
	 * 文本层可用最小非空白字符数（默认 20，低于视为无文本层）。
	 */
	@Builder.Default
	private final int minTextChars = 20;
	/**
	 * 文本层可读字符占比下限（默认 0.90）。
	 * 缺 ToUnicode 映射的 CID 字体会抽取出一批私有区/未赋值字符，拉低占比。
	 */
	@Builder.Default
	private final double minReadableRatio = 0.90;
	/**
	 * 是否强制所有页面走"渲染 + OCR"通道（默认 false）。
	 * 用于文本层坐标不可信的版式（竖排、页面 /Rotate 旋转等）兜底。
	 */
	@Builder.Default
	private final boolean forceOcr = false;

	private static final PdfOcrConfig DEFAULTS = builder().build();

	/**
	 * 默认配置（不可变共享实例）。
	 *
	 * @return 默认配置
	 */
	public static PdfOcrConfig defaults() {
		return DEFAULTS;
	}
}
