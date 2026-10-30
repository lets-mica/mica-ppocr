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

import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Result;
import net.dreamlu.mica.ai.ppocr.utils.CollUtil;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LabelMatcher} 三项增强的单元测试：
 * <ol>
 *   <li>相邻同排片段拼接重建标签；</li>
 *   <li>标签别名表（{@link LabelAliases}）；</li>
 *   <li>unmatched 语义（{@link LabelMatchStatus}）。</li>
 * </ol>
 */
class LabelMatcherEnhanceTest extends ParserTestSupport {

	// ==================================================================
	// 增强一：相邻同排片段拼接重建标签
	// ==================================================================

	@Test
	void joinAdjacentPieces_rebuildsFullLabel() {
		// OCR 把"出生日期"切成两个独立框
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("出生", 60, 220, 140, 250),
			box("日期", 145, 220, 220, 250)
		);
		LabelMatcher.LabelHit hit = LabelMatcher.findLabelHit(results, "出生日期");
		assertNotNull(hit);
		assertEquals(LabelMatcher.HitKind.JOINED, hit.kind());
		assertEquals("出生日期", hit.box().text());
		// 合成框应覆盖两个片段
		assertEquals(60, LabelMatcher.minX(hit.box()));
		assertEquals(220, LabelMatcher.maxX(hit.box()));
	}

	@Test
	void joinAdjacentPieces_takesValueOnRight() {
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("出生", 60, 220, 140, 250),
			box("日期", 145, 220, 220, 250),
			box("1996-01-11", 260, 220, 400, 250)
		);
		assertEquals("1996-01-11", LabelMatcher.matchValue(results, "出生日期"));
	}

	@Test
	void joinAdjacentPieces_tooFarApart_notJoined() {
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("出生", 60, 220, 140, 250),
			box("日期", 400, 220, 480, 250)
		);
		LabelMatcher.LabelHit hit = LabelMatcher.findLabelHit(results, "出生日期");
		assertNotNull(hit);
		// 间隔过大 → 不拼接，退回 fragment 回退（标签包含识别文本）
		assertEquals(LabelMatcher.HitKind.FRAGMENT, hit.kind());
		assertEquals("出生", hit.box().text());
	}

	@Test
	void joinAdjacentPieces_resultNotEqualLabel_notJoined() {
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("出生", 60, 220, 140, 250),
			box("年月", 145, 220, 220, 250)
		);
		LabelMatcher.LabelHit hit = LabelMatcher.findLabelHit(results, "出生日期");
		assertNotNull(hit);
		assertNotEqualsJoined(hit);
	}

	@Test
	void joinAdjacentPieces_notSameRow_notJoined() {
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("出生", 60, 220, 140, 250),
			box("日期", 145, 400, 220, 430)
		);
		LabelMatcher.LabelHit hit = LabelMatcher.findLabelHit(results, "出生日期");
		assertNotNull(hit);
		assertNotEqualsJoined(hit);
	}

	// ==================================================================
	// 增强二：标签别名表
	// ==================================================================

	@Test
	void alias_extraCharacterVariant_matched() {
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("公民身份证号码", 60, 380, 240, 410),
			box("000000199601110004", 260, 380, 600, 410)
		);
		LabelMatcher.LabelHit hit = LabelMatcher.findLabelHit(results, "公民身份号码");
		assertNotNull(hit);
		assertEquals(LabelMatcher.HitKind.ALIAS, hit.kind());
		assertEquals("公民身份证号码", hit.box().text());
		assertEquals("000000199601110004",
			LabelMatcher.matchValue(results, "公民身份号码"));
	}

	@Test
	void alias_spacedVariant_matched() {
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("姓 名", 60, 100, 140, 130),
			box("测试甲", 160, 100, 280, 130)
		);
		assertEquals("测试甲", LabelMatcher.matchValue(results, "姓名"));
	}

	@Test
	void alias_mergedBox_valueStripped() {
		// 标签与值合并成一框，且标签用的是别名写法
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("身份证号000000199601110004", 60, 380, 600, 410)
		);
		assertEquals("000000199601110004",
			LabelMatcher.matchValueFromPrefix(results, "公民身份号码"));
	}

	@Test
	void alias_doesNotOverrideCanonicalLabel() {
		// 同时存在规范标签与别名写法时，规范标签优先
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("公民身份号码", 60, 380, 240, 410),
			box("身份证号", 60, 440, 200, 470)
		);
		LabelMatcher.LabelHit hit = LabelMatcher.findLabelHit(results, "公民身份号码");
		assertNotNull(hit);
		assertEquals(LabelMatcher.HitKind.EXACT, hit.kind());
		assertEquals("公民身份号码", hit.box().text());
	}

	@Test
	void aliasTable_registrableAndDisableable() {
		String label = "自定义标签";
		try {
			LabelAliases.register(label, "自 定 义");
			List<PPOcrV6Result> results = CollUtil.listOf(
				box("自 定 义", 60, 100, 140, 130),
				box("值", 160, 100, 200, 130)
			);
			assertNotNull(LabelMatcher.findLabelBox(results, label));
			// 关闭别名表后应回到"找不到"
			LabelAliases.setEnabled(false);
			try {
				assertNull(LabelMatcher.findLabelBox(results, label));
			} finally {
				LabelAliases.setEnabled(true);
			}
		} finally {
			LabelAliases.setEnabled(true);
		}
	}

	// ==================================================================
	// 增强三：unmatched 语义
	// ==================================================================

	@Test
	void status_labelNotFound_isNotFound() {
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("姓名", 60, 100, 140, 130)
		);
		LabelMatcher.LabeledMatch m = LabelMatcher.matchValueWithBox(results, "性别");
		assertEquals(LabelMatchStatus.NOT_FOUND, m.status());
		assertFalse(m.hasValue());
		assertFalse(m.labelWithoutValue());
	}

	@Test
	void status_labelFoundWithoutValue_isLabelWithoutValue() {
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("性别", 60, 160, 140, 190)
		);
		LabelMatcher.LabeledMatch m = LabelMatcher.matchValueWithBox(results, "性别");
		assertEquals(LabelMatchStatus.LABEL_WITHOUT_VALUE, m.status());
		assertFalse(m.hasValue());
		assertTrue(m.labelWithoutValue());
	}

	@Test
	void status_labelAndValueMatched_isFound() {
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("性别", 60, 160, 140, 190),
			box("男", 160, 160, 200, 190)
		);
		LabelMatcher.LabeledMatch m = LabelMatcher.matchValueWithBox(results, "性别");
		assertEquals(LabelMatchStatus.FOUND, m.status());
		assertTrue(m.hasValue());
		assertEquals("男", m.value());
	}

	@Test
	void status_mergedBoxStripped_isFound() {
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("性别男民族汉", 60, 160, 240, 190)
		);
		LabelMatcher.LabeledMatch m = LabelMatcher.matchValueFromPrefixWithBox(results, "性别");
		assertTrue(m.hasValue());
		assertEquals(LabelMatchStatus.FOUND, m.status());
	}

	// ==================================================================
	// 辅助
	// ==================================================================

	/**
	 * 断言命中不是"拼接重建"得到的。
	 *
	 * @param hit 命中信息
	 */
	private static void assertNotEqualsJoined(LabelMatcher.LabelHit hit) {
		assertFalse(hit.kind() == LabelMatcher.HitKind.JOINED,
			"不应触发相邻片段拼接重建");
	}
}
