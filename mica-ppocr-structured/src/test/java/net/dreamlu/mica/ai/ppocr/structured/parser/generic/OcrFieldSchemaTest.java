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

import net.dreamlu.mica.ai.ppocr.structured.parser.core.ValidationStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 声明式 schema、字段规则与归一化方式的单元测试。
 */
class OcrFieldSchemaTest {

	// ==================================================================
	// OcrFieldSchema
	// ==================================================================

	@Test
	void builder_keepsDocumentTypeAndFieldOrder() {
		OcrFieldSchema schema = OcrFieldSchema.builder("采购合同")
			.field("contractNo").labels("合同编号").add()
			.field("amount").labels("金额").add()
			.build();

		assertEquals("采购合同", schema.documentType());
		assertEquals(2, schema.fields().size());
		assertEquals("contractNo", schema.fields().get(0).name());
		assertEquals("amount", schema.fields().get(1).name());
	}

	@Test
	void builder_missingAdd_stillKeepsField() {
		OcrFieldSchema schema = OcrFieldSchema.builder("采购合同")
			.field("contractNo").labels("合同编号")
			.field("amount").labels("金额")
			.build();

		assertEquals(2, schema.fields().size());
	}

	@Test
	void builder_keepsAllLabelVariants() {
		OcrFieldSchema schema = OcrFieldSchema.builder("采购合同")
			.field("contractNo").labels("合同编号", "合同号", "合同号码").add()
			.build();

		FieldSpec spec = schema.fields().get(0);
		assertEquals(3, spec.labels().size());
		assertEquals("合同编号", spec.labels().get(0));
	}

	@Test
	void builder_fieldWithoutLabels_usesRegexPathOnly() {
		OcrFieldSchema schema = OcrFieldSchema.builder("订单")
			.field("orderNo").regex("\\d{12}").add()
			.build();

		FieldSpec spec = schema.fields().get(0);
		assertTrue(spec.labels().isEmpty());
		assertNotNull(spec.regex());
	}

	@Test
	void builder_fieldDefaultsToRequiredAndNormalizerNone() {
		OcrFieldSchema schema = OcrFieldSchema.builder("采购合同")
			.field("contractNo").labels("合同编号").add()
			.build();

		FieldSpec spec = schema.fields().get(0);
		assertFalse(spec.optional());
		assertEquals(ValueNormalizer.NONE, spec.normalizer());
	}

	@Test
	void schema_isImmutable_fieldListUnmodifiable() {
		OcrFieldSchema schema = OcrFieldSchema.builder("采购合同")
			.field("contractNo").labels("合同编号").add()
			.build();

		assertThrows(UnsupportedOperationException.class,
			() -> schema.fields().add(schema.fields().get(0)));
	}

	@Test
	void builder_blankDocumentType_throws() {
		assertThrows(IllegalArgumentException.class, () -> OcrFieldSchema.builder(""));
		assertThrows(IllegalArgumentException.class, () -> OcrFieldSchema.builder(null));
	}

	@Test
	void builder_blankFieldName_throws() {
		assertThrows(IllegalArgumentException.class,
			() -> OcrFieldSchema.builder("采购合同").field("").add().build());
	}

	// ==================================================================
	// FieldRule
	// ==================================================================

	@Test
	void rule_digits_checksFixedLengthDigits() {
		FieldRule rule = FieldRule.digits(6);
		assertTrue(rule.check("123456").valid());
		assertEquals(ValidationStatus.FAIL, rule.check("12345").status());
		assertEquals(ValidationStatus.FAIL, rule.check("12345a").status());
	}

	@Test
	void rule_length_checksRange() {
		FieldRule rule = FieldRule.length(2, 4);
		assertTrue(rule.check("ab").valid());
		assertTrue(rule.check("abcd").valid());
		assertEquals(ValidationStatus.FAIL, rule.check("a").status());
		assertEquals(ValidationStatus.FAIL, rule.check("abcde").status());
	}

	@Test
	void rule_date_requiresRealCalendarDate() {
		FieldRule rule = FieldRule.date();
		assertTrue(rule.check("2024-02-29").valid());
		assertEquals(ValidationStatus.FAIL, rule.check("2023-02-29").status(), "平年2月29日不存在");
		assertEquals(ValidationStatus.FAIL, rule.check("2024-13-01").status(), "月份13不存在");
		assertEquals(ValidationStatus.FAIL, rule.check("2024/01/01").status());
	}

	@Test
	void rule_idCard_usesGb11643TriState() {
		FieldRule rule = FieldRule.idCardNumber();
		assertTrue(rule.check("11010519491231002X").valid());
		assertTrue(rule.check("110105194912310021").mismatched(), "校验位不符是硬证据");
		assertFalse(rule.check("1101051949123").mismatched(), "长度不对不能断定认错");
	}

	@Test
	void rule_bankCard_usesLuhn() {
		FieldRule rule = FieldRule.bankCardNumber();
		assertTrue(rule.check("4111111111111111").valid());
		assertTrue(rule.check("4111111111111112").mismatched(), "校验位不符是硬证据");
		assertFalse(rule.check("411111").mismatched(), "长度不对不能断定认错");
	}

	@Test
	void rule_customPredicate() {
		FieldRule rule = FieldRule.of("金额", v -> v.matches("^[0-9]+(\\.[0-9]{1,2})?$"));
		assertTrue(rule.check("1234.56").valid());
		assertTrue(rule.check("abc").mismatched());
		assertEquals("金额", rule.name());
	}

	@Test
	void rule_blankNameOrNullChecker_throws() {
		assertThrows(IllegalArgumentException.class, () -> FieldRule.of("", v -> true));
		assertThrows(IllegalArgumentException.class, () -> FieldRule.of("x", null));
		assertThrows(IllegalArgumentException.class, () -> FieldRule.digits(0));
		assertThrows(IllegalArgumentException.class, () -> FieldRule.length(5, 2));
	}

	// ==================================================================
	// ValueNormalizer
	// ==================================================================

	@Test
	void normalizer_none_returnsInputUnchanged() {
		assertEquals(" HT-2024 ", ValueNormalizer.NONE.apply(" HT-2024 "));
		assertNull(ValueNormalizer.NONE.apply(null));
	}

	@Test
	void normalizer_digits_keepsDigitsOnly() {
		assertEquals("2024001", ValueNormalizer.DIGITS.apply("HT-2024-001"));
		assertEquals("", ValueNormalizer.DIGITS.apply("abc"));
	}

	@Test
	void normalizer_compact_keepsLettersAndCjk() {
		assertEquals("HT2024001", ValueNormalizer.COMPACT.apply("HT-2024-001"));
		assertEquals("11010519491231002X", ValueNormalizer.COMPACT.apply("110105 19491231002X"));
		assertEquals("北京市海淀区", ValueNormalizer.COMPACT.apply("北京市 海淀区"));
	}

	@Test
	void normalizer_date_repairsSeparatorAndKeepsIllegalDate() {
		assertEquals("2017-05-08", ValueNormalizer.DATE.apply("2017年5月8日"));
		assertEquals("2017-05-08", ValueNormalizer.DATE.apply("2017-05-08"));
		assertEquals("2017-05-08", ValueNormalizer.DATE.apply("20170508"), "纯数字 8 位也会还原成标准形态");
		assertEquals("2017-13-45", ValueNormalizer.DATE.apply("2017-13-45"), "非法日期宁可不修");
	}
}
