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

package net.dreamlu.mica.ai.ppocr.structured.parser.invoice;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link UpperMoneyConverter} 单元测试。
 */
class UpperMoneyConverterTest {

	@Test
	void plainInteger() {
		assertEquals("59976.00",
			UpperMoneyConverter.toLower("伍万玖仟玖佰柒拾陆圆整"));
	}

	@Test
	void withJiaoFen() {
		assertEquals("21.79", UpperMoneyConverter.toLower("贰拾壹圆柒角玖分"));
	}

	@Test
	void smallAmount() {
		assertEquals("139.00", UpperMoneyConverter.toLower("壹佰叁拾玖圆整"));
	}

	@Test
	void jiaoOnly() {
		assertEquals("6132.50", UpperMoneyConverter.toLower("陆仟壹佰叁拾贰圆伍角整"));
	}

	@Test
	void zero() {
		assertEquals("0.00", UpperMoneyConverter.toLower("零圆整"));
	}

	@Test
	void qiPrefix() {
		// "染" 在发票 OCR 中常出现；保证不被误识成其它字
		assertEquals("2.00", UpperMoneyConverter.toLower("贰圆整"));
	}

	@Test
	void noYuan() {
		// 兜底：整段为整数（已极少出现）
		assertEquals("100.00", UpperMoneyConverter.toLower("壹佰"));
	}

	@Test
	void withPrefixNoise() {
		// OCR 可能输出 "⊙伍万玖仟玖佰染拾陆圆整"
		assertEquals("59976.00",
			UpperMoneyConverter.toLower("⊙伍万玖仟玖佰柒拾陆圆整"));
	}

	@Test
	void nullOrEmptyReturnsNull() {
		assertNull(UpperMoneyConverter.toLower(null));
		assertNull(UpperMoneyConverter.toLower(""));
		assertNull(UpperMoneyConverter.toLower("⊙"));
	}

	@Test
	void wanOnlyZero() {
		// 0万 + 5
		assertEquals("5.00", UpperMoneyConverter.toLower("零万伍圆整"));
	}
}