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

import lombok.experimental.UtilityClass;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Locale;

/**
 * 证件号校验位算法集合。
 *
 * <p><b>解决什么问题</b>：格式正则只能看出「长得像不像」，校验位能看出「数学上自不自洽」。
 * OCR 认错一个字符，格式校验照样通过，校验位逃不掉 —— 这是把「识别成功」升级为
 * 「<b>识别正确</b>」的唯一硬证据。
 *
 * <p><b>三套规则</b>：
 * <ul>
 *   <li>{@link #RULE_ID_CARD} —— GB 11643-1999 公民身份号码校验位（18 位）；</li>
 *   <li>{@link #RULE_BANK_CARD} —— Luhn 校验（ISO/IEC 7812，银行卡通用）；</li>
 *   <li>{@link #RULE_CREDIT_CODE} —— GB 32100-2015 统一社会信用代码校验位（18 位）。</li>
 * </ul>
 *
 * <p><b>15 位身份证号说明</b>：1999 年前签发的 15 位号码<b>没有校验位</b>
 * （结构为 6 位地址码 + YYMMDD + 3 位顺序码 + 1 位校验码，末位即校验位但规则不同），
 * 因此 {@link #validateIdCard(String)} 对 15 位号码返回
 * {@link ValidationStatus#UNCHECKABLE}，不误判为不通过。
 *
 * <p>本类为纯函数集合，<b>零第三方依赖、零 I/O、线程安全</b>。
 *
 * @see ValidationResult
 * @see FieldConfidence
 */
@UtilityClass
public class Validations {

	/**
	 * 规则编码：GB 11643-1999 公民身份号码。
	 */
	public static final String RULE_ID_CARD = "GB11643";

	/**
	 * 规则编码：Luhn 校验（银行卡）。
	 */
	public static final String RULE_BANK_CARD = "LUHN";

	/**
	 * 规则编码：GB 32100-2015 统一社会信用代码。
	 */
	public static final String RULE_CREDIT_CODE = "GB32100";

	/**
	 * GB 11643 加权因子（前 17 位）。
	 */
	private static final int[] ID_WEIGHTS = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};

	/**
	 * GB 11643 校验码表，下标为 {@code sum % 11}。
	 *
	 * <p>即：加权和对 11 取余，余 0→1、1→0、2→X、3→9 … 10→2。
	 *
	 * <p>⚠️ <b>不要写成 {@code (12 - sum % 11) % 11} 再查表</b> —— 那是把"校验码值"
	 * 当成了"表下标"，两者在多数余数上不一致，会把标准示例
	 * {@code 11010519491231002X}（余 2→X）误判成不通过。
	 */
	private static final char[] ID_CHECK_CODES = {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};

	/**
	 * GB 32100 字符集：数字 0-9 + 字母（剔除易混的 I / O / S / V / Z），共 31 个。
	 */
	private static final String CREDIT_CHARSET = "0123456789ABCDEFGHJKLMNPQRTUWXY";

	/**
	 * GB 32100 加权因子（前 17 位）。
	 */
	private static final int[] CREDIT_WEIGHTS = {1, 3, 9, 27, 19, 26, 16, 17, 20, 29, 25, 13, 8, 24, 10, 30, 28};

	/**
	 * 校验 18 位公民身份号码（GB 11643-1999）。
	 *
	 * <p>算法：前 17 位按权位加权求和，取 {@code sum % 11} 作校验码表下标，
	 * 查表得到期望校验位，与第 18 位比对。
	 *
	 * @param idNumber 身份证号（可含空白，末尾 X 大小写不敏感）
	 * @return 校验结果；15 位号码返回 {@link ValidationStatus#UNCHECKABLE}
	 */
	public static ValidationResult validateIdCard(String idNumber) {
		if (idNumber == null) {
			return ValidationResult.uncheckable(RULE_ID_CARD, "身份证号为空");
		}
		String s = idNumber.trim().toUpperCase(Locale.ROOT);
		if (s.length() == 15) {
			return ValidationResult.uncheckable(RULE_ID_CARD, "15 位身份证号无 GB 11643 校验位，无法校验");
		}
		if (s.length() != 18) {
			return ValidationResult.uncheckable(RULE_ID_CARD,
				"身份证号长度应为 18 位，实际 " + s.length() + " 位");
		}
		int sum = 0;
		for (int i = 0; i < 17; i++) {
			char c = s.charAt(i);
			if (c < '0' || c > '9') {
				return ValidationResult.uncheckable(RULE_ID_CARD,
					"身份证号前 17 位含非数字字符：" + c);
			}
			sum += (c - '0') * ID_WEIGHTS[i];
		}
		char expected = ID_CHECK_CODES[sum % 11];
		char actual = s.charAt(17);
		if (actual != 'X' && (actual < '0' || actual > '9')) {
			return ValidationResult.uncheckable(RULE_ID_CARD,
				"身份证号末位含非法字符：" + actual);
		}
		if (expected == actual) {
			return ValidationResult.pass(RULE_ID_CARD, "身份证号校验位通过");
		}
		return ValidationResult.fail(RULE_ID_CARD, String.valueOf(actual), String.valueOf(expected),
			"身份证号校验位不符：实际 " + actual + "，应为 " + expected + "（OCR 至少认错了一个字符）");
	}

	/**
	 * 校验银行卡号（Luhn 算法，ISO/IEC 7812）。
	 *
	 * <p>算法：从右往左逐位累加，奇数位（自右起第 1、3、5…位）先乘 2，
	 * 超过 9 则减 9（等价于「乘 2 后取个位与十位之和」），总和能被 10 整除即通过。
	 *
	 * <p><b>形态前置校验</b>：Luhn 对「各位数字相同」的串会误判为通过 ——
	 * 全零串（{@code 000000000000000000}）加权和恰为 0，{@code 0 % 10 == 0} 成立。
	 * 而真实卡号不可能所有位相同。这类串多为票据上的<b>占位符</b>或 OCR 把整串认成同一个数字，
	 * 属于「确定不是有效卡号」，故判 {@link ValidationStatus#FAIL}（而非 UNCHECKABLE）——
	 * 只有 FAIL 会让 {@link FieldConfidence} 把该字段置信度归零并置位人工复核。
	 *
	 * <p>卡号本身不携带校验位字符，故 {@code actual} / {@code expected} 均为 null。
	 *
	 * @param cardNumber 卡号（可含空格、短横线等分隔符，会被忽略）
	 * @return 校验结果
	 */
	public static ValidationResult validateBankCard(String cardNumber) {
		if (cardNumber == null) {
			return ValidationResult.uncheckable(RULE_BANK_CARD, "卡号为空");
		}
		String digits = cardNumber.replaceAll("[^0-9]", "");
		if (digits.length() < 12 || digits.length() > 19) {
			return ValidationResult.uncheckable(RULE_BANK_CARD,
				"卡号长度应为 12~19 位，实际 " + digits.length() + " 位");
		}
		// 形态校验：各位数字全同（如全零占位符）不是合法卡号。
		// Luhn 本身抓不到（0 的加权和恒为 0，必然"通过"），必须在此前置拦掉。
		if (isAllSameDigit(digits)) {
			return ValidationResult.fail(RULE_BANK_CARD, null, null,
				"卡号各位数字全为 " + digits.charAt(0) + "（共 " + digits.length()
					+ " 位），不是有效卡号（票据占位符或整串识别错误）");
		}
		int sum = 0;
		boolean doubleDigit = false;
		for (int i = digits.length() - 1; i >= 0; i--) {
			int d = digits.charAt(i) - '0';
			if (doubleDigit) {
				d *= 2;
				if (d > 9) {
					d -= 9;
				}
			}
			sum += d;
			doubleDigit = !doubleDigit;
		}
		if (sum % 10 == 0) {
			return ValidationResult.pass(RULE_BANK_CARD, "卡号 Luhn 校验通过");
		}
		return ValidationResult.fail(RULE_BANK_CARD, null, null,
			"卡号 Luhn 校验不通过（各位加权和不满足 mod 10 == 0，OCR 至少认错了一个数字）");
	}

	/**
	 * 判断数字串是否所有位都相同（覆盖全零占位符、全一同码等）。
	 *
	 * @param digits 纯数字串（长度 ≥ 1）
	 * @return true 表示所有位相同
	 */
	private static boolean isAllSameDigit(String digits) {
		if (digits == null || digits.length() <= 1) {
			return false;
		}
		char first = digits.charAt(0);
		for (int i = 1; i < digits.length(); i++) {
			if (digits.charAt(i) != first) {
				return false;
			}
		}
		return true;
	}

	/**
	 * 校验统一社会信用代码（GB 32100-2015）。
	 *
	 * <p>算法：前 17 位按 {@link #CREDIT_CHARSET} 映射为 0~30 的数值，
	 * 按权位加权求和后取 {@code check = 31 - (sum % 31)}（余 0 时为 0），
	 * 再映射回字符与第 18 位比对。
	 *
	 * @param creditCode 统一社会信用代码（自动转大写）
	 * @return 校验结果
	 */
	public static ValidationResult validateCreditCode(String creditCode) {
		if (creditCode == null) {
			return ValidationResult.uncheckable(RULE_CREDIT_CODE, "统一社会信用代码为空");
		}
		String s = creditCode.trim().toUpperCase(Locale.ROOT);
		if (s.length() != 18) {
			return ValidationResult.uncheckable(RULE_CREDIT_CODE,
				"统一社会信用代码长度应为 18 位，实际 " + s.length() + " 位");
		}
		int sum = 0;
		for (int i = 0; i < 17; i++) {
			int idx = CREDIT_CHARSET.indexOf(s.charAt(i));
			if (idx < 0) {
				return ValidationResult.uncheckable(RULE_CREDIT_CODE,
					"统一社会信用代码含非法字符：" + s.charAt(i));
			}
			sum += idx * CREDIT_WEIGHTS[i];
		}
		int check = 31 - sum % 31;
		if (check == 31) {
			check = 0;
		}
		char expected = CREDIT_CHARSET.charAt(check);
		char actual = s.charAt(17);
		if (expected == actual) {
			return ValidationResult.pass(RULE_CREDIT_CODE, "统一社会信用代码校验位通过");
		}
		return ValidationResult.fail(RULE_CREDIT_CODE, String.valueOf(actual), String.valueOf(expected),
			"统一社会信用代码校验位不符：实际 " + actual + "，应为 " + expected
				+ "（OCR 至少认错了一个字符）");
	}

	/**
	 * 校验 18 位公民身份号码是否通过 GB 11643 校验位。
	 *
	 * @param idNumber 身份证号
	 * @return true 表示格式合法且校验位自洽
	 */
	public static boolean isValidIdCard(String idNumber) {
		return validateIdCard(idNumber).valid();
	}

	/**
	 * 校验银行卡号是否通过 Luhn 校验。
	 *
	 * @param cardNumber 卡号
	 * @return true 表示 Luhn 校验通过
	 */
	public static boolean isValidBankCard(String cardNumber) {
		return validateBankCard(cardNumber).valid();
	}

	/**
	 * 校验统一社会信用代码是否通过 GB 32100 校验位。
	 *
	 * @param creditCode 统一社会信用代码
	 * @return true 表示格式合法且校验位自洽
	 */
	public static boolean isValidCreditCode(String creditCode) {
		return validateCreditCode(creditCode).valid();
	}

	/**
	 * 从公民身份号码回填性别。
	 *
	 * <p>身份证号的倒数第二位（18 位制，顺序码第 3 位）即性别位：
	 * <b>奇数为男、偶数为女</b>。15 位旧制号码的性别位在末位（倒数第 1 位），规则相同。
	 *
	 * <p>典型用途：OCR 把"性别"标签下的值糊掉时，号码本身仍能救回性别 ——
	 * 这比"性别缺失"有用得多。
	 *
	 * @param idNumber 15 位或 18 位身份证号
	 * @return "男" / "女"；号码长度不符或含非法字符时返回 null
	 */
	public static String genderFromIdNumber(String idNumber) {
		if (idNumber == null) {
			return null;
		}
		String s = idNumber.trim();
		int idx;
		if (s.length() == 18) {
			idx = 16;
		} else if (s.length() == 15) {
			idx = 14;
		} else {
			return null;
		}
		char c = s.charAt(idx);
		if (c < '0' || c > '9') {
			return null;
		}
		return (c - '0') % 2 == 1 ? "男" : "女";
	}

	/**
	 * 从公民身份号码回填出生日期（{@code yyyy-MM-dd}）。
	 *
	 * <p>18 位取第 7~14 位（YYYYMMDD）；15 位取第 7~12 位（YYMMDD，年份按 19YY 补全）。
	 *
	 * <p><b>日期合法性校验</b>：号码被 OCR 认错时，出生日期段可能拼出「13 月」「32 日」这类
	 * 非法日期。此处用 {@link LocalDate#of(int, int, int)} 校验月份范围、当月天数与闰年规则，
	 * 非法则返回 {@code null} —— <b>宁可不给日期，也不给一个假日期</b>。
	 * （与 {@link DateRepair} 的日期组装逻辑保持一致）
	 *
	 * @param idNumber 15 位或 18 位身份证号
	 * @return {@code yyyy-MM-dd}；号码长度不符或日期非法时返回 null
	 */
	public static String birthDateFromIdNumber(String idNumber) {
		if (idNumber == null) {
			return null;
		}
		String s = idNumber.trim();
		if (s.length() == 18) {
			return formatBirthDate(s.substring(6, 10), s.substring(10, 12), s.substring(12, 14));
		}
		if (s.length() == 15) {
			// 15 位 YY 按 19YY 补全（早期 15 位身份证号均为 19XX 年签发）
			return formatBirthDate("19" + s.substring(6, 8), s.substring(8, 10), s.substring(10, 12));
		}
		return null;
	}

	/**
	 * 组装并校验 {@code yyyy-MM-dd} 出生日期。
	 *
	 * <p>复用 {@link DateRepair} 的组装口径：{@link LocalDate#of(int, int, int)} 会校验
	 * 月份范围、当月天数与闰年规则，非法日期一律返回 {@code null}。
	 *
	 * @param year  年（4 位字符串）
	 * @param month 月（2 位字符串）
	 * @param day   日（2 位字符串）
	 * @return 合法则返回 {@code yyyy-MM-dd}，否则返回 null
	 */
	private static String formatBirthDate(String year, String month, String day) {
		int y = parseDigits(year);
		int m = parseDigits(month);
		int d = parseDigits(day);
		if (y < 0 || m < 0 || d < 0) {
			return null;
		}
		try {
			// LocalDate.of 会校验月份范围、当月天数与闰年规则
			LocalDate.of(y, m, d);
		} catch (DateTimeException e) {
			return null;
		}
		return String.format(Locale.ROOT, "%04d-%02d-%02d", y, m, d);
	}

	/**
	 * 解析数字字符串，非法返回 -1。
	 *
	 * @param s 数字字符串
	 * @return 数值；含非数字字符或为空时返回 -1
	 */
	private static int parseDigits(String s) {
		if (s == null || s.isEmpty() || s.length() > 4) {
			return -1;
		}
		int value = 0;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c < '0' || c > '9') {
				return -1;
			}
			value = value * 10 + (c - '0');
		}
		return value;
	}
}
