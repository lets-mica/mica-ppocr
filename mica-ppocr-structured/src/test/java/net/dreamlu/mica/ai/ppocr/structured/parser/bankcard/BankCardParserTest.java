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

import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Result;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.FieldConfidence;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.FieldSource;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.ParserTestSupport;
import net.dreamlu.mica.ai.ppocr.utils.CollUtil;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 银行卡解析器单元测试。
 *
 * <p>用构造的 OCR 框模拟典型版面（基于 bankcard1.png 工行金卡的实际 OCR 分布）。
 */
class BankCardParserTest extends ParserTestSupport {

	@Test
	void parse_icbcCreditGold() {
		// 模拟工行金卡 OCR 框（基于 bankcard1.png）
		// 图片约 800x500
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("ICBC", 50, 60, 130, 90),                    // 顶部英文
			box("中国工商银行", 160, 60, 350, 90),            // 顶部中文（发卡行）
			box("GOLD", 360, 60, 420, 90),
			box("CREDIT", 60, 130, 140, 160),                 // 卡片类型
			box("环球旅行卡 Global Travel", 200, 130, 460, 160),
			box("6225 9700 7000 3000", 90, 290, 700, 360),    // 卡号（带空格）
			box("6225", 90, 380, 130, 400),                   // 卡号小字
			box("月/年", 200, 380, 240, 400),                  // 有效期标签
			box("02", 240, 380, 270, 400),
			box("07/22", 290, 380, 360, 400),                 // 有效期值
			box("MR.CWENTA", 90, 430, 230, 460)              // 持卡人
		);
		BankCardResult r = parse(new BankCardParser(null), results);
		assertNotNull(r);
		assertEquals("6225970070003000", r.getCardNumber());
		assertEquals("07/22", r.getValidDate());
		assertEquals("MR. CWENTA", r.getHolderName());
		assertEquals("中国工商银行", r.getBankName());
		assertEquals("CREDIT", r.getCardType());
	}

	@Test
	void parse_huaxiaDebit() {
		// 模拟华夏借记卡（bankcard2.png）：VALID THRU / MONTH/YEAR 英文标签 + 中文发卡行
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("华夏银行", 160, 60, 300, 90),                // 发卡行
			box("HUAXIA BANK", 300, 60, 460, 90),
			box("京津冀协同卡", 500, 60, 720, 90),
			box("借记卡 DEBIT CARD", 220, 130, 460, 160),
			box("6230 2020 1852 1255", 90, 290, 700, 360),    // 卡号
			box("VALID THRU", 200, 380, 290, 400),            // 有效期标签（英文）
			box("MONTH/YEAR", 360, 380, 480, 400),
			box("12/27", 510, 380, 570, 400)
		);
		BankCardResult r = parse(new BankCardParser(null), results);
		assertNotNull(r);
		assertEquals("6230202018521255", r.getCardNumber());
		assertEquals("12/27", r.getValidDate());
		assertEquals("华夏银行", r.getBankName());
	}

	@Test
	void parse_ningbo19DigitCard() {
		// 模拟宁波银行借记卡（bankcard4.png）：19 位卡号无空格（首字符 OCR 误识别为 '6' 而非 'b'）
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("宁波银行", 160, 60, 300, 90),
			box("BANK OF NINGBO", 300, 60, 480, 90),
			box("汇通卡", 540, 60, 640, 90),
			box("借记卡/DEBIT", 280, 100, 460, 130),
			box("白领通", 540, 130, 660, 160),
			box("6214180701100538758", 90, 290, 700, 360),    // 19 位连续
			box("VALID THRU", 200, 380, 290, 400),
			box("MONTH/YEAR", 360, 380, 480, 400),
			box("01/25", 510, 380, 570, 400)
		);
		BankCardResult r = parse(new BankCardParser(null), results);
		assertNotNull(r);
		assertEquals("6214180701100538758", r.getCardNumber());
		assertEquals("01/25", r.getValidDate());
		assertEquals("宁波银行", r.getBankName());
	}

	@Test
	void parse_emptyResults() {
		BankCardResult r = parse(new BankCardParser(null), CollUtil.listOf());
		assertNotNull(r);
		// 所有字段为 null
		assertEquals(null, r.getCardNumber());
		assertEquals(null, r.getValidDate());
	}

	// ==================================================================
	// 正确性层：Luhn 校验 + 字段级置信度
	// ==================================================================

	/**
	 * 构造一张标准版式的银行卡 OCR 框。
	 *
	 * @param cardNumber 卡号（带 4-4-4-4 空格分隔）
	 * @return OCR 框列表
	 */
	private static List<PPOcrV6Result> buildCardOcr(String cardNumber) {
		return CollUtil.listOf(
			box("ICBC", 50, 60, 130, 90),
			box("中国工商银行", 160, 60, 350, 90),
			box("GOLD", 360, 60, 420, 90),
			box("CREDIT", 60, 130, 140, 160),
			box(cardNumber, 90, 290, 700, 360),
			box("月/年", 200, 380, 240, 400),
			box("07/22", 290, 380, 360, 400),
			box("MR.CWENTA", 90, 430, 230, 460)
		);
	}

	@Test
	void parse_cardNumberLuhnValid_confidenceKept() {
		// 4111111111111111 是公开的 Luhn 校验样例卡号
		BankCardResult r = parse(new BankCardParser(null), buildCardOcr("4111 1111 1111 1111"));
		assertEquals("4111111111111111", r.getCardNumber());
		FieldConfidence fc = r.getFieldConfidences().get("cardNumber");
		assertNotNull(fc);
		assertTrue(fc.score() > 0);
		assertTrue(fc.violations().isEmpty());
		assertFalse(r.isNeedsReview());
	}

	@Test
	void parse_cardNumberSource_isPatternNotLabel() {
		// 卡号是「内容正则」抽出来的（不是标签定位）⇒ 来源必须记 PATTERN，
		// 否则会白拿标签定位的置信度、虚高约 11%。
		BankCardResult r = parse(new BankCardParser(null), buildCardOcr("4111 1111 1111 1111"));
		FieldConfidence fc = r.getFieldConfidences().get("cardNumber");
		assertEquals(FieldSource.PATTERN, fc.source());
		assertEquals(0.9d, fc.score(), 1e-6, "PATTERN 路径应吃 0.9 折扣");
	}

	@Test
	void parse_cardNumberLuhnInvalid_scoreZeroAndNeedsReview() {		// 末位改成 2 → Luhn 不通过 ⇒ 至少认错了一个数字
		BankCardResult r = parse(new BankCardParser(null), buildCardOcr("4111 1111 1111 1112"));
		// 值仍要保留
		assertEquals("4111111111111112", r.getCardNumber());
		FieldConfidence fc = r.getFieldConfidences().get("cardNumber");
		assertNotNull(fc);
		assertEquals(0d, fc.score(), 1e-9);
		assertFalse(fc.violations().isEmpty());
		assertTrue(fc.needsReview());
		assertTrue(r.isNeedsReview());
	}

	@Test
	void parse_cardNumberAllZeroPlaceholder_scoreZeroAndNeedsReview() {
		// 全零占位符卡号：Luhn 加权和恒为 0，会被误判为"通过" ⇒ 置信度不归零、不标复核。
		// 必须在 Luhn 之前用形态校验拦掉（判 FAIL），否则下游会把这串占位符当成有效卡号。
		BankCardResult r = parse(new BankCardParser(null), buildCardOcr("0000 0000 0000 0000"));
		// 值仍然保留（丢值会让下游比现在更糟）
		assertEquals("0000000000000000", r.getCardNumber());
		FieldConfidence fc = r.getFieldConfidences().get("cardNumber");
		assertNotNull(fc);
		assertEquals(0d, fc.score(), 1e-9, "全零占位符卡号置信度必须归零");
		assertFalse(fc.violations().isEmpty());
		assertTrue(fc.needsReview());
		assertTrue(r.isNeedsReview());
	}
}
