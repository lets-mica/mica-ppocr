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

package net.dreamlu.mica.ai.ppocr.structured.parser.invoice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

/**
 * 大写金额 → 小写金额字符串解析器。
 *
 * <p>用于 OCR 通道的小写金额兑底：价税合计 OCR 误识后（如 "59976.00" → "593.82"，
 * 在多列金额表格上丢位），优先取大写金额反推结果。
 *
 * <p>支持中文大写数字：零壹贰叁肆伍陆柒挪玖拾佰仟万亿圆元角分整。
 *
 * <p>输入示例：
 * <ul>
 *   <li>"伍万玖仟玖佰染拾陆圆整" → "59976.00"</li>
 *   <li>"贰拾壹圆染角玖分" → "21.79"</li>
 *   <li>"壹佰叁拾玖圆整" → "139.00"</li>
 *   <li>"陆仟壹佰叁拾贰圆伍角整" → "6132.50"</li>
 * </ul>
 *
 * <p>实现说明：
 * <ol>
 *   <li>将中文数字逐字映射为 {@code long}，分段累积（万段 / 个段）；</li>
 *   <li>"亿/万" 段间补零；圆后转为角分（最大两位小数）；</li>
 *   <li>输出 "X.XX"（含 .00）格式，便于与文本层 "¥59976.00" 对齐。</li>
 * </ol>
 */
final class UpperMoneyConverter {

	private static final Map<Character, Integer> DIGITS = new HashMap<>();
	static {
		DIGITS.put('零', 0);
		DIGITS.put('壹', 1);
		DIGITS.put('贰', 2);
		DIGITS.put('叁', 3);
		DIGITS.put('肆', 4);
		DIGITS.put('伍', 5);
		DIGITS.put('陆', 6);
		DIGITS.put('柒', 7);
		DIGITS.put('捌', 8);
		DIGITS.put('玖', 9);
	}

	private UpperMoneyConverter() {
	}

	/**
	 * 把大写金额文本转换为 "X.XX" 小写金额字符串。
	 *
	 * @param upper 大写金额文本（如 "伍万玖仟玖佰染拾陆圆整"），可为 null
	 * @return 小写金额字符串（如 "59976.00"）；输入无效返回 null
	 */
	static String toLower(String upper) {
		if (upper == null) return null;
		String s = stripNoise(upper);
		if (s.isEmpty()) return null;

		// 切分圆/元前后
		int yuanIdx = indexOfYuan(s);
		int afterStart;
		long yuan;
		int jiaoFenCount;
		if (yuanIdx < 0) {
			// 无圆元，整段视为整数部分
			afterStart = s.length();
			yuan = parseInteger(s, 0, s.length());
			jiaoFenCount = 0;
		} else {
			yuan = parseInteger(s, 0, yuanIdx);
			afterStart = yuanIdx + 1;
			jiaoFenCount = parseJiaoFen(s, afterStart);
		}
		if (yuan < 0) {
			return null;
		}
		BigDecimal total = BigDecimal.valueOf(yuan)
			.add(BigDecimal.valueOf(jiaoFenCount).movePointLeft(2));
		return total.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
	}

	/**
	 * 清噪声：去掉 OCR 前缀的 ⊙ 等符号、末尾"整"、空白、繁体"圆"。
	 */
	private static String stripNoise(String s) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (DIGITS.containsKey(c)
				|| c == '拾' || c == '佰' || c == '仟'
				|| c == '万' || c == '億' || c == '亿'
				|| c == '圆' || c == '元'
				|| c == '角' || c == '分'
				|| c == '整'
				|| c == '萬') {
				sb.append(c);
			}
		}
		return sb.toString();
	}

	private static int indexOfYuan(String s) {
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '圆' || c == '元') return i;
		}
		return -1;
	}

	/**
	 * 解析整数部分（圆/元之前）。
	 *
	 * <p>逐字读：拾佰仟 是位权、万 是段权、零 是补位。万段与个段分开累积，
	 * 遇"万"时把当前个段累积结果乘 10000 加到 wanSection。
	 *
	 * @return 整数金额；解析失败返回 -1
	 */
	private static long parseInteger(String s, int start, int end) {
		long wanSection = 0;   // 万及以上
		long curSection = 0;   // 当前段累积
		long curDigit = 0;     // 当前未配单位的数字
		boolean hasWan = false;

		for (int i = start; i < end; i++) {
			char c = s.charAt(i);
			Integer digit = DIGITS.get(c);
			if (digit != null) {
				curDigit = digit;
				continue;
			}
			if (c == '拾') {
				if (curDigit == 0) curDigit = 1;  // "拾"起头 = 10
				curSection += curDigit * 10;
				curDigit = 0;
			} else if (c == '佰') {
				curSection += curDigit * 100;
				curDigit = 0;
			} else if (c == '仟') {
				curSection += curDigit * 1000;
				curDigit = 0;
			} else if (c == '万' || c == '萬') {
				// 当前段 + 当前数字 → 收尾，乘 10000
				curSection += curDigit;
				wanSection = wanSection * 10_000 + curSection;
				curSection = 0;
				curDigit = 0;
				hasWan = true;
			} else if (c == '亿' || c == '億') {
				curSection += curDigit;
				wanSection = (wanSection * 10_000 + curSection) * 100_000_000;
				curSection = 0;
				curDigit = 0;
				hasWan = true;
			} else {
				// 其它字符（理论上 stripNoise 已过滤）
				return -1;
			}
		}
		// 收尾：把最后的段 + 残留数字加上
		long total;
		if (hasWan) {
			total = wanSection * 10_000 + curSection + curDigit;
		} else {
			total = curSection + curDigit;
		}
		return total;
	}

	/**
	 * 解析圆/元后的角分，转换为分（0~99）。
	 *
	 * <p>"伍角" = 50 分；"染角玖分" = 79 分；"整" 或空 = 0 分。
	 */
	private static int parseJiaoFen(String s, int start) {
		int fen = 0;
		long curDigit = 0;
		boolean sawJiao = false;
		boolean sawFen = false;
		for (int i = start; i < s.length(); i++) {
			char c = s.charAt(i);
			Integer digit = DIGITS.get(c);
			if (digit != null) {
				curDigit = digit;
				continue;
			}
			if (c == '角') {
				fen += curDigit * 10;
				curDigit = 0;
				sawJiao = true;
			} else if (c == '分') {
				fen += curDigit;
				curDigit = 0;
				sawFen = true;
			} else if (c == '整') {
				// 结尾，整元，无角分
				if (!sawJiao && !sawFen) {
					fen = 0;
				}
				break;
			}
		}
		// 容错：仅有数字残留（不可能，但防 OCR 漏掉"角"/"分"标签）→ 不补
		return fen;
	}
}
