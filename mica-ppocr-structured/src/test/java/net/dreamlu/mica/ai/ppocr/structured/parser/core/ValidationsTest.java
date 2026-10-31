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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 校验位算法单元测试（GB 11643 / GB 32100 / Luhn）与证件号回填测试。
 *
 * <p>测试数据均为公开标准示例或合成占位号码，不含任何真实个人信息。
 */
class ValidationsTest {

	// ==================================================================
	// GB 11643 公民身份号码
	// ==================================================================

	@Test
	void gb11643_standardSample_passes() {
		// GB 11643-1999 标准示例：前 17 位加权和 167，167 % 11 = 2 → 校验码 'X'
		ValidationResult r = Validations.validateIdCard("11010519491231002X");
		assertTrue(r.valid());
		assertEquals(ValidationStatus.PASS, r.status());
		assertFalse(r.mismatched());
		assertEquals(Validations.RULE_ID_CARD, r.rule());
	}

	@Test
	void gb11643_checksumMismatch_fails() {
		ValidationResult r = Validations.validateIdCard("110105194912310021");
		assertFalse(r.valid());
		assertTrue(r.mismatched());
		assertEquals("X", r.expected());
		assertEquals("1", r.actual());
	}

	@Test
	void gb11643_anotherStandardSample_passes() {
		// 加权和 195，195 % 11 = 8 → 校验码 '4'
		assertTrue(Validations.isValidIdCard("440524188001010014"));
	}

	@Test
	void gb11643_lowercaseXSuffix_alsoPasses() {
		assertTrue(Validations.isValidIdCard("11010519491231002x"));
	}

	@Test
	void gb11643_legacy15Digit_uncheckableNotMismatch() {
		// 15 位旧证无 GB 11643 校验位，只能"无法校验"，不能误判成"不通过"
		ValidationResult r = Validations.validateIdCard("110105491231002");
		assertEquals(ValidationStatus.UNCHECKABLE, r.status());
		assertFalse(r.mismatched());
	}

	@Test
	void gb11643_illegalLengthOrCharset_uncheckable() {
		assertEquals(ValidationStatus.UNCHECKABLE, Validations.validateIdCard("123").status());
		assertEquals(ValidationStatus.UNCHECKABLE, Validations.validateIdCard(null).status());
		assertEquals(ValidationStatus.UNCHECKABLE,
			Validations.validateIdCard("11010519491231002A").status());
	}

	// ==================================================================
	// GB 32100 统一社会信用代码
	// ==================================================================

	@Test
	void gb32100_knownCode_passes() {
		// 前 17 位加权和 1640，1640 % 31 = 28 → 校验位 31 - 28 = 3
		ValidationResult r = Validations.validateCreditCode("91350100M000100Y43");
		assertTrue(r.valid());
		assertEquals(ValidationStatus.PASS, r.status());
		assertEquals(Validations.RULE_CREDIT_CODE, r.rule());
	}

	@Test
	void gb32100_checksumMismatch_fails() {
		ValidationResult r = Validations.validateCreditCode("91350100M000100Y44");
		assertFalse(r.valid());
		assertTrue(r.mismatched());
		assertEquals("3", r.expected());
		assertEquals("4", r.actual());
	}

	@Test
	void gb32100_wrongLength_uncheckable() {
		assertEquals(ValidationStatus.UNCHECKABLE, Validations.validateCreditCode("91350100M000100Y4").status());
		assertEquals(ValidationStatus.UNCHECKABLE, Validations.validateCreditCode(null).status());
	}

	@Test
	void gb32100_charsetViolation_uncheckable() {
		// GB 32100 字符集剔除 I / O / S / V / Z，含这些字符的串不是合法信用代码
		assertEquals(ValidationStatus.UNCHECKABLE,
			Validations.validateCreditCode("91310116S11653529C").status());
	}

	// ==================================================================
	// Luhn 银行卡
	// ==================================================================

	@Test
	void luhn_knownValidCard_passes() {
		assertTrue(Validations.isValidBankCard("4111111111111111"));
	}

	@Test
	void luhn_wrongChecksum_fails() {
		ValidationResult r = Validations.validateBankCard("4111111111111112");
		assertFalse(r.valid());
		assertTrue(r.mismatched());
	}

	@Test
	void luhn_separatedBySpaces_stillValidates() {
		assertTrue(Validations.isValidBankCard("4111 1111 1111 1111"));
		assertTrue(Validations.isValidBankCard("4111-1111-1111-1111"));
	}

	@Test
	void luhn_illegalLength_uncheckable() {
		assertEquals(ValidationStatus.UNCHECKABLE, Validations.validateBankCard("411111").status());
		assertEquals(ValidationStatus.UNCHECKABLE, Validations.validateBankCard(null).status());
	}

	@Test
	void luhn_allZeroPlaceholder_failsInsteadOfPassing() {
		// 全零串的 Luhn 加权和恒为 0，0 % 10 == 0 会被误判为"通过"；
		// 但真实卡号不可能所有位相同 —— 必须在 Luhn 之前用形态校验拦掉。
		// 判 FAIL（而非 UNCHECKABLE）才能让 FieldConfidence 把置信度归零并置位人工复核。
		ValidationResult r = Validations.validateBankCard("000000000000000000");
		assertFalse(r.valid());
		assertTrue(r.mismatched());
		assertEquals(ValidationStatus.FAIL, r.status());
		assertEquals(Validations.RULE_BANK_CARD, r.rule());
	}

	@Test
	void luhn_allSameDigit_failsInsteadOfPassing() {
		// 同理：全一同码、全二同码等占位形态一律判 FAIL
		assertTrue(Validations.validateBankCard("1111111111111111").mismatched());
		assertTrue(Validations.validateBankCard("0000000000000000").mismatched());
		assertTrue(Validations.validateBankCard("0000000000000000000").mismatched());
	}

	@Test
	void luhn_normalCardWithMixedDigits_stillPasses() {
		// 形态校验不能误伤正常卡号：含不同数字且 Luhn 成立的卡号仍判 PASS
		assertTrue(Validations.isValidBankCard("4111111111111111"));
		assertTrue(Validations.isValidBankCard("4539578763621486"));
		// 含不同数字但 Luhn 不成立的，仍按 Luhn 判 FAIL（形态校验不干预）
		assertTrue(Validations.validateBankCard("6222021234567890123").mismatched());
	}

	// ==================================================================
	// 证件号回填
	// ==================================================================

	@Test
	void genderFromId_18Digit_oddMaleEvenFemale() {
		// 第 17 位为性别位：奇数男、偶数女
		assertEquals("女", Validations.genderFromIdNumber("11010519491231002X"));
		assertEquals("男", Validations.genderFromIdNumber("440524188001010014"));
	}

	@Test
	void genderFromId_15Digit_lastDigitIsGender() {
		assertEquals("女", Validations.genderFromIdNumber("110105491231002"));
		assertEquals("男", Validations.genderFromIdNumber("110105491231001"));
	}

	@Test
	void genderFromId_illegalNumber_returnsNull() {
		assertNull(Validations.genderFromIdNumber(null));
		assertNull(Validations.genderFromIdNumber("123"));
		assertNull(Validations.genderFromIdNumber("1101051949123100AX"));
	}

	@Test
	void birthDateFromId_18And15Digit() {
		assertEquals("1949-12-31", Validations.birthDateFromIdNumber("11010519491231002X"));
		// 15 位 YY 按 19YY 补全
		assertEquals("1949-12-31", Validations.birthDateFromIdNumber("110105491231002"));
		assertNull(Validations.birthDateFromIdNumber("123"));
	}

	@Test
	void birthDateFromId_illegalDateInNumber_returnsNull() {
		// OCR 把月份认成 13、日认成 32 —— 拼出来的是非法日期，不能当成真实出生日期输出
		assertNull(Validations.birthDateFromIdNumber("11010519491331002X"));
		assertNull(Validations.birthDateFromIdNumber("11010519491232002X"));
		// 2 月 30 日不存在（非闰年）
		assertNull(Validations.birthDateFromIdNumber("11010519490230002X"));
		// 15 位同样受检
		assertNull(Validations.birthDateFromIdNumber("110105491331002"));
	}

	@Test
	void birthDateFromId_leapDay_accepted() {
		// 2000-02-29 是闰日，应被接受（防止校验过严误伤）
		assertEquals("2000-02-29", Validations.birthDateFromIdNumber("11010520000229002X"));
		// 1900 年不是闰年（能被 100 整除但不能被 400 整除）
		assertNull(Validations.birthDateFromIdNumber("11010519000229002X"));
	}
}
