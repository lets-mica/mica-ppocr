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
 * 抽取 schema：用声明描述一张新单据的字段结构。
 *
 * <p><b>定位</b>：通用解析器的声明式入口。新单据类型<b>不需要</b>再写一个
 * Java 解析器类，只要用 schema 把"字段名 → 标签 / 正则 / 规则"讲清楚即可；
 * 存量 10 个强类型解析器不受影响、也不需要迁移。
 *
 * <p>典型用法：
 * <pre>
 * ExtractionSchema schema = ExtractionSchema.builder("采购合同")
 *     .field("contractNo")
 *         .labels("合同编号", "合同号")
 *         .compact()
 *         .add()
 *     .field("amount")
 *         .labels("金额")
 *         .rules(FieldRule.of("金额", v -&gt; v.matches("^[0-9.]+$")))
 *         .add()
 *     .build();
 *
 * GenericResult r = new GenericParser(engine, schema).parse(imagePath);
 * String no = r.getValue("contractNo");
 * </pre>
 *
 * <p><b>取值与校验</b>：归一化先于一切判定 —— 正则与校验规则都作用在
 * <b>最终输出值</b>上，保证"调用方拿到的值"与"判定依据的值"永远是同一个。
 *
 * <p><b>取值的四条路径</b>（按优先级）：
 * <ol>
 *   <li>标签定位 —— 在标签右侧找值，并做"值归属最近标签"判定（见
 *       {@link GenericParser}）；</li>
 *   <li>标签与值合并在同一框 —— 从合并框里剥出值；</li>
 *   <li>内容正则兜底 —— 标签定位失败，或标签取到的（归一化后）值不符合正则时；</li>
 *   <li>都没命中 —— 字段缺失。</li>
 * </ol>
 * <p>本类不可变，线程安全。
 *
 * @see GenericParser
 * @see GenericResult
 */
public final class ExtractionSchema {

	/**
	 * 单据类型名（用于结果标识与日志）。
	 */
	private final String documentType;

	/**
	 * 字段声明列表。
	 */
	private final List<FieldSpec> fields;

	/**
	 * 构造 schema。
	 *
	 * @param documentType 单据类型名
	 * @param fields       字段声明列表
	 */
	private ExtractionSchema(String documentType, List<FieldSpec> fields) {
		if (documentType == null || documentType.isEmpty()) {
			throw new IllegalArgumentException("documentType must not be empty");
		}
		this.documentType = documentType;
		this.fields = Collections.unmodifiableList(new ArrayList<>(fields));
	}

	/**
	 * 创建 schema 构造器。
	 *
	 * @param documentType 单据类型名（如 "采购合同"）
	 * @return 构造器
	 */
	public static Builder builder(String documentType) {
		return new Builder(documentType);
	}

	/**
	 * 单据类型名。
	 *
	 * @return 单据类型名
	 */
	public String documentType() {
		return documentType;
	}

	/**
	 * 字段声明列表。
	 *
	 * @return 字段声明列表，不可变
	 */
	public List<FieldSpec> fields() {
		return fields;
	}

	/**
	 * schema 构造器。
	 */
	public static final class Builder {

		private final String documentType;
		private final List<FieldSpec> fields = new ArrayList<>();
		private FieldBuilder pending;

		/**
		 * 构造构造器。
		 *
		 * @param documentType 单据类型名
		 */
		private Builder(String documentType) {
			if (documentType == null || documentType.isEmpty()) {
				throw new IllegalArgumentException("documentType must not be empty");
			}
			this.documentType = documentType;
		}

		/**
		 * 声明一个字段。
		 *
		 * <p>若上一个字段还没调用 {@link FieldBuilder#add()}，会先自动补上 ——
		 * 少写一个 {@code add()} 不该导致字段凭空消失。
		 *
		 * @param name 字段名
		 * @return 字段构造器
		 */
		public FieldBuilder field(String name) {
			return beginField(name);
		}

		/**
		 * 结束当前字段声明并开启下一个字段。
		 *
		 * @param name 字段名
		 * @return 新的字段构造器
		 */
		FieldBuilder beginField(String name) {
			flush();
			pending = new FieldBuilder(this, name);
			return pending;
		}

		/**
		 * 构建 schema。
		 *
		 * @return schema
		 */
		public ExtractionSchema build() {
			flush();
			return new ExtractionSchema(documentType, fields);
		}

		/**
		 * 把待添加的字段收进列表。
		 */
		private void flush() {
			if (pending != null) {
				fields.add(pending.toSpec());
				pending = null;
			}
		}
	}

	/**
	 * 单个字段的构造器。
	 */
	public static final class FieldBuilder {

		private final Builder parent;
		private final String name;
		private final List<String> labels = new ArrayList<>();
		private Pattern regex;
		private final List<FieldRule> rules = new ArrayList<>();
		private Normalizer normalizer = Normalizer.NONE;
		private boolean optional;

		/**
		 * 构造字段构造器。
		 *
		 * @param parent 所属 schema 构造器
		 * @param name   字段名
		 */
		private FieldBuilder(Builder parent, String name) {
			this.parent = parent;
			this.name = name;
		}

		/**
		 * 声明标签文本。
		 *
		 * <p>第一个为规范标签，其余为同一字段的其它写法（OCR 走样时的兜底）。
		 *
		 * @param values 标签文本
		 * @return 自身
		 */
		public FieldBuilder labels(String... values) {
			if (values != null) {
				for (String value : values) {
					if (value != null && !value.isEmpty()) {
						labels.add(value);
					}
				}
			}
			return this;
		}

		/**
		 * 声明内容正则（全文兜底）。
		 *
		 * <p>语义为<b>全匹配</b>：命中的 OCR 框文本需整段符合该正则。
		 *
		 * @param pattern 正则表达式
		 * @return 自身
		 */
		public FieldBuilder regex(String pattern) {
			this.regex = pattern == null ? null : Pattern.compile(pattern);
			return this;
		}

		/**
		 * 声明校验规则。
		 *
		 * @param values 规则列表
		 * @return 自身
		 */
		public FieldBuilder rules(FieldRule... values) {
			if (values != null) {
				for (FieldRule value : values) {
					if (value != null) {
						rules.add(value);
					}
				}
			}
			return this;
		}

		/**
		 * 声明归一化方式。
		 *
		 * @param value 归一化方式
		 * @return 自身
		 */
		public FieldBuilder normalize(Normalizer value) {
			this.normalizer = value == null ? Normalizer.NONE : value;
			return this;
		}

		/**
		 * 归一化：只保留数字。
		 *
		 * @return 自身
		 */
		public FieldBuilder digits() {
			return normalize(Normalizer.DIGITS);
		}

		/**
		 * 归一化：去掉空白与常见分隔符。
		 *
		 * @return 自身
		 */
		public FieldBuilder compact() {
			return normalize(Normalizer.COMPACT);
		}

		/**
		 * 归一化：日期修复成 {@code yyyy-MM-dd}。
		 *
		 * @return 自身
		 */
		public FieldBuilder asDate() {
			return normalize(Normalizer.DATE);
		}

		/**
		 * 标记为可选字段：缺失时不触发人工复核。
		 *
		 * @return 自身
		 */
		public FieldBuilder optional() {
			this.optional = true;
			return this;
		}

		/**
		 * 结束当前字段声明，可继续声明下一个字段。
		 *
		 * @return schema 构造器
		 */
		public Builder add() {
			parent.flush();
			return parent;
		}

		/**
		 * 结束当前字段声明并直接声明下一个字段。
		 *
		 * <p>等价于先 {@code add()} 再 {@code field(...)}，但少一个 {@code add()} 也不丢字段 ——
		 * 当前字段会被自动补进 schema。
		 *
		 * @param name 下一个字段名
		 * @return 新的字段构造器
		 */
		public FieldBuilder field(String name) {
			return parent.beginField(name);
		}

		/**
		 * 构建 schema（当前字段会被自动补上，无需再调 {@code add()}）。
		 *
		 * @return schema
		 */
		public ExtractionSchema build() {
			return parent.build();
		}

		/**
		 * 生成不可变的字段声明。
		 *
		 * @return 字段声明
		 */
		FieldSpec toSpec() {
			return new FieldSpec(name, labels, regex, rules, normalizer, optional);
		}
	}
}
