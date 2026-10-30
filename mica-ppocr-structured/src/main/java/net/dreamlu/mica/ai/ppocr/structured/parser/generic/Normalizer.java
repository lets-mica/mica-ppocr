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
 * @see ExtractionSchema.FieldBuilder#normalize(Normalizer)
 */
public enum Normalizer {

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
	 */
	DATE {
		@Override
		public String apply(String value) {
			if (value == null) {
				return null;
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
}
