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
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * PDF 页面文本层质量评分（启发式）。
 *
 * <p>用于双通道分流判定：文本层"存在"不等于"可用"——缺 ToUnicode 映射的 CID
 * 字体、Type3 字形等会抽取出一批私有区/未赋值字符，看似有文本实际不可读。
 * 因此以「非空白字符数 + 可读字符占比」双指标判定：
 * {@link #usable(int, double)}。
 *
 * <p>注意这是启发式判定：映射"错但合法"的乱码（落在 CJK 合法区）无法被字符级
 * 规则识别，极端场景可通过 {@link PdfOcrConfig#isForceOcr()} 强制走 OCR 通道。
 */
@Getter
@ToString
@Accessors(fluent = true)
public final class PdfTextQuality {

	/**
	 * 非空白字符总数（不含空白与控制字符）。
	 */
	private final int totalChars;
	/**
	 * 可读字符数（非空白、非控制、非 U+FFFD、非未赋值/私有区/代理字符）。
	 */
	private final int readableChars;

	/**
	 * 构造质量评分。
	 *
	 * @param totalChars    非空白字符总数
	 * @param readableChars 可读字符数
	 */
	public PdfTextQuality(int totalChars, int readableChars) {
		this.totalChars = totalChars;
		this.readableChars = readableChars;
	}

	/**
	 * 可读字符占比，范围 [0, 1]；无字符时为 0。
	 *
	 * @return 可读占比
	 */
	public double readableRatio() {
		return totalChars == 0 ? 0.0 : (double) readableChars / totalChars;
	}

	/**
	 * 双阈值判定文本层是否可用。
	 *
	 * @param minTextChars     最小非空白字符数
	 * @param minReadableRatio 最小可读占比，范围 [0, 1]
	 * @return true 表示文本层可用，可走坐标抽取通道
	 */
	public boolean usable(int minTextChars, double minReadableRatio) {
		return totalChars >= minTextChars && readableRatio() >= minReadableRatio;
	}

	/**
	 * 字符级可读性判定。
	 *
	 * <p>规则：排除空白、ISO 控制字符、U+FFFD 替换字符、未赋值（Cn）、
	 * 私有区（Co）、代理项（Cs）字符。后三者是缺 ToUnicode 映射时
	 * PDFBox 抽取乱码的高发落点。
	 *
	 * @param c 待判定字符
	 * @return true 表示可读
	 */
	static boolean isReadableChar(char c) {
		if (Character.isWhitespace(c) || c == '\uFFFD') {
			return false;
		}
		if (Character.isISOControl(c)) {
			return false;
		}
		int type = Character.getType(c);
		return type != Character.UNASSIGNED
			&& type != Character.PRIVATE_USE
			&& type != Character.SURROGATE;
	}
}
