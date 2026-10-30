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

import net.dreamlu.mica.ai.ppocr.structured.parser.core.ValidationResult;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.Validations;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * 字段校验规则。
 *
 * <p>声明式 schema 里对某个字段的"形状要求"。规则命中与否以
 * {@link ValidationResult} 表达，沿用阶段二统一的三态语义：
 * <ul>
 *   <li>{@code PASS} —— 通过；</li>
 *   <li>{@code FAIL} —— <b>唯一</b>的硬证据：值确定不符合要求，记违规、置信度归零；</li>
 *   <li>{@code UNCHECKABLE} —— "长得不像"但不能断定认错字符（如身份证长度不对），
 *       <b>不</b>记违规。</li>
 * </ul>
 *
 * <p>身份证号 / 银行卡号 / 统一社会信用代码三类规则直接委托给
 * {@link Validations}，与存量 10 个强类型解析器共用同一套校验位实现，
 * 不存在两套口径。
 *
 * @see ExtractionSchema.FieldBuilder#rules(FieldRule...)
 */
public class FieldRule {

	/**
	 * 规范日期形态（归一化之后）。
	 */
	private static final Pattern DATE_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

	/**
	 * 规则名（用于违规说明）。
	 */
	private final String name;

	/**
	 * 校验函数：输入字段值，输出校验结论。
	 */
	private final Function<String, ValidationResult> checker;

	/**
	 * 构造校验规则。
	 *
	 * @param name    规则名（用于违规说明）
	 * @param checker 校验函数，不可为 null
	 */
	public FieldRule(String name, Function<String, ValidationResult> checker) {
		if (name == null || name.isEmpty()) {
			throw new IllegalArgumentException("rule name must not be empty");
		}
		if (checker == null) {
			throw new IllegalArgumentException("checker must not be null");
		}
		this.name = name;
		this.checker = checker;
	}

	/**
	 * 规则名。
	 *
	 * @return 规则名
	 */
	public String name() {
		return name;
	}

	/**
	 * 校验函数。
	 *
	 * @return 校验函数
	 */
	public Function<String, ValidationResult> checker() {
		return checker;
	}

	/**
	 * 执行校验。
	 *
	 * @param value 字段值
	 * @return 校验结论
	 */
	public ValidationResult check(String value) {
		return checker.apply(value);
	}

	// ==================================================================
	// 内置规则
	// ==================================================================

	/**
	 * 纯数字定长规则（如 18 位身份证号、13 位订单号）。
	 *
	 * @param length 期望长度
	 * @return 校验规则
	 */
	public static FieldRule digits(int length) {
		if (length <= 0) {
			throw new IllegalArgumentException("length must be positive");
		}
		final Pattern pattern = Pattern.compile("\\d{" + length + "}");
		final String expected = "\\d{" + length + "}";
		return new FieldRule("DIGITS", value -> pattern.matcher(value).matches()
			? ValidationResult.pass("DIGITS", "纯数字 " + length + " 位")
			: ValidationResult.fail("DIGITS", value, expected, "应为 " + length + " 位纯数字"));
	}

	/**
	 * 长度范围规则。
	 *
	 * @param min 最小长度（含）
	 * @param max 最大长度（含）
	 * @return 校验规则
	 */
	public static FieldRule length(int min, int max) {
		if (min < 0 || max < min) {
			throw new IllegalArgumentException("invalid length range: [" + min + ", " + max + "]");
		}
		return new FieldRule("LENGTH", value -> {
			int len = value.length();
			return (len >= min && len <= max)
				? ValidationResult.pass("LENGTH", "长度 " + len + " 在 [" + min + ", " + max + "] 内")
				: ValidationResult.fail("LENGTH", String.valueOf(len), "[" + min + ", " + max + "]",
				"长度超出范围");
		});
	}

	/**
	 * 日期规则：要求 {@code yyyy-MM-dd} 且为真实存在的日期。
	 *
	 * <p>月份 13、2 月 30 日这类"格式对但日期不存在"的值判定为 FAIL。
	 *
	 * @return 校验规则
	 */
	public static FieldRule date() {
		return new FieldRule("DATE", value -> {
			if (!DATE_PATTERN.matcher(value).matches()) {
				return ValidationResult.fail("DATE", value, "yyyy-MM-dd", "日期格式应为 yyyy-MM-dd");
			}
			try {
				LocalDate.of(Integer.parseInt(value.substring(0, 4)),
					Integer.parseInt(value.substring(5, 7)),
					Integer.parseInt(value.substring(8, 10)));
			} catch (DateTimeException | NumberFormatException e) {
				return ValidationResult.fail("DATE", value, "合法日期", "日期不存在");
			}
			return ValidationResult.pass("DATE", "合法日期");
		});
	}

	/**
	 * 身份证号规则（GB 11643 校验位）。
	 *
	 * <p>直接委托 {@link Validations#validateIdCard(String)}，三态语义与存量解析器一致。
	 *
	 * @return 校验规则
	 */
	public static FieldRule idCardNumber() {
		return new FieldRule(Validations.RULE_ID_CARD, Validations::validateIdCard);
	}

	/**
	 * 银行卡号规则（Luhn 校验位）。
	 *
	 * @return 校验规则
	 */
	public static FieldRule bankCardNumber() {
		return new FieldRule(Validations.RULE_BANK_CARD, Validations::validateBankCard);
	}

	/**
	 * 统一社会信用代码规则（GB 32100 校验位）。
	 *
	 * @return 校验规则
	 */
	public static FieldRule creditCode() {
		return new FieldRule(Validations.RULE_CREDIT_CODE, Validations::validateCreditCode);
	}

	/**
	 * 自定义规则。
	 *
	 * @param name    规则名（用于违规说明）
	 * @param checker 判定谓词；返回 true 表示通过
	 * @return 校验规则
	 */
	public static FieldRule of(String name, Predicate<String> checker) {
		if (checker == null) {
			throw new IllegalArgumentException("checker must not be null");
		}
		return new FieldRule(name, value -> checker.test(value)
			? ValidationResult.pass(name, "符合规则 " + name)
			: ValidationResult.fail(name, value, name, "不符合规则 " + name));
	}
}
