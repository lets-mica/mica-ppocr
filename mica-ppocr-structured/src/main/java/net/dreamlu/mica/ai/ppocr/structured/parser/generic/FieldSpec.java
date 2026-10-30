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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 单个字段的声明（schema 的一条）。
 *
 * <p>由 {@link ExtractionSchema.FieldBuilder} 构造，不可变。
 *
 * @see ExtractionSchema
 */
public class FieldSpec {

	/**
	 * 字段名（结果 map 的 key）。
	 */
	private final String name;

	/**
	 * 标签文本列表：第一个为规范标签，其余为同一字段的其它写法（别名）。
	 *
	 * <p>可为空 —— 此时该字段只能靠 {@link #regex} 兜底抽取。
	 */
	private final List<String> labels;

	/**
	 * 内容正则；可为 null。
	 *
	 * <p>兼两个角色：
	 * <ul>
	 *   <li><b>定位</b> —— 全文兜底，命中的 OCR 框文本需<b>整段</b>符合该正则
	 *       （{@link java.util.regex.Matcher#matches()} 全匹配语义）；</li>
	 *   <li><b>形状约束</b> —— 最终输出值（<b>归一化之后</b>）也必须符合该正则，
	 *       否则记违规、置信度归零并置位需复核。</li>
	 * </ul>
	 *
	 * <p>⚠️ 约束作用在归一化<b>之后</b>的输出值上，这样"调用方拿到的值"与
	 * "判定依据的值"永远是同一个 —— 否则 {@code .regex("\\d{18}").compact()}
	 * 这类组合会因为先校验后归一化而形同虚设。
	 */
	private final Pattern regex;

	/**
	 * 校验规则列表；可为空。
	 */
	private final List<FieldRule> rules;

	/**
	 * 值归一化方式；不会为 null（缺省 {@link Normalizer#NONE}）。
	 */
	private final Normalizer normalizer;

	/**
	 * 是否为可选字段。
	 *
	 * <p>默认 false —— schema 里声明的字段即"这张单据应该有该字段"，
	 * 缺失时触发人工复核标记。设为 true 则缺失不触发复核。
	 */
	private final boolean optional;

	/**
	 * 构造字段声明。
	 *
	 * @param name       字段名，不可为空
	 * @param labels     标签列表，可为 null
	 * @param regex      内容正则，可为 null
	 * @param rules      校验规则列表，可为 null
	 * @param normalizer 归一化方式，可为 null（按 {@link Normalizer#NONE} 处理）
	 * @param optional   是否可选字段
	 */
	public FieldSpec(String name, List<String> labels, Pattern regex, List<FieldRule> rules,
					 Normalizer normalizer, boolean optional) {
		if (name == null || name.isEmpty()) {
			throw new IllegalArgumentException("field name must not be empty");
		}
		this.name = name;
		this.labels = labels == null
			? Collections.<String>emptyList()
			: Collections.unmodifiableList(new ArrayList<>(labels));
		this.regex = regex;
		this.rules = rules == null
			? Collections.<FieldRule>emptyList()
			: Collections.unmodifiableList(new ArrayList<>(rules));
		this.normalizer = normalizer == null ? Normalizer.NONE : normalizer;
		this.optional = optional;
	}

	/**
	 * 字段名。
	 *
	 * @return 字段名
	 */
	public String name() {
		return name;
	}

	/**
	 * 标签列表（第一个为规范标签）。
	 *
	 * @return 标签列表，不可变
	 */
	public List<String> labels() {
		return labels;
	}

	/**
	 * 内容正则。
	 *
	 * @return 内容正则；可为 null
	 */
	public Pattern regex() {
		return regex;
	}

	/**
	 * 校验规则列表。
	 *
	 * @return 规则列表，不可变
	 */
	public List<FieldRule> rules() {
		return rules;
	}

	/**
	 * 归一化方式。
	 *
	 * @return 归一化方式，不会为 null
	 */
	public Normalizer normalizer() {
		return normalizer;
	}

	/**
	 * 是否可选字段。
	 *
	 * @return true 表示缺失时不触发人工复核
	 */
	public boolean optional() {
		return optional;
	}
}
