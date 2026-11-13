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
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 金额货币符号归一化单元测试。
 *
 * <p>票面字形随票据版本而变：老版蓝票用全角 {@code ￥}（U+FFE5），新版电子票用半角
 * {@code ¥}（U+00A5）。rec 模型忠实照抄，于是同一个金额字段在不同单据上会输出两种符号，
 * 下游做 {@code startsWith("￥")} 或字符串等值比对时会漏掉一半样本 —— 解析层统一成半角。
 */
class MoneyUtilTest {

	@Test
	void normalizeYuanPrefix_fullWidthYuan_convertedToHalfWidth() {
		// 老版蓝票：全角 ￥
		assertEquals("¥26.00元", MoneyUtil.normalizeYuanPrefix("￥26.00元"));
		assertEquals("¥194.50元", MoneyUtil.normalizeYuanPrefix("￥194.50元"));
	}

	@Test
	void normalizeYuanPrefix_halfWidthYuan_unchanged() {
		// 新版电子票：半角 ¥，原样返回
		assertEquals("¥280.5元", MoneyUtil.normalizeYuanPrefix("¥280.5元"));
		assertEquals("¥26.00", MoneyUtil.normalizeYuanPrefix("¥26.00"));
	}

	@Test
	void normalizeYuanPrefix_ocrMisreadQuestionMark_restoredToYuan() {
		// OCR 把 ¥ 认成 ?（发票「价税合计（小写）」常见）
		assertEquals("¥280.5元", MoneyUtil.normalizeYuanPrefix("?280.5元"));
		assertEquals("¥99.42", MoneyUtil.normalizeYuanPrefix("?99.42"));
	}

	@Test
	void normalizeYuanPrefix_plainNumber_unchanged() {
		// 出租车票的金额是纯数字（无货币符），不该被塞进符号
		assertEquals("40.60", MoneyUtil.normalizeYuanPrefix("40.60"));
		assertEquals("0.00", MoneyUtil.normalizeYuanPrefix("0.00"));
	}

	@Test
	void normalizeYuanPrefix_symbolFollowedBySpace_spaceTrimmed() {
		// OCR 常在符号与数字之间留空格
		assertEquals("¥26.00", MoneyUtil.normalizeYuanPrefix("￥ 26.00"));
	}

	@Test
	void normalizeYuanPrefix_nullOrEmpty_returnedAsIs() {
		assertNull(MoneyUtil.normalizeYuanPrefix(null));
		assertEquals("", MoneyUtil.normalizeYuanPrefix(""));
	}

	@Test
	void yuanConstant_isHalfWidthYen() {
		// 全仓库金额字段的唯一货币符号口径
		assertEquals("¥", MoneyUtil.YUAN);
		assertEquals(1, MoneyUtil.YUAN.length());
	}
}
