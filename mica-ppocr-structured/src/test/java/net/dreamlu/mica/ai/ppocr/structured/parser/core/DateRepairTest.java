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
 * 日期修复单元测试。
 *
 * <p>覆盖 OCR 把日期分隔符认错的各种形态：认成数字、认成点、认成汉字、漏掉补零。
 */
class DateRepairTest {

	@Test
	void repairDate_separatorRecognizedAsDigit_restoredByDigitsRearrange() {
		// "2015-0518"：短横线被认成 0，8 位数字凑齐后按 yyyyMMdd 还原
		assertEquals("2015-05-18", DateRepair.repairDate("2015-0518"));
	}

	@Test
	void repairDate_normalSeparator_unchanged() {
		assertEquals("2015-05-18", DateRepair.repairDate("2015-05-18"));
		assertEquals("2015-05-18", DateRepair.repairDate("2015.05.18"));
		assertEquals("2015-05-18", DateRepair.repairDate("2015/05/18"));
		assertEquals("2015-05-18", DateRepair.repairDate("2015年05月18日"));
	}

	@Test
	void repairDate_singleDigitMonthAndDay_zeroPadded() {
		assertEquals("2015-05-08", DateRepair.repairDate("2015-5-8"));
		assertEquals("2015-03-05", DateRepair.repairDate("2015年3月5日"));
	}

	@Test
	void repairDate_eightDigits_restoredDirectly() {
		assertEquals("2015-05-18", DateRepair.repairDate("20150518"));
	}

	@Test
	void repairDate_daySplitByDot_keepsAllDigits() {
		// "2017-05-1.2"：日期里的点把 "12" 拆成 "1" + "2"
		assertEquals("2017-05-12", DateRepair.repairDate("2017-05-1.2"));
	}

	@Test
	void repairDate_trailingNoise_stillRepaired() {
		assertEquals("2017-05-12", DateRepair.repairDate("2017-05-12."));
	}

	@Test
	void repairDate_trailingTime_slidingWindowTakesFirstValidDate() {
		assertEquals("2015-05-18", DateRepair.repairDate("2015-05-18 12:30"));
	}

	@Test
	void repairDate_illegalDate_notRepaired() {
		// 月份 13 不是合法月份
		assertNull(DateRepair.repairDate("2015-13-18"));
		// 2 月没有 30 日
		assertNull(DateRepair.repairDate("2015-02-30"));
		// 平年 2 月 29 日非法
		assertNull(DateRepair.repairDate("2015-02-29"));
		// 闰年 2 月 29 日合法
		assertEquals("2016-02-29", DateRepair.repairDate("2016-02-29"));
	}

	@Test
	void repairDate_nullOrNonDateText_returnsNull() {
		assertNull(DateRepair.repairDate(null));
		assertNull(DateRepair.repairDate(""));
		assertNull(DateRepair.repairDate("   "));
		assertNull(DateRepair.repairDate("长期"));
		assertNull(DateRepair.repairDate("2015"));
	}

	@Test
	void isRepairableDate_matchesRepairResult() {
		assertTrue(DateRepair.isRepairableDate("2015-0518"));
		assertFalse(DateRepair.isRepairableDate("2015-13-18"));
	}
}
