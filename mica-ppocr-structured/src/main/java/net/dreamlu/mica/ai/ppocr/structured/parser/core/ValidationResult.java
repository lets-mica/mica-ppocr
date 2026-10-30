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
 * 单条字段的校验位判定结果。
 *
 * <p><b>为什么需要它</b>：格式正则只能看出"长得像不像"，校验位能看出"数学上自不自洽"。
 * OCR 认错一个字符时格式校验照样通过，校验位逃不掉 —— 这是把「识别成功」
 * 升级为「<b>识别正确</b>」的唯一硬证据。
 *
 * <p>典型用法：
 * <pre>
 * ValidationResult v = Validations.validateIdCard(idNumber);
 * if (!v.valid()) {
 *     // FAIL  → 一定认错了字符，置信度应归零
 *     // UNCHECKABLE → 只是"长得不像"，不能断定认错
 *     log.warn("身份证号校验未通过：{}", v.message());
 * }
 * </pre>
 *
 * @see Validations
 * @see FieldConfidence
 */
@Value
@Accessors(fluent = true)
public class ValidationResult {

	/**
	 * 规则编码，便于日志与上层按规则分组。
	 *
	 * <p>取值见 {@link Validations#RULE_ID_CARD} / {@link Validations#RULE_BANK_CARD}
	 * / {@link Validations#RULE_CREDIT_CODE}。
	 */
	String rule;

	/**
	 * 判定状态（通过 / 不通过 / 无法校验）。
	 */
	ValidationStatus status;

	/**
	 * 实际读到的校验位；无法定位时为 null（如银行卡无独立校验位字符）。
	 */
	String actual;

	/**
	 * 期望的校验位；无法定位时为 null。
	 */
	String expected;

	/**
	 * 中文结论说明，可直接写入日志或返给前端。
	 */
	String message;

	/**
	 * 校验是否通过。
	 *
	 * @return true 仅当状态为 {@link ValidationStatus#PASS}
	 */
	public boolean valid() {
		return status == ValidationStatus.PASS;
	}

	/**
	 * 是否可判定为"识别错误"（而非"格式不对"）。
	 *
	 * <p>只有 {@link ValidationStatus#FAIL} 才是真正的硬证据：
	 * 格式合法但校验位不符 ⇒ OCR 至少认错了一个字符。
	 *
	 * @return true 表示校验位不符，识别结果不可直接采信
	 */
	public boolean mismatched() {
		return status == ValidationStatus.FAIL;
	}

	/**
	 * 构造"通过"结果。
	 *
	 * @param rule    规则编码
	 * @param message 中文说明
	 * @return 通过的校验结果
	 */
	public static ValidationResult pass(String rule, String message) {
		return new ValidationResult(rule, ValidationStatus.PASS, null, null, message);
	}

	/**
	 * 构造"校验位不符"结果。
	 *
	 * @param rule     规则编码
	 * @param actual   实际读到的校验位
	 * @param expected 期望的校验位
	 * @param message  中文说明
	 * @return 校验不通过的结果
	 */
	public static ValidationResult fail(String rule, String actual, String expected, String message) {
		return new ValidationResult(rule, ValidationStatus.FAIL, actual, expected, message);
	}

	/**
	 * 构造"无法校验"结果。
	 *
	 * @param rule    规则编码
	 * @param message 中文说明
	 * @return 无法校验的结果
	 */
	public static ValidationResult uncheckable(String rule, String message) {
		return new ValidationResult(rule, ValidationStatus.UNCHECKABLE, null, null, message);
	}
}
