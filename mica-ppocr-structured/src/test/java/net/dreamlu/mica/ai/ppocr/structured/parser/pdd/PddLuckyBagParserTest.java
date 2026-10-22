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

package net.dreamlu.mica.ai.ppocr.structured.parser.pdd;

import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Result;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.ParserTestSupport;
import net.dreamlu.mica.ai.ppocr.utils.CollUtil;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 拼多多福袋解析器单元测试。
 *
 * <p>基于 pdd1.jpg 的典型 OCR 分布（图片约 1080x1530）做 mock。
 *
 * <p>覆盖场景：邀请码提取（标签定位 / 合并框 / 形态兜底 / 漏字）+
 * 福袋类型判定（福袋券 / 水果券 / 仅水印兜底 / 无法判定）。
 */
class PddLuckyBagParserTest extends ParserTestSupport {

	/**
	 * 典型版面：标签"2 搜索以下数字邀请码"与福袋码 "92463725" 独立成框，
	 * 同时包含步骤序号 "1"/"2"、水印 "百亿补贴福袋专享"、折扣 "5折券"。
	 */
	@Test
	void parse_typicalLayout() {
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("百亿补贴", 280, 30, 460, 80),
			box("抽福袋", 510, 30, 660, 80),
			box("搜索邀请码", 250, 160, 740, 230),
			box("组队双方 必得现金或券", 60, 280, 1020, 360),
			box("完成2步，可与我组队一起抽现金或5折券", 140, 410, 940, 460),
			box("1", 230, 540, 290, 600),
			box("打开拼多多APP", 320, 540, 750, 600),
			box("2", 230, 660, 290, 720),
			box("搜索以下数字邀请码", 320, 660, 760, 720),
			box("92463725", 270, 880, 830, 1020),    // 大字号 8 位福袋码
			box("百亿补贴", 110, 940, 230, 980),     // 背景水印
			box("福袋专享", 110, 990, 230, 1030)
		);
		PddLuckyBagResult r = parse(new PddLuckyBagParser(null), results);
		assertNotNull(r);
		assertEquals("92463725", r.getLuckyBagCode());
		assertEquals(PddLuckyBagType.LUCKY_BAG, r.getType());
	}

	/**
	 * 合并框场景：标签 "搜索以下数字邀请码" 与福袋码被 OCR 识别成同一框
	 * "2 搜索以下数字邀请码 92463725"，需按 prefix 剥出 8 位数字。
	 */
	@Test
	void parse_mergedLabel() {
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("2 搜索以下数字邀请码 92463725", 230, 660, 880, 1020),
			box("1 打开拼多多APP", 230, 540, 750, 600)
		);
		PddLuckyBagResult r = parse(new PddLuckyBagParser(null), results);
		assertNotNull(r);
		assertEquals("92463725", r.getLuckyBagCode());
	}

	/**
	 * 形态兜底场景：没有 "数字邀请码" 标签，只有大字号 8 位纯数字框。
	 * 应按"纯数字 + 面积最大 + y 偏下半"打分命中。
	 */
	@Test
	void parse_fallbackByShape() {
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("百亿补贴", 280, 30, 460, 80),
			box("抽福袋", 510, 30, 660, 80),
			box("92463725", 270, 880, 830, 1020),
			box("百亿补贴福袋专享", 80, 940, 360, 1020)   // 合并的水印
		);
		PddLuckyBagResult r = parse(new PddLuckyBagParser(null), results);
		assertNotNull(r);
		assertEquals("92463725", r.getLuckyBagCode());
	}

	/**
	 * 7 位数字（OCR 漏 1 位）也应能识别，容错到 6~12 位。
	 */
	@Test
	void parse_sevenDigits() {
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("搜索以下数字邀请码", 230, 660, 760, 720),
			box("9246372", 270, 880, 830, 1020)    // OCR 漏 1 位
		);
		PddLuckyBagResult r = parse(new PddLuckyBagParser(null), results);
		assertNotNull(r);
		assertEquals("9246372", r.getLuckyBagCode());
	}

	/**
	 * 空结果：所有字段为 null。
	 */
	@Test
	void parse_emptyResults() {
		PddLuckyBagResult r = parse(new PddLuckyBagParser(null), CollUtil.listOf());
		assertNotNull(r);
		assertEquals(null, r.getLuckyBagCode());
		assertEquals(PddLuckyBagType.UNKNOWN, r.getType());
	}

	/**
	 * 水果券版面：标题「水果分享券」、口号「必得水果券」、水印「水果专享」，
	 * 与福袋券版式一致，仅文案不同，类型应判为 FRUIT。
	 */
	@Test
	void parse_fruitType() {
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("百亿补贴", 280, 30, 460, 80),
			box("水果分享券", 500, 30, 800, 80),
			box("搜索邀请码", 250, 160, 740, 230),
			box("组队双方 必得水果券", 60, 280, 1020, 360),
			box("完成2步，可与我组队一起抽水果券", 140, 410, 940, 460),
			box("1", 230, 540, 290, 600),
			box("打开拼多多APP", 320, 540, 750, 600),
			box("2", 230, 660, 290, 720),
			box("搜索以下邀请码", 320, 660, 760, 720),
			box("214353161", 270, 880, 830, 1020),
			box("百亿补贴", 110, 940, 230, 980),
			box("水果专享", 110, 990, 230, 1030)
		);
		PddLuckyBagResult r = parse(new PddLuckyBagParser(null), results);
		assertNotNull(r);
		assertEquals("214353161", r.getLuckyBagCode());
		assertEquals(PddLuckyBagType.FRUIT, r.getType());
	}

	/**
	 * 水果券版面的合并框场景：标题与「百亿补贴」合并成同一框，类型仍应判为 FRUIT。
	 */
	@Test
	void parse_fruitTypeMergedTitle() {
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("百亿补贴 水果分享券", 280, 30, 800, 80),
			box("组队双方 必得水果券", 60, 280, 1020, 360),
			box("2 搜索以下邀请码 214353161", 230, 660, 880, 1020)
		);
		PddLuckyBagResult r = parse(new PddLuckyBagParser(null), results);
		assertNotNull(r);
		assertEquals("214353161", r.getLuckyBagCode());
		assertEquals(PddLuckyBagType.FRUIT, r.getType());
	}

	/**
	 * 只识别出水印（标题 / 口号缺失）时，靠水印文案兜底判定类型。
	 */
	@Test
	void parse_typeByWatermarkOnly() {
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("214353161", 270, 880, 830, 1020),
			box("百亿补贴 水果专享", 110, 940, 360, 1030)
		);
		PddLuckyBagResult r = parse(new PddLuckyBagParser(null), results);
		assertNotNull(r);
		assertEquals(PddLuckyBagType.FRUIT, r.getType());
	}

	/**
	 * 无任何类型特征文案：类型判为 UNKNOWN，邀请码仍能正常提取。
	 */
	@Test
	void parse_typeUnknown() {
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("92463725", 270, 880, 830, 1020)
		);
		PddLuckyBagResult r = parse(new PddLuckyBagParser(null), results);
		assertNotNull(r);
		assertEquals("92463725", r.getLuckyBagCode());
		assertEquals(PddLuckyBagType.UNKNOWN, r.getType());
	}
}
