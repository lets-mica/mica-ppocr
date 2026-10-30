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

import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Engine;
import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Result;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.BaseStructuredParser;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.FieldConfidence;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.FieldSource;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.LabelMatcher;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.ValidationResult;
import net.dreamlu.mica.ai.ppocr.utils.CollUtil;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 通用结构化解析器：用一份声明式 schema 把 OCR 散落文字框组织成业务字段。
 *
 * <p><b>定位</b>：schema 驱动的通用版结构化解析器 —— 新增单据类型的<b>增量入口</b>，
 * 不再为每种单据写一个 Java 解析器类，改成用 {@link OcrFieldSchema} 描述字段结构即可。
 * 存量 10 个强类型解析器不受影响，也不需要迁移；两者<b>并行存在</b>。
 *
 * <p><b>取值路径</b>（按优先级）：
 * <ol>
 *   <li><b>标签定位</b> —— 复用 {@link LabelMatcher#findLabelHit} 定位标签
 *       （完整等于 &gt; 前缀 &gt; 片段拼接 &gt; fragment &gt; 别名），
 *       再由 {@link ValueLocator} 在标签右侧取值；</li>
 *   <li><b>合并框剥值</b> —— 标签与值被 OCR 合在一框（"合同编号：HT-2024-001"）时剥出值；</li>
 *   <li><b>内容正则兜底</b> —— 标签定位失败、或标签取到的值不符合 schema 正则时；</li>
 *   <li><b>字段缺失</b> —— 都没命中。</li>
 * </ol>
 *
 * <p><b>校验与置信度</b>：复用阶段二的公共能力 ——
 * {@link net.dreamlu.mica.ai.ppocr.structured.parser.core.Validations} 校验位、
 * {@link FieldConfidence} 字段级置信度、{@link FieldSource} 取值来源。
 * 校验不通过时<b>字段值仍然保留</b>，只是该字段置信度直接归零并置位需复核。
 *
 * <p><b>复核触发</b>：schema 里声明的字段即"这张单据应该有该字段"，
 * 缺失时（除非标了 {@code optional()}）触发人工复核 —— 这正是阶段二
 * 「缺失字段由解析器按版面预期显式标记」原则的通用化。
 *
 * <p>本类不可变（schema 不可变），可安全多线程复用。
 *
 * <pre>
 * OcrFieldSchema schema = OcrFieldSchema.builder("采购合同")
 *     .field("contractNo").labels("合同编号").compact().add()
 *     .build();
 * OcrStructuredParser parser = new OcrStructuredParser(engine, schema);
 * OcrStructuredResult result = parser.parse(imagePath);
 * </pre>
 *
 * @see OcrFieldSchema
 * @see OcrStructuredResult
 */
public class OcrStructuredParser extends BaseStructuredParser<OcrStructuredResult> {

	/**
	 * 字段声明。
	 */
	private final OcrFieldSchema schema;

	/**
	 * 构造通用解析器。
	 *
	 * @param engine PP-OCRv6 推理引擎；可为 null（仅调用 {@code parseResults} 时）
	 * @param schema 字段声明；不可为 null
	 * @throws IllegalArgumentException schema 为 null
	 */
	public OcrStructuredParser(PPOcrV6Engine engine, OcrFieldSchema schema) {
		super(engine);
		if (schema == null) {
			throw new IllegalArgumentException("schema must not be null");
		}
		this.schema = schema;
	}

	/**
	 * 构造通用解析器（不绑定推理引擎，仅用于 {@code parseResults}）。
	 *
	 * @param schema 字段声明；不可为 null
	 * @throws IllegalArgumentException schema 为 null
	 */
	public OcrStructuredParser(OcrFieldSchema schema) {
		this(null, schema);
	}

	/**
	 * 字段声明。
	 *
	 * @return schema
	 */
	public OcrFieldSchema schema() {
		return schema;
	}

	@Override
	public OcrStructuredResult parseResults(List<PPOcrV6Result> results) {
		OcrStructuredResult result = new OcrStructuredResult();
		result.setDocumentType(schema.documentType());
		List<PPOcrV6Result> ocr = results == null
			? CollUtil.<PPOcrV6Result>listOf()
			: results;
		// 拷贝一份：与其它解析器一致，避免调用方后续改动入参 list 影响已返回的结果
		result.setRawResults(new ArrayList<>(ocr));

		// 第一轮：定位每个字段的标签框。
		Map<String, LabelMatcher.LabelHit> hits = new LinkedHashMap<>();
		for (FieldSpec spec : schema.fields()) {
			hits.put(spec.name(), findLabel(ocr, spec));
		}

		// 全局标签框集合：供"值归属最近标签"判定使用。
		Set<PPOcrV6Result> labelBoxes = collectLabelBoxes(ocr, hits.values());

		// 第二轮：逐字段抽取。
		for (FieldSpec spec : schema.fields()) {
			extractField(result, spec, ocr, hits.get(spec.name()), labelBoxes);
		}
		return result;
	}

	/**
	 * 定位字段的标签框：按 schema 里声明的标签依次尝试。
	 *
	 * @param ocr  OCR 结果列表
	 * @param spec 字段声明
	 * @return 标签命中信息；未命中返回 null
	 */
	private LabelMatcher.LabelHit findLabel(List<PPOcrV6Result> ocr, FieldSpec spec) {
		for (String label : spec.labels()) {
			LabelMatcher.LabelHit hit = LabelMatcher.findLabelHit(ocr, label);
			if (hit != null) {
				return hit;
			}
		}
		return null;
	}

	/**
	 * 汇总全局标签框集合。
	 *
	 * <p>来源有二：① 各字段实际定位到的标签框；② OCR 文本<b>恰好等于</b>某条声明标签的框
	 * —— 后者用于兜住"标签框存在但没被分配给任何字段"的情形，
	 * 避免相邻标签抢走本字段的值。
	 *
	 * @param ocr  OCR 结果列表
	 * @param hits 各字段的标签命中信息
	 * @return 标签框集合
	 */
	private Set<PPOcrV6Result> collectLabelBoxes(List<PPOcrV6Result> ocr,
												 Iterable<LabelMatcher.LabelHit> hits) {
		Set<PPOcrV6Result> labelBoxes = new LinkedHashSet<>();
		for (LabelMatcher.LabelHit hit : hits) {
			if (hit != null && hit.box() != null) {
				labelBoxes.add(hit.box());
			}
		}
		Set<String> declared = new HashSet<>();
		for (FieldSpec spec : schema.fields()) {
			declared.addAll(spec.labels());
		}
		if (!declared.isEmpty()) {
			for (PPOcrV6Result r : ocr) {
				if (r == null || r.text() == null) {
					continue;
				}
				if (declared.contains(r.text().trim())) {
					labelBoxes.add(r);
				}
			}
		}
		return labelBoxes;
	}

	/**
	 * 抽取单个字段。
	 *
	 * @param result     结果对象
	 * @param spec       字段声明
	 * @param ocr        OCR 结果列表
	 * @param hit        标签命中信息，可为 null
	 * @param labelBoxes 全局标签框集合
	 */
	private void extractField(OcrStructuredResult result, FieldSpec spec, List<PPOcrV6Result> ocr,
							  LabelMatcher.LabelHit hit, Set<PPOcrV6Result> labelBoxes) {
		// 路径一/二：标签定位（含合并框剥值）
		String labelRaw = null;
		PPOcrV6Result valueBox = null;
		if (hit != null) {
			ValueLocator.LocatedValue located = ValueLocator.locate(ocr, hit, labelBoxes);
			if (located != null) {
				labelRaw = located.value();
				valueBox = located.box();
			}
		}

		// 路径三：内容正则兜底
		String regexRaw = null;
		PPOcrV6Result regexBox = null;
		if (spec.regex() != null) {
			LabelMatcher.LabeledMatch regexMatch =
				LabelMatcher.matchPatternWithBox(ocr, spec.regex(), false);
			if (regexMatch.hasValue()) {
				regexRaw = regexMatch.value();
				regexBox = regexMatch.matches().isEmpty() ? null : regexMatch.matches().get(0);
			}
		}

		// 归一化：必须在校验与选值之前 —— 后续所有判定（正则、规则）都作用在
		// "最终输出值"上，保证调用方拿到的值与校验依据永远是同一个。
		// 否则像 .regex("\\d{18}").compact() 这种组合，正则会作用在归一化前的原始值上，
		// 输出值不符合声明却无人报错。
		String labelValue = labelRaw == null ? null : spec.normalizer().apply(labelRaw);
		String regexValue = regexRaw == null ? null : spec.normalizer().apply(regexRaw);

		// 选值：标签路径的（归一化后）值不符合正则时改走正则兜底；正则也兜不到就保留标签值，
		// 交给规则校验暴露问题 —— 宁可保留可能有误的值，也不丢值。
		String value;
		FieldSource source;
		if (labelValue != null) {
			if (spec.regex() == null || spec.regex().matcher(labelValue).matches()) {
				value = labelValue;
				source = FieldSource.LABEL;
			} else if (regexValue != null) {
				value = regexValue;
				source = FieldSource.PATTERN;
				valueBox = regexBox;
			} else {
				value = labelValue;
				source = FieldSource.LABEL;
			}
		} else if (regexValue != null) {
			value = regexValue;
			source = FieldSource.PATTERN;
			valueBox = regexBox;
		} else {
			value = null;
			source = FieldSource.MISSING;
		}

		// 规则校验：只有"确定认错"（FAIL）才记违规。
		List<String> violations = new ArrayList<>();
		if (value != null && !value.isEmpty()) {
			// schema 正则同样是"形状约束"，作用在归一化后的最终值上；
			// 不符合即记违规 —— 与"校验失败保留值、置信度归零"的既有约定一致。
			if (spec.regex() != null && !spec.regex().matcher(value).matches()) {
				violations.add("值不符合 schema 正则 " + spec.regex().pattern() + "：" + value);
			}
			for (FieldRule rule : spec.rules()) {
				ValidationResult validation = rule.check(value);
				if (validation != null && validation.mismatched()) {
					violations.add(validation.message());
				}
			}
		}

		Float ocrScore = valueBox == null ? null : valueBox.score();
		FieldConfidence confidence = FieldConfidence.of(spec.name(), value, ocrScore, source, violations);
		result.putFieldConfidence(confidence);

		if (value != null && !value.isEmpty()) {
			result.getValues().put(spec.name(), value);
			if (valueBox != null && valueBox.box() != null) {
				List<int[][]> boxes = new ArrayList<>(1);
				boxes.add(valueBox.box());
				result.getFieldBoxes().put(spec.name(), boxes);
			}
			return;
		}

		// 字段缺失：schema 里声明了就是"应该有"，非可选字段缺失要提示复核。
		result.getMissingFields().add(spec.name());
		if (!spec.optional()) {
			result.markNeedsReview("字段 " + spec.name() + " 未识别到");
		}
	}
}
