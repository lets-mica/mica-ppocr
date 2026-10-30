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

package net.dreamlu.mica.ai.ppocr.structured.parser.core;

import lombok.experimental.UtilityClass;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 日期修复：把被 OCR 弄乱的日期还原成标准 {@code yyyy-MM-dd}。
 *
 * <p><b>解决什么问题</b>：证件上的日期分隔符是印刷体，OCR 常把 {@code -} 认成
 * {@code 0}、{@code .}、{@code /} 或直接吞掉，于是出现
 * {@code "2015-0518"}、{@code "2015.05.18"}、{@code "2017-05-1.2"} 这类文本 ——
 * 它们<b>看起来</b>像日期，却匹配不上 {@code \d{4}-\d{2}-\d{2}}。
 *
 * <p><b>两条修复路径</b>：
 * <ol>
 *   <li><b>结构化重排</b>：按「4 位年 + 分隔 + 1~2 位月 + 分隔 + 1~2 位日」切分，
 *       容忍分隔符个数与种类不一（{@code -} / {@code .} / {@code /} / {@code 年月日}）；</li>
 *   <li><b>纯数字重排</b>：取文本中全部数字凑满 8 位，按 {@code yyyyMMdd} 还原
 *       （对应"分隔符被认成数字"的场景，如 {@code "2015-0518"}）。</li>
 * </ol>
 *
 * <p>两条路径都会校验年月日合法性（月份 1~12、日期不超出当月天数、闰年规则），
 * 非法则返回 null —— <b>宁可修不出，也不修错</b>。
 *
 * <p>本类为纯函数，零依赖、线程安全。
 */
@UtilityClass
public class DateRepair {

	/**
	 * 结构化日期：4 位年 + 非数字分隔 + 1~2 位月 + 非数字分隔 + 1~2 位日。
	 *
	 * <p>分隔符要求"至少 1 个非数字字符"，从而把 {@code "20150518"} 这类纯数字留给数字重排路径。
	 */
	private static final Pattern STRUCTURED = Pattern.compile("(\\d{4})\\D{1,4}?(\\d{1,2})\\D{1,4}?(\\d{1,2})");

	/**
	 * 修复被 OCR 弄乱的日期。
	 *
	 * @param text 含噪日期文本
	 * @return 修复后的 {@code yyyy-MM-dd}；无法修复时返回 null
	 */
	public static String repairDate(String text) {
		if (text == null) {
			return null;
		}
		String s = text.trim();
		if (s.isEmpty()) {
			return null;
		}
		String digits = s.replaceAll("[^0-9]", "");

		// 路径 1：纯数字重排（恰好 8 位）—— 最可靠。
		// 分隔符被认成数字时天然免疫："2015-0518" → "20150518"。
		// 必须排在结构化路径之前：结构化路径会把 "2017-05-1.2" 切成 "2017-05-01"（丢掉 12 的 2）。
		if (digits.length() == 8) {
			String repaired = format(digits.substring(0, 4), digits.substring(4, 6), digits.substring(6, 8));
			if (repaired != null) {
				return repaired;
			}
		}

		// 路径 2：结构化重排（处理单位数月/日，如 "2015-5-8"、"2015年3月5日"）
		Matcher m = STRUCTURED.matcher(s);
		if (m.find()) {
			String repaired = format(m.group(1), m.group(2), m.group(3));
			if (repaired != null) {
				return repaired;
			}
		}

		// 路径 3：数字多于 8 位（如后面还跟着时间）→ 滑窗找第一段合法日期
		if (digits.length() > 8) {
			for (int i = 0; i + 8 <= digits.length(); i++) {
				String repaired = format(digits.substring(i, i + 4), digits.substring(i + 4, i + 6),
					digits.substring(i + 6, i + 8));
				if (repaired != null) {
					return repaired;
				}
			}
		}
		return null;
	}

	/**
	 * 判定文本是否可修复为合法日期（不返回修复结果）。
	 *
	 * @param text 含噪日期文本
	 * @return true 表示可修复
	 */
	public static boolean isRepairableDate(String text) {
		return repairDate(text) != null;
	}

	/**
	 * 组装并校验 {@code yyyy-MM-dd}。
	 *
	 * @param year  年（字符串，允许 "1996" 形态）
	 * @param month 月（字符串，允许 "1" / "01"）
	 * @param day   日（字符串，允许 "2" / "02"）
	 * @return 合法则返回 {@code yyyy-MM-dd}，否则返回 null
	 */
	private static String format(String year, String month, String day) {
		int y = parse(year);
		int m = parse(month);
		int d = parse(day);
		if (y < 0 || m < 0 || d < 0) {
			return null;
		}
		try {
			// LocalDate.of 会校验月份范围、当月天数与闰年规则
			LocalDate.of(y, m, d);
		} catch (DateTimeException e) {
			return null;
		}
		return String.format(Locale.ROOT, "%04d-%02d-%02d", y, m, d);
	}

	/**
	 * 解析数字字符串，非法返回 -1。
	 *
	 * @param s 数字字符串
	 * @return 数值；非法返回 -1
	 */
	private static int parse(String s) {
		if (s == null || s.isEmpty() || s.length() > 4) {
			return -1;
		}
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c < '0' || c > '9') {
				return -1;
			}
		}
		return Integer.parseInt(s);
	}
}
