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

package net.dreamlu.mica.ai.ppocr.structured.parser.driver;

import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Result;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.FieldConfidence;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.FieldSource;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.ParserTestSupport;
import net.dreamlu.mica.ai.ppocr.utils.CollUtil;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 驾驶证解析器单元测试。
 *
 * <p>基于 driver1.jpg（王桃桃）和 driver2.jpg（常飞超）的实际版面布局构造测试数据。
 */
class DriverLicenseParserTest extends ParserTestSupport {

	/**
	 * 构造一张典型驾照的 OCR 框（模拟图片宽 800 / 高 600）。
	 */
	private static List<PPOcrV6Result> buildSampleOcr(
		String licenseNo,
		String name,
		String gender,
		String nationality,
		String address,
		String birthDate,
		String issueDate,
		String vehicleClass,
		String authorityLine1,
		String authorityLine2,
		String authorityLine3,
		String validPeriod
	) {
		return CollUtil.listOf(
			box("中华人民共和国机动车驾驶证", 200, 30, 600, 60),
			box("Driving License of the People's Republic of China", 180, 65, 620, 85),
			box("证号", 280, 100, 340, 125),
			box(licenseNo, 360, 100, 600, 125),
			box("姓名", 100, 150, 150, 175),
			box("Name", 100, 180, 160, 200),
			box(name, 170, 160, 260, 200),
			box("性别", 320, 150, 370, 175),
			box("Sex", 320, 180, 360, 200),
			box(gender, 380, 160, 420, 200),
			box("国籍", 480, 150, 530, 175),
			box("Nationality", 480, 180, 580, 200),
			box(nationality, 590, 160, 660, 200),
			box("住址", 100, 230, 150, 255),
			box("Address", 100, 260, 170, 280),
			box(address, 180, 240, 700, 280),
			box(authorityLine1, 100, 330, 240, 360),
			box(authorityLine2, 100, 370, 240, 400),
			box(authorityLine3, 100, 410, 240, 440),
			box("出生日期", 320, 330, 400, 360),
			box("Date of Birth", 320, 360, 410, 380),
			box(birthDate, 420, 340, 560, 380),
			box("初次领证日期", 320, 400, 430, 430),
			box("Date of First Issue", 320, 430, 450, 450),
			box(issueDate, 440, 410, 580, 450),
			box("准驾车型", 320, 470, 410, 500),
			box("Class", 320, 500, 380, 520),
			box(vehicleClass, 420, 480, 510, 520),
			box("有效期限", 100, 510, 180, 540),
			box("Valid Period", 100, 540, 200, 560),
			box(validPeriod, 210, 520, 700, 560)
		);
	}

	@Test
	void parse_driver1() {
		List<PPOcrV6Result> results = buildSampleOcr(
			"210282198809294228",  // 证号
			"王桃桃",              // 姓名
			"女",                  // 性别
			"中国",                // 国籍
			"辽宁省大连市甘井子区", // 住址
			"1988-09-29",          // 出生日期
			"2015-05-18",          // 初次领证日期
			"C1",                  // 准驾车型
			"北京市公",            // 签发机关第 1 行（OCR 实际可能拆字）
			"安局公安",            // 第 2 行
			"交通管理局",          // 第 3 行
			"2015-05-18 至 2021-05-18"  // 有效期限
		);
		DriverLicenseResult r = parse(new DriverLicenseParser(null), results);
		assertNotNull(r);
		assertEquals("210282198809294228", r.getLicenseNumber());
		assertEquals("王桃桃", r.getName());
		assertEquals("女", r.getGender());
		assertEquals("中国", r.getNationality());
		assertEquals("辽宁省大连市甘井子区", r.getAddress());
		assertEquals("1988-09-29", r.getBirthDate());
		assertEquals("2015-05-18", r.getIssueDate());
		assertEquals("C1", r.getVehicleClass());
		assertEquals("2015-05-18", r.getValidFrom());
		assertEquals("2021-05-18", r.getValidTo());
		// 签发机关：≥4 字的行按 y 升序拼接
		assertEquals("北京市公安局公安交通管理局", r.getIssuingAuthority());
	}

	@Test
	void parse_driver2() {
		List<PPOcrV6Result> results = buildSampleOcr(
			"130428198812180013",  // 证号
			"常飞超",              // 姓名
			"男",                  // 性别
			"中国",                // 国籍
			"河北省邯郸市肥乡县肥乡镇", // 住址
			"1988-12-18",          // 出生日期
			"2017-05-12",          // 初次领证日期
			"C1",                  // 准驾车型
			"北京市公",
			"安局公安",
			"交通管理局",
			"2017-05-12 至 2023-05-12"
		);
		DriverLicenseResult r = parse(new DriverLicenseParser(null), results);
		assertNotNull(r);
		assertEquals("130428198812180013", r.getLicenseNumber());
		assertEquals("常飞超", r.getName());
		assertEquals("男", r.getGender());
		assertEquals("河北省邯郸市肥乡县肥乡镇", r.getAddress());
		assertEquals("1988-12-18", r.getBirthDate());
		assertEquals("2017-05-12", r.getIssueDate());
		assertEquals("C1", r.getVehicleClass());
		assertEquals("2017-05-12", r.getValidFrom());
		assertEquals("2023-05-12", r.getValidTo());
	}

	@Test
	void parse_emptyResults() {
		DriverLicenseResult r = parse(new DriverLicenseParser(null), CollUtil.listOf());
		assertNotNull(r);
		// 所有字段为 null
		assertEquals(null, r.getLicenseNumber());
		assertEquals(null, r.getName());
		assertEquals(null, r.getValidFrom());
		assertEquals(null, r.getValidTo());
	}

	@Test
	void parse_licenseNumberFallbackByRegex() {
		// "证号" 标签丢失，但全文中能搜到 18 位数字
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("中华人民共和国机动车驾驶证", 200, 30, 600, 60),
			box("210282198809294228", 360, 100, 600, 125),
			box("姓名", 100, 150, 150, 175),
			box("王桃桃", 170, 160, 260, 200)
		);
		DriverLicenseResult r = parse(new DriverLicenseParser(null), results);
		assertNotNull(r);
		assertEquals("210282198809294228", r.getLicenseNumber());
		assertEquals("王桃桃", r.getName());
	}

	// ==================================================================
	// 正确性层：日期修复 + 性别回填 + 证号校验位
	// ==================================================================

	@Test
	void parse_birthDateBrokenSeparator_repaired() {
		// "2015-0518"：短横线被认成 0 → 按纯数字重排还原
		List<PPOcrV6Result> results = buildSampleOcr(
			"440524188001010014",
			"测试甲",
			"男",
			"中国",
			"测试省甲市甲区",
			"2015-0518",
			"2017-05-12",
			"C1",
			"测试省公",
			"安局公安",
			"交通管理局",
			"2017-05-12 至 2023-05-12"
		);
		DriverLicenseResult r = parse(new DriverLicenseParser(null), results);
		assertEquals("2015-05-18", r.getBirthDate());
	}

	@Test
	void parse_birthDateNormal_unchanged() {
		List<PPOcrV6Result> results = buildSampleOcr(
			"440524188001010014",
			"测试甲",
			"男",
			"中国",
			"测试省甲市甲区",
			"2015-05-18",
			"2017-05-12",
			"C1",
			"测试省公",
			"安局公安",
			"交通管理局",
			"2017-05-12 至 2023-05-12"
		);
		DriverLicenseResult r = parse(new DriverLicenseParser(null), results);
		assertEquals("2015-05-18", r.getBirthDate());
	}

	@Test
	void parse_genderBackfilledFromLicenseNo() {
		// OCR 把"性别"整块漏掉：驾驶证证号即公民身份号码，其性别位能救回
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("中华人民共和国机动车驾驶证", 200, 30, 600, 60),
			box("证号", 280, 100, 340, 125),
			box("440524188001010014", 360, 100, 600, 125),
			box("姓名", 100, 150, 150, 175),
			box("测试甲", 170, 160, 260, 200)
		);
		DriverLicenseResult r = parse(new DriverLicenseParser(null), results);
		// 证号第 17 位为 '1'（奇数）→ 男
		assertEquals("男", r.getGender());
		FieldConfidence fc = r.getFieldConfidences().get("gender");
		assertNotNull(fc);
		assertEquals(FieldSource.BACKFILL, fc.source());
	}

	@Test
	void parse_licenseNoChecksumValid_confidenceKept() {
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("证号", 280, 100, 340, 125),
			box("440524188001010014", 360, 100, 600, 125)
		);
		DriverLicenseResult r = parse(new DriverLicenseParser(null), results);
		FieldConfidence fc = r.getFieldConfidences().get("licenseNumber");
		assertNotNull(fc);
		assertTrue(fc.score() > 0);
		assertTrue(fc.violations().isEmpty());
		assertFalse(r.isNeedsReview());
	}

	@Test
	void parse_licenseNoChecksumInvalid_scoreZeroAndNeedsReview() {
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("证号", 280, 100, 340, 125),
			box("440524188001010015", 360, 100, 600, 125)
		);
		DriverLicenseResult r = parse(new DriverLicenseParser(null), results);
		assertEquals("440524188001010015", r.getLicenseNumber());
		FieldConfidence fc = r.getFieldConfidences().get("licenseNumber");
		assertNotNull(fc);
		assertEquals(0d, fc.score(), 1e-9);
		assertTrue(r.isNeedsReview());
	}
}
