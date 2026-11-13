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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link ValueNormalizer} 单元测试。
 *
 * <p>重点锁定 {@link ValueNormalizer#DATE} 的<b>前置形态闸门</b>：
 * 长数字串（身份证号 / 银行卡号）不得被滑窗路径修成假日期。
 */
class ValueNormalizerTest {

	@Test
	void apply_DATE_pureEightDigits_converted() {
		assertEquals("2015-03-05", ValueNormalizer.DATE.apply("20150305"));
	}

	@Test
	void apply_DATE_chineseFormat_converted() {
		assertEquals("2015-03-05", ValueNormalizer.DATE.apply("2015年3月5日"));
	}

	@Test
	void apply_DATE_separatorFormat_converted() {
		assertEquals("2015-03-05", ValueNormalizer.DATE.apply("2015-03-05"));
	}

	/**
	 * 18 位身份证号必须原样保留。
	 *
	 * <p>{@code DateRepair} 的滑窗路径（数字 &gt; 8 位时取前 8 位）会把它修成
	 * {@code "1101-05-19"} —— 恰好是合法日期；而归一化发生在校验之前，
	 * 这个假日期还能通过 {@code \d{4}-\d{2}-\d{2}} 形态校验 ⇒ 静默错误。
	 */
	@Test
	void apply_DATE_longIdNumber_keptAsIs() {
		String idNo = "11010519491231002X";
		assertEquals(idNo, ValueNormalizer.DATE.apply(idNo));
	}

	@Test
	void apply_DATE_longCardNumber_keptAsIs() {
		String cardNo = "6225970070003000";
		assertEquals(cardNo, ValueNormalizer.DATE.apply(cardNo));
	}

	@Test
	void apply_DATE_illegalDate_keptAsIs() {
		assertEquals("2015-13-45", ValueNormalizer.DATE.apply("2015-13-45"));
	}

	@Test
	void apply_DATE_null_returnsNull() {
		assertNull(ValueNormalizer.DATE.apply(null));
	}

	@Test
	void apply_COMPACT_keepsTrailingX() {
		assertEquals("11010519491231002X", ValueNormalizer.COMPACT.apply("110105 19491231002X"));
	}

	@Test
	void apply_DIGITS_dropsTrailingX() {
		assertEquals("11010519491231002", ValueNormalizer.DIGITS.apply("11010519491231002X"));
	}
}
