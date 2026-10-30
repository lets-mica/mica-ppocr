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

package net.dreamlu.mica.ai.ppocr.structured.parser.idcard;

import lombok.Data;
import lombok.EqualsAndHashCode;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.BaseStructuredResult;

/**
 * 身份证 OCR 结构化解析结果（正反面合一）。
 *
 * <p>正面字段：name / gender / nation / birthDate / address / idNumber。
 * <p>反面字段：issuingAuthority / validFrom / validTo。
 *
 * <p>继承 {@link BaseStructuredResult}：
 * <ul>
 *   <li>{@code rawResults} —— 原始 OCR 结果（含所有文字框）</li>
 *   <li>{@code fieldBoxes} —— 字段名 → 对应 OCR 框坐标（key 即上面的字段名）。
 *       本解析器走文本版标签匹配，<b>不回填</b>该 map（始终为空）；需要坐标请用 {@code rawResults} 自行定位。</li>
 * </ul>
 *
 * <p>{@link #side} 指明解析出的实际版面，便于调用方区分字段有效性：
 * <ul>
 *   <li>正面字段（name 等）在反面图片上识别时会保持 null；</li>
 *   <li>反面字段（issuingAuthority 等）在正面图片上识别时会保持 null。</li>
 * </ul>
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class IdCardResult extends BaseStructuredResult {

	/**
	 * 身份证版面：正面 / 反面。
	 */
	private IdCardSide side;
	/**
	 * 姓名（正面）
	 */
	private String name;
	/**
	 * 性别（正面）
	 */
	private String gender;
	/**
	 * 民族（正面）
	 */
	private String nation;
	/**
	 * 出生日期（正面，"yyyy 年 MM 月 dd 日" 或 "yyyyMMdd"）
	 */
	private String birthDate;
	/**
	 * 住址（正面）
	 */
	private String address;
	/**
	 * 公民身份号码（正面，18 位）
	 */
	private String idNumber;
	/**
	 * 签发机关（反面）
	 */
	private String issuingAuthority;
	/**
	 * 有效期限起始日期（反面）
	 */
	private String validFrom;
	/**
	 * 有效期限截止日期（反面）
	 */
	private String validTo;
}
