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

package net.dreamlu.mica.ai.ppocr.structured.parser.bankcard;

import lombok.Data;
import lombok.EqualsAndHashCode;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.BaseStructuredResult;

/**
 * 银行卡 OCR 结构化解析结果。
 *
 * <p>典型字段：卡号、有效期、持卡人姓名、发卡行、卡片类型（借记/信用卡）。
 *
 * <p>继承 {@link BaseStructuredResult}：
 * <ul>
 *   <li>{@code rawResults} —— 原始 OCR 结果（含所有文字框）</li>
 *   <li>{@code fieldBoxes} —— 字段名 → 对应 OCR 框坐标（key: cardNumber/validDate/holderName/bankName/cardType）。
 *       本解析器走文本版标签匹配，<b>不回填</b>该 map（始终为空）；需要坐标请用 {@code rawResults} 自行定位。</li>
 * </ul>
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class BankCardResult extends BaseStructuredResult {
	/**
	 * 卡号（一般为 16~19 位数字）
	 */
	private String cardNumber;
	/**
	 * 有效期，格式 MM/YY
	 */
	private String validDate;
	/**
	 * 持卡人姓名
	 */
	private String holderName;
	/**
	 * 发卡行
	 */
	private String bankName;
	/**
	 * 卡片类型：DEBIT / CREDIT
	 */
	private String cardType;
}
