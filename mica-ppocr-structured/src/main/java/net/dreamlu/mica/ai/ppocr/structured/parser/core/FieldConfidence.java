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
import net.dreamlu.mica.ai.ppocr.utils.CollUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 单个业务字段的置信度信息。
 *
 * <p><b>解决什么问题</b>：结构化结果里只有"值"，调用方无从判断这个值
 * 「能不能直接用」。本类把三件事说清楚：
 * <ul>
 *   <li><b>值是什么</b> —— {@link #value}；</li>
 *   <li><b>值从哪来</b> —— {@link #source}（标签定位 / 正则兜底 / 推算 / 回填）；</li>
 *   <li><b>值可不可信</b> —— {@link #score} 与 {@link #violations}。</li>
 * </ul>
 *
 * <p><b>关键设计：校验不通过时置信度直接归零，而不是打折。</b>
 * 校验位不符意味着这个值<b>确定</b>是错的，给 0.5 分只会让下游误以为"大概能用"。
 *
 * <p>典型用法：
 * <pre>
 * FieldConfidence fc = result.getFieldConfidences().get("idNumber");
 * if (fc != null &amp;&amp; fc.needsReview()) {
 *     // 送人工复核，或让上游按"待确认"处理
 * }
 * </pre>
 *
 * @see FieldSource
 * @see BaseStructuredResult#putFieldConfidence(FieldConfidence)
 */
@Value
@Accessors(fluent = true)
public class FieldConfidence {

	/**
	 * 需人工复核的置信度阈值：低于此值即视为"不宜直接采信"。
	 */
	public static final double REVIEW_THRESHOLD = 0.8;

	/**
	 * 取值路径未知的 OCR 置信度基线。
	 */
	public static final double DEFAULT_OCR_SCORE = 0.9;

	/**
	 * 正则兜底相对标签定位的折扣系数。
	 */
	public static final double PATTERN_DISCOUNT = 0.9;

	/**
	 * 推算 / 回填的置信度基线（不依赖 OCR 文本，但依赖源字段可靠）。
	 */
	public static final double DERIVED_SCORE = 0.9;

	/**
	 * 字段名（与结果类属性名一致，如 "idNumber"）。
	 */
	String field;

	/**
	 * 字段值；未取到时为 null。
	 */
	String value;

	/**
	 * 综合置信度，取值 [0, 1]。越大越可信。
	 */
	double score;

	/**
	 * OCR 原始置信度（来自值框的识别分数）；无来源时为 null。
	 */
	Float ocrScore;

	/**
	 * 取值来源。
	 */
	FieldSource source;

	/**
	 * 违规说明（如"GB 11643 校验位不符"）；无违规时为空列表。
	 */
	List<String> violations;

	/**
	 * 构造字段置信度。
	 *
	 * @param field      字段名
	 * @param value      字段值
	 * @param score      综合置信度
	 * @param ocrScore   OCR 原始置信度，可为 null
	 * @param source     取值来源
	 * @param violations 违规说明，不可为 null
	 */
	public FieldConfidence(String field, String value, double score, Float ocrScore,
						   FieldSource source, List<String> violations) {
		this.field = field;
		this.value = value;
		this.score = score;
		this.ocrScore = ocrScore;
		this.source = source;
		this.violations = violations == null
			? Collections.<String>emptyList()
			: Collections.unmodifiableList(new ArrayList<>(violations));
	}

	/**
	 * 按取值来源计算置信度：无违规时。
	 *
	 * @param field    字段名
	 * @param value    字段值
	 * @param ocrScore OCR 原始置信度，可为 null
	 * @param source   取值来源
	 * @return 字段置信度
	 */
	public static FieldConfidence of(String field, String value, Float ocrScore, FieldSource source) {
		return of(field, value, ocrScore, source, null);
	}

	/**
	 * 按取值来源计算置信度。
	 *
	 * <p>置信度基线规则：
	 * <ul>
	 *   <li>{@link FieldSource#LABEL} —— 取 OCR 置信度本身；</li>
	 *   <li>{@link FieldSource#PATTERN} —— OCR 置信度 × {@link #PATTERN_DISCOUNT}；</li>
	 *   <li>{@link FieldSource#DERIVED} / {@link FieldSource#BACKFILL} —— {@link #DERIVED_SCORE}；</li>
	 *   <li>{@link FieldSource#MISSING} —— 0。</li>
	 * </ul>
	 * <b>违规非空时一律归零</b>（校验位不符 = 确定认错，不打折）。
	 *
	 * @param field      字段名
	 * @param value      字段值
	 * @param ocrScore   OCR 原始置信度，可为 null
	 * @param source     取值来源
	 * @param violations 违规说明，可为 null / 空
	 * @return 字段置信度
	 */
	public static FieldConfidence of(String field, String value, Float ocrScore,
									 FieldSource source, List<String> violations) {
		boolean hasViolation = violations != null && !violations.isEmpty();
		if (value == null || value.isEmpty()) {
			// 值压根没取到：来源按 MISSING 处理，置信度 0。
			// "字段不存在"不是"值不可信"，是否需要复核由解析器按版面预期决定。
			return new FieldConfidence(field, value, 0d, ocrScore, FieldSource.MISSING, violations);
		}
		double base;
		if (source == FieldSource.MISSING) {
			base = 0d;
		} else if (source == FieldSource.DERIVED || source == FieldSource.BACKFILL) {
			base = DERIVED_SCORE;
		} else {
			double raw = ocrScore == null ? DEFAULT_OCR_SCORE : ocrScore;
			base = source == FieldSource.PATTERN ? raw * PATTERN_DISCOUNT : raw;
		}
		double score = hasViolation ? 0d : base;
		return new FieldConfidence(field, value, score, ocrScore, source, violations);
	}

	/**
	 * 按取值来源计算置信度，并带上校验位结论。
	 *
	 * <p>仅当校验结论为 {@link ValidationStatus#FAIL}（校验位不符）时才记违规 ——
	 * {@link ValidationStatus#UNCHECKABLE} 只说明"长得不像"，不能据此断定认错字符，
	 * 不计入违规（详见 {@link ValidationResult#mismatched()}）。
	 *
	 * @param field      字段名
	 * @param parsed     字段解析结果
	 * @param validation 校验结论，可为 null
	 * @return 字段置信度
	 */
	public static FieldConfidence of(String field, ParsedField parsed, ValidationResult validation) {
		List<String> violations = (validation != null && validation.mismatched())
			? CollUtil.listOf(validation.message())
			: CollUtil.<String>listOf();
		if (parsed == null) {
			return of(field, null, null, FieldSource.MISSING, violations);
		}
		return of(field, parsed.value(), parsed.ocrScore(), parsed.source(), violations);
	}

	/**
	 * 是否需要人工复核。
	 *
	 * <p>判定依据：
	 * <ul>
	 *   <li>存在违规说明（校验位不符等硬证据）→ 需复核；</li>
	 *   <li>值压根没取到（{@link FieldSource#MISSING}）→ <b>不算</b>：
	 *       "字段不存在"与"值不可信"是两件事，是否要复核由解析器按版面预期决定
	 *       （{@code BaseStructuredResult#markNeedsReview(String)}）；</li>
	 *   <li>置信度低于 {@link #REVIEW_THRESHOLD} → 需复核。</li>
	 * </ul>
	 *
	 * @return true 表示该字段不宜直接采信
	 */
	public boolean needsReview() {
		if (!violations.isEmpty()) {
			return true;
		}
		if (source == FieldSource.MISSING) {
			return false;
		}
		return score < REVIEW_THRESHOLD;
	}
}
