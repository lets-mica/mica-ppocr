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

import lombok.Data;
import lombok.EqualsAndHashCode;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.BaseStructuredResult;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.FieldConfidence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通用抽取结果。
 *
 * <p>字段值以 {@link #getValues()}（map，key 为 schema 里声明的字段名）承载，
 * 不为每个字段生成一个固定属性 —— schema 是运行期决定的，属性没法预生成。
 *
 * <p>继承 {@link BaseStructuredResult}，获得：
 * <ul>
 *   <li>{@code rawResults} —— 原始 OCR 结果；</li>
 *   <li>{@code fieldBoxes} —— 字段名 → 对应 OCR 框坐标（便于页面高亮复原）；</li>
 *   <li>{@code fieldConfidences} —— 字段名 → 字段级置信度（值从哪来、可不可信、有无违规）；</li>
 *   <li>{@code needsReview} / {@code reviewReasons} —— 是否建议人工复核及原因。</li>
 * </ul>
 *
 * <p>典型用法：
 * <pre>
 * OcrStructuredResult r = parser.parse(imagePath);
 * String contractNo = r.getValue("contractNo");
 * if (!r.allTrusted()) {
 *     // 逐字段看原因
 *     r.getNeedingReviewFields().forEach(f -&gt; log.warn("{} 需复核", f));
 * }
 * </pre>
 *
 * @see OcrStructuredParser
 * @see OcrFieldSchema
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class OcrStructuredResult extends BaseStructuredResult {

	/**
	 * 单据类型名（来自 schema）。
	 */
	private String documentType;

	/**
	 * 字段名 → 字段值。
	 *
	 * <p>用 {@link LinkedHashMap} 保持 schema 里的字段顺序，便于日志与人工核对。
	 */
	private Map<String, String> values = new LinkedHashMap<>();

	/**
	 * 未取到值的字段名列表。
	 *
	 * <p>包含可选字段（{@link FieldSpec#optional()}）—— "没取到"是事实，
	 * "该不该复核"是另一回事（见 {@link #isNeedsReview()}）。
	 */
	private List<String> missingFields = new ArrayList<>();

	/**
	 * 取字段值。
	 *
	 * @param field 字段名
	 * @return 字段值；未取到时返回 null
	 */
	public String getValue(String field) {
		if (field == null) {
			return null;
		}
		return values.get(field);
	}

	/**
	 * 是否所有字段都可信（无缺失、无违规、置信度达标）。
	 *
	 * <p>与 {@link #isNeedsReview()} 的区别：本方法要求<b>一个字段都不缺</b>，
	 * 而 {@code needsReview} 只在"值不可信"时置位。
	 *
	 * @return true 表示所有字段都取到且可信
	 */
	public boolean allTrusted() {
		if (!missingFields.isEmpty()) {
			return false;
		}
		return getNeedingReviewFields().isEmpty();
	}

	/**
	 * 需要人工复核的字段名列表。
	 *
	 * @return 需复核字段名列表
	 */
	public List<String> getNeedingReviewFields() {
		List<String> fields = new ArrayList<>();
		for (Map.Entry<String, FieldConfidence> entry : getFieldConfidences().entrySet()) {
			FieldConfidence confidence = entry.getValue();
			if (confidence != null && confidence.needsReview()) {
				fields.add(entry.getKey());
			}
		}
		return fields;
	}
}
