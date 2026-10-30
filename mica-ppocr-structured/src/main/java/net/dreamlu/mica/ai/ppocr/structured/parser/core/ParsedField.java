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

import lombok.Value;
import lombok.experimental.Accessors;

/**
 * 字段解析结果：值 + 取值来源 + OCR 置信度。
 *
 * <p>解析器内部经常要在多条取值路径之间选择（标签定位 / 正则兜底 / 推算 / 回填），
 * 选完之后还得记住"这次走的是哪条路"—— 否则没法算出可信的字段置信度。
 * 本类就是这条"路径记忆"。
 *
 * @see FieldConfidence
 * @see FieldSource
 */
@Value
@Accessors(fluent = true)
public class ParsedField {

	/**
	 * 字段值；未取到时为 null。
	 */
	String value;

	/**
	 * 取值来源。
	 */
	FieldSource source;

	/**
	 * OCR 原始置信度（值来自 OCR 文本框时可得）；推算 / 回填时为 null。
	 */
	Float ocrScore;

	/**
	 * 构造解析结果。
	 *
	 * @param value    字段值
	 * @param source   取值来源
	 * @param ocrScore OCR 原始置信度，可为 null
	 */
	public ParsedField(String value, FieldSource source, Float ocrScore) {
		this.value = value;
		this.source = source == null ? FieldSource.MISSING : source;
		this.ocrScore = ocrScore;
	}

	/**
	 * 标签定位命中。
	 *
	 * @param value    字段值
	 * @param ocrScore OCR 原始置信度，可为 null
	 * @return 解析结果
	 */
	public static ParsedField label(String value, Float ocrScore) {
		return new ParsedField(value, FieldSource.LABEL, ocrScore);
	}

	/**
	 * 内容 / 正则兜底命中。
	 *
	 * @param value    字段值
	 * @param ocrScore OCR 原始置信度，可为 null
	 * @return 解析结果
	 */
	public static ParsedField pattern(String value, Float ocrScore) {
		return new ParsedField(value, FieldSource.PATTERN, ocrScore);
	}

	/**
	 * 由其它字段推算得出。
	 *
	 * @param value 字段值
	 * @return 解析结果
	 */
	public static ParsedField derived(String value) {
		return new ParsedField(value, FieldSource.DERIVED, null);
	}

	/**
	 * 由其它证据回填得出。
	 *
	 * @param value 字段值
	 * @return 解析结果
	 */
	public static ParsedField backfill(String value) {
		return new ParsedField(value, FieldSource.BACKFILL, null);
	}

	/**
	 * 未取到值。
	 *
	 * @return 解析结果
	 */
	public static ParsedField missing() {
		return new ParsedField(null, FieldSource.MISSING, null);
	}

	/**
	 * 是否取到了值。
	 *
	 * @return true 表示值非空
	 */
	public boolean hasValue() {
		return value != null && !value.isEmpty();
	}
}
