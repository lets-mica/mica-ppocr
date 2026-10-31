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

package net.dreamlu.mica.ai.ppocr.structured.parser.idcard;

import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Result;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.FieldConfidence;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.FieldSource;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.ParserTestSupport;
import net.dreamlu.mica.ai.ppocr.utils.CollUtil;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 身份证解析器单元测试。
 *
 * <p>所有测试数据均为合成占位（姓名"测试甲/乙/..."、身份证区段"000000"、
 * 地址"测试省X市X区..."），不引用任何真实个人数据。
 */
class IdCardParserTest extends ParserTestSupport {

	@Test
	void parse_front() {
		// 模拟正面：姓名/性别/民族/出生/住址/身份证号
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("姓名", 60, 100, 140, 130),
			box("测试甲", 160, 100, 280, 130),
			box("性别", 60, 160, 140, 190),
			box("男", 160, 160, 200, 190),
			box("民族", 280, 160, 360, 190),
			box("汉", 380, 160, 420, 190),
			box("出生", 60, 220, 140, 250),
			box("1996 年 1 月 11 日", 160, 220, 400, 250),
			box("住址", 60, 280, 140, 310),
			box("测试省甲市甲区", 160, 280, 480, 310),
			box("公民身份号码", 60, 380, 240, 410),
			box("000000199601110001", 260, 380, 600, 410)
		);
		IdCardResult r = parse(new IdCardParser(null), results);
		assertNotNull(r);
		assertEquals(IdCardSide.FRONT, r.getSide());
		assertEquals("测试甲", r.getName());
		assertEquals("男", r.getGender());
		assertEquals("汉", r.getNation());
		assertEquals("1996 年 1 月 11 日", r.getBirthDate());
		assertEquals("测试省甲市甲区", r.getAddress());
		assertEquals("000000199601110001", r.getIdNumber());
		// 反面字段在正面下保持 null
		assertNull(r.getIssuingAuthority());
		assertNull(r.getValidFrom());
		assertNull(r.getValidTo());
	}

	@Test
	void parse_front_multilineAddress() {
		// 模拟住址跨两行：第一行"测试省乙市乙区乙街"，第二行"测试镇"
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("姓名", 60, 100, 140, 130),
			box("测试乙", 160, 100, 220, 130),
			box("性别", 60, 160, 140, 190),
			box("男", 160, 160, 200, 190),
			box("民族", 280, 160, 360, 190),
			box("汉", 380, 160, 420, 190),
			box("出生", 60, 220, 140, 250),
			box("1966 年 11 月 2 日", 160, 220, 400, 250),
			box("住址", 60, 280, 140, 310),
			box("测试省乙市乙区乙街", 160, 280, 480, 310),  // 第一行
			box("测试镇", 160, 320, 240, 350),            // 第二行（y 在标签下方扩展范围）
			box("公民身份号码", 60, 380, 240, 410),
			box("000000196611020002", 260, 380, 600, 410)
		);
		IdCardResult r = parse(new IdCardParser(null), results);
		assertEquals(IdCardSide.FRONT, r.getSide());
		assertNotNull(r.getAddress());
		// 应包含两行内容
		assertEquals(true, r.getAddress().contains("测试省乙市乙区乙街"));
		assertEquals(true, r.getAddress().contains("测试镇"));
	}

	@Test
	void parse_back() {
		// 模拟反面：签发机关 + 有效期限
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("签发机关", 200, 320, 300, 350),
			box("测试公安局甲区分局", 320, 320, 580, 350),
			box("有效期限", 200, 380, 300, 410),
			box("2020.01.01-2030.01.01", 320, 380, 580, 410)
		);
		IdCardResult r = parse(new IdCardParser(null), results);
		assertNotNull(r);
		assertEquals(IdCardSide.BACK, r.getSide());
		assertEquals("测试公安局甲区分局", r.getIssuingAuthority());
		assertEquals("2020.01.01", r.getValidFrom());
		assertEquals("2030.01.01", r.getValidTo());
		// 正面字段在反面上保持 null
		assertNull(r.getName());
		assertNull(r.getIdNumber());
	}

	@Test
	void parse_back_longTerm() {
		// 模拟新版反面：长期有效
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("签发机关", 200, 320, 300, 350),
			box("测试公安局乙区分局", 320, 320, 580, 350),
			box("有效期限", 200, 380, 300, 410),
			box("2021.02.03-2041.02.03", 320, 380, 580, 410)
		);
		IdCardResult r = parse(new IdCardParser(null), results);
		assertEquals(IdCardSide.BACK, r.getSide());
		assertEquals("测试公安局乙区分局", r.getIssuingAuthority());
		assertEquals("2021.02.03", r.getValidFrom());
		assertEquals("2041.02.03", r.getValidTo());
	}

	@Test
	void parse_emptyResults_returnsUnknown() {
		IdCardResult r = parse(new IdCardParser(null), CollUtil.listOf());
		assertNotNull(r);
		assertEquals(IdCardSide.UNKNOWN, r.getSide());
	}

	@Test
	void parse_idNumberFallbackWhenLabelMissing() {
		// "公民身份号码" 标签残缺/丢失，仅靠正则兜底
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("姓名", 60, 100, 140, 130),
			box("测试丙", 160, 100, 220, 130),
			box("000000199601110001", 260, 380, 600, 410)
		);
		IdCardResult r = parse(new IdCardParser(null), results);
		assertEquals(IdCardSide.FRONT, r.getSide());
		assertEquals("000000199601110001", r.getIdNumber());
	}

	@Test
	void parse_idNumberRegexFindWhenLabelGarbled() {
		// "公民身份号码" 标签 OCR 残缺（缺"码"字），靠 18 位正则 find() 兜底
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("姓名", 60, 100, 140, 130),
			box("测试丙", 160, 100, 220, 130),
			box("公民身份号000000197402220003", 260, 380, 600, 410)
		);
		IdCardResult r = parse(new IdCardParser(null), results);
		assertEquals(IdCardSide.FRONT, r.getSide());
		assertEquals("000000197402220003", r.getIdNumber());
	}

	@Test
	void parse_front_mergedLabelValueBoxes() {
		// 模拟真实 OCR：标签与值合并在同一框，"性别男民族汉" 双标签连写
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("姓名测试丁", 745, 556, 873, 586),
			box("性别男民族汉", 750, 595, 927, 621),
			box("出生1974年2月22日", 754, 632, 972, 656),
			box("住址测试省丁市丁区丁街", 756, 671, 1010, 696),
			box("测试镇测试路1号", 819, 692, 975, 715),
			box("公民身份号码000000197402220004", 767, 765, 1159, 791)
		);
		IdCardResult r = parse(new IdCardParser(null), results);
		assertEquals(IdCardSide.FRONT, r.getSide());
		assertEquals("测试丁", r.getName());
		assertEquals("男", r.getGender());
		assertEquals("汉", r.getNation());
		assertEquals("1974年2月22日", r.getBirthDate());
		assertEquals("测试省丁市丁区丁街测试镇测试路1号", r.getAddress());
		assertEquals("000000197402220004", r.getIdNumber());
	}

	@Test
	void parse_front_shortLineMultilineAddress() {
		// 模拟住址跨行且第二行为短文本（如"测试镇1号"），测试中心 x 靠左时不被误杀
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("姓名测试丙", 84, 70, 216, 100),
			box("性别男民族汉", 82, 113, 276, 145),
			box("出生1999年12月24日", 80, 154, 319, 185),
			box("住址测试省戊市戊区戊街", 76, 199, 362, 231),
			box("测试镇1号", 139, 229, 173, 255),
			box("公民身份号码", 66, 312, 194, 337),
			box("000000199912240005", 219, 315, 505, 344)
		);
		IdCardResult r = parse(new IdCardParser(null), results);
		assertEquals(IdCardSide.FRONT, r.getSide());
		assertEquals("测试丙", r.getName());
		assertEquals("男", r.getGender());
		assertEquals("汉", r.getNation());
		assertEquals("1999年12月24日", r.getBirthDate());
		assertEquals("测试省戊市戊区戊街测试镇1号", r.getAddress());
		assertEquals("000000199912240005", r.getIdNumber());
	}


	@Test
	void parse_front_15digit() {
		// 模拟 15 位身份证正面（早期签发，常见于历史档案/老照片）：6位区划 + 6位 YYMMDD + 3位顺序
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("姓名", 60, 100, 140, 130),
			box("测试戊", 160, 100, 220, 130),
			box("性别", 60, 160, 140, 190),
			box("男", 160, 160, 200, 190),
			box("民族", 280, 160, 360, 190),
			box("汉", 380, 160, 420, 190),
			box("出生", 60, 220, 140, 250),
			box("1966 年 5 月 20 日", 160, 220, 400, 250),
			box("住址", 60, 280, 140, 310),
			box("测试省丙市", 160, 280, 480, 310),
			box("公民身份号码", 60, 380, 240, 410),
			box("000000660520006", 260, 380, 600, 410)
		);
		IdCardResult r = parse(new IdCardParser(null), results);
		assertEquals(IdCardSide.FRONT, r.getSide());
		assertEquals("测试戊", r.getName());
		assertEquals("男", r.getGender());
		assertEquals("汉", r.getNation());
		assertEquals("1966 年 5 月 20 日", r.getBirthDate());
		assertEquals("测试省丙市", r.getAddress());
		assertEquals("000000660520006", r.getIdNumber());
	}

	@Test
	void parse_idNumberFallback_15digit() {
		// 15 位身份证号正则兜底：标签残缺/丢失，仅 15 位号码本身
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("姓名", 60, 100, 140, 130),
			box("测试己", 160, 100, 220, 130),
			box("000000700315007", 260, 380, 600, 410)
		);
		IdCardResult r = parse(new IdCardParser(null), results);
		assertEquals(IdCardSide.FRONT, r.getSide());
		assertEquals("000000700315007", r.getIdNumber());
	}

	@Test
	void parse_birthDateFromIdNumber_15digit() {
		// 15 位身份证：OCR "出生" 标签整体残缺，靠身份证号推算（YY 默认按 19YY 补全）
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("姓名", 60, 100, 140, 130),
			box("测试丙", 160, 100, 220, 130),
			box("公民身份号码", 60, 380, 240, 410),
			box("000000650812008", 260, 380, 600, 410)
		);
		IdCardResult r = parse(new IdCardParser(null), results);
		assertEquals(IdCardSide.FRONT, r.getSide());
		assertEquals("000000650812008", r.getIdNumber());
		// 15 位 YY=65 → 1965 年 8 月 12 日
		assertEquals("1965年08月12日", r.getBirthDate());
	}

	@Test
	void parse_birthDateFromIdNumber_18digit() {
		// 18 位身份证：OCR "出生" 标签整体残缺，靠身份证号推算
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("姓名", 60, 100, 140, 130),
			box("测试庚", 160, 100, 220, 130),
			box("公民身份号码", 60, 380, 240, 410),
			box("000000199003150009", 260, 380, 600, 410)
		);
		IdCardResult r = parse(new IdCardParser(null), results);
		assertEquals(IdCardSide.FRONT, r.getSide());
		assertEquals("1990年03月15日", r.getBirthDate());
	}

	@Test
	void parse_birthDateFromIdNumber_18PriorityOver15Substring() {
		// 18 位号码的前 15 位也是连续数字（合法 15 位子串），需识别为完整 18 位而非前 15 位
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("姓名", 60, 100, 140, 130),
			box("测试辛", 160, 100, 220, 130),
			box("000000198501010010", 260, 380, 600, 410)
		);
		IdCardResult r = parse(new IdCardParser(null), results);
		assertEquals(IdCardSide.FRONT, r.getSide());
		// 完整 18 位号码（不返回前 15 位 "000000198501010"）
		assertEquals(18, r.getIdNumber().length());
		assertEquals("000000198501010010", r.getIdNumber());
	}

	@Test
	void parse_birthDateFromIdNumber_illegalDate_notDerived() {
		// OCR 把出生日期的月份认成 13 —— 推算出的是非法日期，
		// 必须返回 null（字段缺失），不能输出 "1990年13月15日" 这种假日期
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("姓名", 60, 100, 140, 130),
			box("测试辛", 160, 100, 220, 130),
			box("公民身份号码", 60, 380, 240, 410),
			box("000000199013150009", 260, 380, 600, 410)
		);
		IdCardResult r = parse(new IdCardParser(null), results);
		assertEquals(IdCardSide.FRONT, r.getSide());
		assertNull(r.getBirthDate());
	}

	@Test
	void parse_birthDateFromIdNumber_feb30_notDerived() {
		// 非闰年的 2 月 30 日不存在，同样不能输出
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("姓名", 60, 100, 140, 130),
			box("测试辛", 160, 100, 220, 130),
			box("公民身份号码", 60, 380, 240, 410),
			box("000000199002300009", 260, 380, 600, 410)
		);
		IdCardResult r = parse(new IdCardParser(null), results);
		assertEquals(IdCardSide.FRONT, r.getSide());
		assertNull(r.getBirthDate());
	}

	/**
	 * 模拟 doc_ori 90° 旋转后的真实 OCR 输出：
	 * <ul>
	 *   <li>公民身份号码在左侧（x=650-685），与地址同 y 起点（y=440），号码向下延伸 y=760；</li>
	 *   <li>住址第一行在中间（x=727-762, y=440-655），是"住址+第一行"合并框；</li>
	 *   <li>住址续行在第一行下方（x=712-740, y=486-584），x 部分覆盖第一行；</li>
	 *   <li>性别标签在右上方（x=791-822, y=442-489），男民族汉值框堆叠在标签正下方（x=792-819, y=484-590）。</li>
	 * </ul>
	 * 验证：
	 * <ol>
	 *   <li>address 必须包含续行（旋转布局下 bottomLimitY 不应误剔续行）；</li>
	 *   <li>gender = "男"（值框在标签正下方，标准右侧匹配会失败，below-label 策略兜底）；</li>
	 *   <li>nation = "汉"（从"男民族汉"切到下一标签前）；</li>
	 *   <li>idNumber = 完整 18 位。</li>
	 * </ol>
	 */
	@Test
	void parse_front_rotatedCard_addressAndGender() {
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("公民身份号码000000196102120011", 650, 440, 685, 760),
			box("住址测试省己市己区己街", 727, 440, 762, 655),
			box("测试镇63号", 712, 486, 740, 584),
			box("性别", 791, 442, 822, 489),
			box("姓名测试壬", 822, 442, 854, 551),
			box("男民族汉", 792, 484, 819, 590)
		);
		IdCardResult r = parse(new IdCardParser(null), results);
		assertEquals(IdCardSide.FRONT, r.getSide());
		assertEquals("测试壬", r.getName());
		assertEquals("男", r.getGender());
		assertEquals("汉", r.getNation());
		// 关键：旋转布局下地址必须包含续行
		assertEquals("测试省己市己区己街测试镇63号", r.getAddress());
		assertEquals("000000196102120011", r.getIdNumber());
	}

	/**
	 * 模拟标准正面但"性别"标签和"男"值框上下堆叠（同 x 范围、y 重叠小）：
	 * 验证 below-label 策略能从"男"框独立提取性别值，且不与右侧其他框混淆。
	 */
	@Test
	void parse_front_genderValueDirectlyBelowLabel() {
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("姓名", 60, 100, 140, 130),
			box("测试甲", 160, 100, 280, 130),
			box("性别", 60, 160, 140, 190),
			box("男", 60, 192, 110, 222),
			box("民族", 280, 160, 360, 190),
			box("汉", 380, 160, 420, 190),
			box("出生", 60, 280, 140, 310),
			box("1996 年 1 月 11 日", 160, 280, 400, 310),
			box("住址", 60, 340, 140, 370),
			box("测试省甲市甲区", 160, 340, 480, 370),
			box("公民身份号码", 60, 440, 240, 470),
			box("000000199601110001", 260, 440, 600, 470)
		);
		IdCardResult r = parse(new IdCardParser(null), results);
		assertEquals("男", r.getGender());
		assertEquals("汉", r.getNation());
	}

	/**
	 * 模拟 small 模型漏识别"性别"label 第一字（或第二字）的场景：合并框为"别男民族汉"。
	 * 验证残片策略能从中切出"男"作为性别。
	 */
	@Test
	void parse_front_partialGenderLabel_xxMissing() {
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("姓名", 60, 100, 140, 130),
			box("测试甲", 160, 100, 280, 130),
			box("别男民族汉", 60, 160, 280, 190),
			box("出生", 60, 220, 140, 250),
			box("1996 年 1 月 11 日", 160, 220, 400, 250),
			box("住址", 60, 280, 140, 310),
			box("测试省甲市甲区", 160, 280, 480, 310),
			box("公民身份号码", 60, 380, 240, 410),
			box("000000199601110001", 260, 380, 600, 410)
		);
		IdCardResult r = parse(new IdCardParser(null), results);
		assertEquals(IdCardSide.FRONT, r.getSide());
		assertEquals("男", r.getGender());
		assertEquals("汉", r.getNation());
	}

	/**
	 * 模拟 small 模型完全没识别"住址"label、地址散在两个框、混有低置信度噪声
	 * （单字 OCR 碎屑）和日期框的极端场景。
	 * 验证无标签兜底能正确拼出地址，并排除噪声。
	 */
	@Test
	void parse_front_addressLabelMissing_fallback() {
		// 注：ParserTestSupport.box() 默认 score=1.0，低置信度框需手动构造
		PPOcrV6Result noise1 = new PPOcrV6Result("甲", 0.37f, new int[][]{
			{741, 458}, {761, 465}, {753, 488}, {733, 481}
		});
		PPOcrV6Result noise2 = new PPOcrV6Result("乙", 0.10f, new int[][]{
			{767, 464}, {788, 464}, {788, 483}, {767, 483}
		});
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("公民身份号码000000196102120011", 656, 438, 685, 760),
			box("姓名测试壬", 822, 441, 855, 550),
			noise1,
			noise2,
			box("别男民族汉", 794, 458, 820, 590),
			box("测试镇63号", 713, 487, 736, 582),
			box("测试省庚市庚区庚街", 733, 484, 758, 655),
			box("1990年3月15日", 762, 479, 789, 620)
		);
		IdCardResult r = parse(new IdCardParser(null), results);
		assertEquals(IdCardSide.FRONT, r.getSide());
		// 关键：地址要从两个无标签框中拼出，且排除低分噪声
		assertEquals("测试省庚市庚区庚街测试镇63号", r.getAddress());
	}

	// ==================================================================
	// 正确性层：GB 11643 校验位 + 性别回填 + 字段级置信度
	// ==================================================================

	/**
	 * 构造一张正面版式的 OCR 框（身份证号可传参，用于校验位用例）。
	 *
	 * @param idNumber 公民身份号码
	 * @param withGender 是否带"性别"标签与值
	 * @return OCR 框列表
	 */
	private static List<PPOcrV6Result> buildFrontOcr(String idNumber, boolean withGender) {
		List<PPOcrV6Result> results = new java.util.ArrayList<>();
		results.add(box("姓名", 60, 100, 140, 130));
		results.add(box("测试甲", 160, 100, 280, 130));
		if (withGender) {
			results.add(box("性别", 60, 160, 140, 190));
			results.add(box("女", 160, 160, 200, 190));
		}
		results.add(box("民族", 280, 160, 360, 190));
		results.add(box("汉", 380, 160, 420, 190));
		results.add(box("出生", 60, 220, 140, 250));
		results.add(box("1996 年 1 月 11 日", 160, 220, 400, 250));
		results.add(box("住址", 60, 280, 140, 310));
		results.add(box("测试省甲市甲区", 160, 280, 480, 310));
		results.add(box("公民身份号码", 60, 380, 240, 410));
		results.add(box(idNumber, 260, 380, 600, 410));
		return results;
	}

	@Test
	void parse_front_idNumberChecksumValid_confidenceKept() {
		// 000000199601110004：前 17 位加权和 107，107 % 11 = 8 → 校验码 '4'
		IdCardResult r = parse(new IdCardParser(null), buildFrontOcr("000000199601110004", true));
		assertEquals("000000199601110004", r.getIdNumber());
		FieldConfidence fc = r.getFieldConfidences().get("idNumber");
		assertNotNull(fc);
		assertTrue(fc.score() > 0);
		assertTrue(fc.violations().isEmpty());
		assertFalse(fc.needsReview());
		assertFalse(r.isNeedsReview());
	}

	@Test
	void parse_front_idNumberChecksumInvalid_scoreZeroAndNeedsReview() {
		// 校验位应为 '4'，这里写成 '5' —— 至少认错了一个字符
		IdCardResult r = parse(new IdCardParser(null), buildFrontOcr("000000199601110005", true));
		// 值本身仍要保留：校验失败不等于"没识别出来"，不能因此把结果丢掉
		assertEquals("000000199601110005", r.getIdNumber());
		FieldConfidence fc = r.getFieldConfidences().get("idNumber");
		assertNotNull(fc);
		// 校验不通过 → 置信度直接归零（不是打折）
		assertEquals(0d, fc.score(), 1e-9);
		assertFalse(fc.violations().isEmpty());
		assertTrue(fc.needsReview());
		// 整条结果标记为需复核，并给出原因
		assertTrue(r.isNeedsReview());
		assertFalse(r.getReviewReasons().isEmpty());
	}

	@Test
	void parse_front_genderBackfilledFromIdNumber() {
		// OCR 把"性别"整块漏掉：证件号的性别位（倒数第二位 '0'，偶数）能救回
		IdCardResult r = parse(new IdCardParser(null), buildFrontOcr("000000199601110004", false));
		assertEquals("女", r.getGender());
		FieldConfidence fc = r.getFieldConfidences().get("gender");
		assertNotNull(fc);
		assertEquals(FieldSource.BACKFILL, fc.source());
	}

	@Test
	void parse_front_missingField_markedNeedsReview() {
		// 正面应有 6 个字段，缺一个就要提示复核
		List<PPOcrV6Result> results = buildFrontOcr("000000199601110004", true);
		results.removeIf(r -> "民族".equals(r.text()) || "汉".equals(r.text()));
		IdCardResult r = parse(new IdCardParser(null), results);
		assertNull(r.getNation());
		assertTrue(r.isNeedsReview());
	}
}
