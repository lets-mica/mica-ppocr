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

package net.dreamlu.mica.ai.ppocr.structured.parser.generic;

import net.dreamlu.mica.ai.ppocr.structured.parser.core.DateRepair;

/**
 * 字段值归一化方式。
 *
 * <p>OCR 识别出的值常带噪声：编号被拆成多段（空格 / 连字符）、日期分隔符被认错、
 * 数字里混入符号。归一化把这些噪声收敛成稳定形态。
 *
 * <p><b>执行时机</b>：<b>取值之后、校验之前</b>。这样保证
 * 「调用方拿到的值」与「规则判定依据的值」永远是同一个 —— 不会出现
 * 「输出看着合规、实际校验的是另一份文本」的错位。
 *
 * <p>本枚举为纯函数，零依赖、线程安全。
 *
 * @see OcrFieldSchema.FieldBuilder#normalize(ValueNormalizer)
 */
public enum ValueNormalizer {

	/**
	 * 原样保留，不做任何处理。
	 */
	NONE {
		@Override
		public String apply(String value) {
			return value;
		}
	},

	/**
	 * 只保留数字 0-9，其余字符全部丢弃。
	 *
	 * <p>适用于纯数字编号（订单号、流水号）。<b>注意</b>：身份证号末位可能是 {@code X}，
	 * 用本归一化会把 {@code X} 丢掉 —— 身份证场景请用 {@link #COMPACT}。
	 */
	DIGITS {
		@Override
		public String apply(String value) {
			if (value == null) {
				return null;
			}
			StringBuilder sb = new StringBuilder(value.length());
			for (int i = 0; i < value.length(); i++) {
				char c = value.charAt(i);
				if (c >= '0' && c <= '9') {
					sb.append(c);
				}
			}
			return sb.toString();
		}
	},

	/**
	 * 去掉空白字符与常见分隔符（空格、全角空格、制表符、连字符、下划线、冒号）。
	 *
	 * <p>适用于"同一编号被 OCR 断成多段"的场景，如
	 * {@code "110105 19491231002X"} → {@code "11010519491231002X"}、
	 * {@code "HT-2024-001"} → {@code "HT2024001"}。
	 *
	 * <p>保留字母与汉字，因此不会破坏身份证末位的 {@code X}，也不会破坏中文地址。
	 */
	COMPACT {
		@Override
		public String apply(String value) {
			if (value == null) {
				return null;
			}
			StringBuilder sb = new StringBuilder(value.length());
			for (int i = 0; i < value.length(); i++) {
				char c = value.charAt(i);
				if (Character.isWhitespace(c)) {
					continue;
				}
				if (c == '-' || c == '—' || c == '–' || c == '_' || c == ':' || c == '：') {
					continue;
				}
				sb.append(c);
			}
			return sb.toString();
		}
	},

	/**
	 * 日期修复：把分隔符被认错的日期还原成 {@code yyyy-MM-dd}。
	 *
	 * <p>内部复用 {@link DateRepair}：无法修复（非法日期）时<b>原样返回</b>，
	 * 不做"修成别的日期"的事 —— 宁可修不出，也不修错。
	 *
	 * <p><b>前置形态闸门</b>：只有"像日期"的文本才进修复流程（见 {@link #looksLikeDate}）。
	 * 没有这道闸门时，{@link DateRepair} 的滑窗路径（数字 &gt; 8 位时取前 8 位）
	 * 会把 18 位身份证号 {@code "11010519491231002X"} 修成 {@code "1101-05-19"} ——
	 * 合法但完全错误的日期；而归一化发生在校验之前，这个假日期还能通过
	 * {@code \d{4}-\d{2}-\d{2}} 形态校验，最终拿到满置信度且不触发人工复核 ⇒ 静默错误。
	 */
	DATE {
		@Override
		public String apply(String value) {
			if (value == null) {
				return null;
			}
			if (!looksLikeDate(value)) {
				return value;
			}
			String repaired = DateRepair.repairDate(value);
			return repaired == null ? value : repaired;
		}
	};

	/**
	 * 归一化字段值。
	 *
	 * @param value 原始值，可为 null
	 * @return 归一化后的值；入参为 null 时返回 null
	 */
	public abstract String apply(String value);

	/**
	 * 判定文本是否"像日期"，作为 {@link #DATE} 归一化的前置闸门。
	 *
	 * <p>判定口径（满足其一即可）：
	 * <ul>
	 *   <li>去掉非数字字符后<b>恰好 8 位</b>（纯数字日期，如 "20150305"）；</li>
	 *   <li>原文含<b>日期分隔符</b>：{@code 年} / {@code 月} / {@code 日} 字样，
	 *       或 {@code -} / {@code .} / {@code /}
	 *       （如 "2015-03-05"、"2015年3月5日"、"2015.03.05"）。</li>
	 * </ul>
	 *
	 * <p>长数字串（&gt; 8 位且无分隔符）一律判为"不像日期" —— 那通常是身份证号 /
	 * 银行卡号 / 订单号，滑窗修出来的只会是假日期。
	 *
	 * @param value 原始值，非 null
	 * @return true 表示可以尝试日期修复
	 */
	private static boolean looksLikeDate(String value) {
		int digits = 0;
		boolean hasSeparator = false;
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (c >= '0' && c <= '9') {
				digits++;
			} else if (c == '年' || c == '月' || c == '日' || c == '-' || c == '.' || c == '/') {
				hasSeparator = true;
			}
		}
		return digits == 8 || hasSeparator;
	}
}
