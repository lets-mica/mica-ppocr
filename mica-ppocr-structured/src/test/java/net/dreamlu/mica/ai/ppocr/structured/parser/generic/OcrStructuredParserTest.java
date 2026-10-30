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

import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Result;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.FieldConfidence;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.FieldSource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 通用解析器单元测试。
 *
 * <p>测试数据全部为<b>程序化合成</b>的 OCR 框（坐标与文本均为构造出来的），
 * 不含任何真实证件信息或真实照片。
 */
class OcrStructuredParserTest {

	// ==================================================================
	// 取值路径
	// ==================================================================

	@Test
	void parse_labelOnRight_takesValue() {
		List<PPOcrV6Result> ocr = new ArrayList<>();
		ocr.add(box("合同编号", 0.98f, 100, 100, 200, 140));
		ocr.add(box("HT-2024-001", 0.96f, 220, 104, 340, 140));

		OcrStructuredResult r = parse(OcrFieldSchema.builder("采购合同")
			.field("contractNo").labels("合同编号").add()
			.build(), ocr);

		assertEquals("HT-2024-001", r.getValue("contractNo"));
		assertTrue(r.getMissingFields().isEmpty());
		assertFalse(r.isNeedsReview());
		assertTrue(r.allTrusted());
	}

	@Test
	void parse_mergedLabelAndValueBox_stripsValue() {
		List<PPOcrV6Result> ocr = new ArrayList<>();
		ocr.add(box("合同编号：HT-2024-001", 0.95f, 100, 100, 300, 140));

		OcrStructuredResult r = parse(OcrFieldSchema.builder("采购合同")
			.field("contractNo").labels("合同编号").add()
			.build(), ocr);

		assertEquals("HT-2024-001", r.getValue("contractNo"));
		assertFalse(r.isNeedsReview());
	}

	@Test
	void parse_labelSplitIntoAdjacentPieces_rebuildsAndTakesValue() {
		List<PPOcrV6Result> ocr = new ArrayList<>();
		ocr.add(box("合同", 0.97f, 100, 100, 150, 140));
		ocr.add(box("编号", 0.97f, 155, 100, 210, 140));
		ocr.add(box("HT-2024-001", 0.96f, 220, 104, 340, 140));

		OcrStructuredResult r = parse(OcrFieldSchema.builder("采购合同")
			.field("contractNo").labels("合同编号").add()
			.build(), ocr);

		assertEquals("HT-2024-001", r.getValue("contractNo"));
	}

	@Test
	void parse_labelAlias_matched() {
		List<PPOcrV6Result> ocr = new ArrayList<>();
		ocr.add(box("合同号码", 0.98f, 100, 100, 200, 140));
		ocr.add(box("HT-2024-001", 0.96f, 220, 104, 340, 140));

		OcrStructuredResult r = parse(OcrFieldSchema.builder("采购合同")
			.field("contractNo").labels("合同编号", "合同号码").add()
			.build(), ocr);

		assertEquals("HT-2024-001", r.getValue("contractNo"));
	}

	// ==================================================================
	// 值归属判定（通用层专有的三层判定）
	// ==================================================================

	@Test
	void parse_valueBelongsToNearestLabel_doesNotStealAcrossNationality() {
		// 复刻驾照版面：性别的真值「女」未被检出，只剩一个横跨「国籍」右侧的
		// 「Nationaliy中国」。若不做归属判定，"性别"会静默拿到错误值。
		List<PPOcrV6Result> ocr = new ArrayList<>();
		ocr.add(box("性别", 0.98f, 780, 100, 870, 140));
		ocr.add(box("国籍", 0.98f, 887, 100, 970, 140));
		ocr.add(box("Nationaliy中国", 0.93f, 882, 100, 1121, 140));

		OcrStructuredResult r = parse(OcrFieldSchema.builder("驾照")
			.field("gender").labels("性别").add()
			.field("nationality").labels("国籍").add()
			.build(), ocr);

		assertNull(r.getValue("gender"), "性别不得抢走属于国籍的值");
		assertNull(r.getValue("nationality"), "值框与国籍标签水平相交，归属于国籍标签");
		assertTrue(r.isNeedsReview());
	}

	@Test
	void parse_nearestValue_genderTakesFemale() {
		List<PPOcrV6Result> ocr = new ArrayList<>();
		ocr.add(box("性别", 0.98f, 100, 100, 160, 140));
		ocr.add(box("女", 0.97f, 170, 105, 200, 140));

		OcrStructuredResult r = parse(OcrFieldSchema.builder("驾照")
			.field("gender").labels("性别").add()
			.build(), ocr);

		assertEquals("女", r.getValue("gender"));
	}

	@Test
	void parse_verticalOverlapRatio_slightlyShiftedValueStillMatched() {
		// 标签 y 100~140，值 y 120~160：重叠 20px / 较矮 40px = 0.5 ≥ 0.3
		List<PPOcrV6Result> ocr = new ArrayList<>();
		ocr.add(box("金额", 0.98f, 100, 100, 180, 140));
		ocr.add(box("1234.56", 0.95f, 200, 120, 260, 160));

		OcrStructuredResult r = parse(OcrFieldSchema.builder("采购合同")
			.field("amount").labels("金额").add()
			.build(), ocr);

		assertEquals("1234.56", r.getValue("amount"));
	}

	@Test
	void parse_horizontalGapLimit_distantSameRowTextNotTakenAsValue() {
		// 标签高 40px → 间距上限 40 × 8 = 320px；远处值间距 840px，超限。
		List<PPOcrV6Result> ocr = new ArrayList<>();
		ocr.add(box("金额", 0.98f, 100, 100, 160, 140));
		ocr.add(box("999999", 0.99f, 1000, 100, 1100, 140));

		OcrStructuredResult r = parse(OcrFieldSchema.builder("采购合同")
			.field("amount").labels("金额").add()
			.build(), ocr);

		assertNull(r.getValue("amount"));
		assertTrue(r.getMissingFields().contains("amount"));
	}

	// ==================================================================
	// 正则兜底
	// ==================================================================

	@Test
	void parse_labelMissing_regexFallbackExtracts() {
		List<PPOcrV6Result> ocr = new ArrayList<>();
		ocr.add(box("622848012345", 0.97f, 100, 100, 300, 140));

		OcrStructuredResult r = parse(OcrFieldSchema.builder("订单")
			.field("orderNo").regex("\\d{12}").add()
			.build(), ocr);

		assertEquals("622848012345", r.getValue("orderNo"));
		assertEquals(FieldSource.PATTERN, r.getFieldConfidences().get("orderNo").source());
	}

	@Test
	void parse_labelValueFailsRegex_fallsBackToRegex() {
		List<PPOcrV6Result> ocr = new ArrayList<>();
		ocr.add(box("订单号", 0.98f, 100, 100, 180, 140));
		ocr.add(box("622848012345", 0.96f, 200, 104, 320, 140));
		ocr.add(box("6228480123456789", 0.96f, 1000, 104, 1200, 140));

		OcrStructuredResult r = parse(OcrFieldSchema.builder("订单")
			.field("orderNo").labels("订单号").regex("\\d{16}").add()
			.build(), ocr);

		assertEquals("6228480123456789", r.getValue("orderNo"));
		assertEquals(FieldSource.PATTERN, r.getFieldConfidences().get("orderNo").source());
	}

	@Test
	void parse_labelValueFailsRegexAndNoFallback_keepsValue() {
		List<PPOcrV6Result> ocr = new ArrayList<>();
		ocr.add(box("订单号", 0.98f, 100, 100, 180, 140));
		ocr.add(box("ABC-123", 0.96f, 200, 104, 320, 140));

		OcrStructuredResult r = parse(OcrFieldSchema.builder("订单")
			.field("orderNo").labels("订单号").regex("\\d{16}").add()
			.build(), ocr);

		assertEquals("ABC-123", r.getValue("orderNo"), "正则兜不到时不得丢值");
	}

	// ==================================================================
	// 归一化
	// ==================================================================

	@Test
	void parse_compactNormalizer_stripsSeparators() {
		List<PPOcrV6Result> ocr = new ArrayList<>();
		ocr.add(box("合同编号", 0.98f, 100, 100, 200, 140));
		ocr.add(box("HT-2024-001", 0.96f, 220, 104, 340, 140));

		OcrStructuredResult r = parse(OcrFieldSchema.builder("采购合同")
			.field("contractNo").labels("合同编号").compact().add()
			.build(), ocr);

		assertEquals("HT2024001", r.getValue("contractNo"));
	}

	@Test
	void parse_dateNormalizer_repairsSeparatorAndPassesRule() {
		List<PPOcrV6Result> ocr = new ArrayList<>();
		ocr.add(box("签订日期", 0.98f, 100, 100, 200, 140));
		ocr.add(box("2017年5月8日", 0.94f, 220, 104, 340, 140));

		OcrStructuredResult r = parse(OcrFieldSchema.builder("采购合同")
			.field("signDate").labels("签订日期").asDate().rules(FieldRule.date()).add()
			.build(), ocr);

		assertEquals("2017-05-08", r.getValue("signDate"));
		assertTrue(r.getFieldConfidences().get("signDate").violations().isEmpty());
		assertFalse(r.isNeedsReview());
	}

	@Test
	void parse_regexAppliesAfterNormalization_invalidValueIsFlagged() {
		// 正则必须作用在"归一化后的输出值"上：值经 compact 归一化后仍不符合 \d{18}，
		// 就不能悄悄放行 —— 记违规、置信度归零、标记需复核。
		List<PPOcrV6Result> ocr = new ArrayList<>();
		ocr.add(box("公民身份号码", 0.98f, 100, 100, 280, 140));
		ocr.add(box("1101051949123100XX", 0.95f, 300, 104, 480, 140));

		OcrStructuredResult r = parse(OcrFieldSchema.builder("身份证")
			.field("idNo").labels("公民身份号码").regex("\\d{18}").compact().add()
			.build(), ocr);

		assertEquals("1101051949123100XX", r.getValue("idNo"), "值仍要保留，不丢");
		FieldConfidence confidence = r.getFieldConfidences().get("idNo");
		assertFalse(confidence.violations().isEmpty(), "不符合声明的正则必须记违规");
		assertEquals(0d, confidence.score(), 1e-9, "违规字段置信度直接归零");
		assertTrue(r.isNeedsReview());
	}

	@Test
	void parse_regexAppliesAfterNormalization_validValuePasses() {
		List<PPOcrV6Result> ocr = new ArrayList<>();
		ocr.add(box("公民身份号码", 0.98f, 100, 100, 280, 140));
		ocr.add(box("11010519491231002X", 0.95f, 300, 104, 480, 140));

		OcrStructuredResult r = parse(OcrFieldSchema.builder("身份证")
			.field("idNo").labels("公民身份号码").regex("\\d{17}X").compact().add()
			.build(), ocr);

		assertEquals("11010519491231002X", r.getValue("idNo"));
		assertTrue(r.getFieldConfidences().get("idNo").violations().isEmpty());
		assertFalse(r.isNeedsReview());
	}

	@Test
	void parse_regexFallbackUsedWhenNormalizedLabelValueFailsRegex() {
		// 标签路径归一化后不符合正则 → 改走正则兜底路径
		List<PPOcrV6Result> ocr = new ArrayList<>();
		ocr.add(box("订单号", 0.98f, 100, 100, 180, 140));
		ocr.add(box("622848012345", 0.96f, 200, 104, 320, 140));
		ocr.add(box("6228480123456789", 0.96f, 1000, 104, 1200, 140));

		OcrStructuredResult r = parse(OcrFieldSchema.builder("订单")
			.field("orderNo").labels("订单号").regex("\\d{16}").add()
			.build(), ocr);

		assertEquals("6228480123456789", r.getValue("orderNo"));
		assertEquals(FieldSource.PATTERN, r.getFieldConfidences().get("orderNo").source());
		assertTrue(r.getFieldConfidences().get("orderNo").violations().isEmpty());
	}

	// ==================================================================
	// 校验位与置信度
	// ==================================================================

	@Test
	void parse_idCardChecksumInvalid_scoreZeroButValueKept() {
		List<PPOcrV6Result> ocr = new ArrayList<>();
		ocr.add(box("公民身份号码", 0.98f, 100, 100, 280, 140));
		ocr.add(box("110105194912310021", 0.90f, 300, 104, 480, 140));

		OcrStructuredResult r = parse(OcrFieldSchema.builder("身份证")
			.field("idNumber").labels("公民身份号码").rules(FieldRule.idCardNumber()).add()
			.build(), ocr);

		assertEquals("110105194912310021", r.getValue("idNumber"), "校验失败也保留值");
		FieldConfidence confidence = r.getFieldConfidences().get("idNumber");
		assertEquals(0d, confidence.score(), 1e-9, "校验失败置信度直接归零");
		assertFalse(confidence.violations().isEmpty());
		assertTrue(r.isNeedsReview());
		assertTrue(r.getNeedingReviewFields().contains("idNumber"));
	}

	@Test
	void parse_idCardChecksumValid_scoreTakesOcrScore() {
		List<PPOcrV6Result> ocr = new ArrayList<>();
		ocr.add(box("公民身份号码", 0.98f, 100, 100, 280, 140));
		ocr.add(box("11010519491231002X", 0.95f, 300, 104, 480, 140));

		OcrStructuredResult r = parse(OcrFieldSchema.builder("身份证")
			.field("idNumber").labels("公民身份号码").rules(FieldRule.idCardNumber()).add()
			.build(), ocr);

		assertEquals("11010519491231002X", r.getValue("idNumber"));
		FieldConfidence confidence = r.getFieldConfidences().get("idNumber");
		assertTrue(confidence.violations().isEmpty());
		assertEquals(0.95d, confidence.score(), 1e-6);
		assertFalse(r.isNeedsReview());
	}

	// ==================================================================
	// 缺失字段与复核触发
	// ==================================================================

	@Test
	void parse_requiredFieldMissing_triggersReviewAndRecordsMissing() {
		List<PPOcrV6Result> ocr = new ArrayList<>();
		ocr.add(box("合同编号", 0.98f, 100, 100, 200, 140));

		OcrStructuredResult r = parse(OcrFieldSchema.builder("采购合同")
			.field("contractNo").labels("合同编号").add()
			.field("amount").labels("金额").add()
			.build(), ocr);

		assertTrue(r.getMissingFields().contains("amount"));
		assertTrue(r.isNeedsReview());
		assertFalse(r.allTrusted());
		assertTrue(r.getReviewReasons().stream().anyMatch(s -> s.contains("amount")));
	}

	@Test
	void parse_optionalFieldMissing_doesNotTriggerReview() {
		List<PPOcrV6Result> ocr = new ArrayList<>();
		ocr.add(box("合同编号", 0.98f, 100, 100, 200, 140));
		ocr.add(box("HT-2024-001", 0.96f, 220, 104, 340, 140));

		OcrStructuredResult r = parse(OcrFieldSchema.builder("采购合同")
			.field("contractNo").labels("合同编号").add()
			.field("remark").labels("备注").optional().add()
			.build(), ocr);

		assertTrue(r.getMissingFields().contains("remark"), "可选字段缺失也要如实记录");
		assertFalse(r.isNeedsReview(), "可选字段缺失不应触发复核");
	}

	// ==================================================================
	// 坐标回填与元信息
	// ==================================================================

	@Test
	void parse_fieldBoxesBackFilled() {
		List<PPOcrV6Result> ocr = new ArrayList<>();
		ocr.add(box("合同编号", 0.98f, 100, 100, 200, 140));
		ocr.add(box("HT-2024-001", 0.96f, 220, 104, 340, 140));

		OcrStructuredResult r = parse(OcrFieldSchema.builder("采购合同")
			.field("contractNo").labels("合同编号").add()
			.build(), ocr);

		List<int[][]> boxes = r.getFieldBoxes().get("contractNo");
		assertNotNull(boxes);
		assertEquals(1, boxes.size());
		assertEquals(220, boxes.get(0)[0][0]);
	}

	@Test
	void parse_resultCarriesDocumentTypeAndRawResults() {
		List<PPOcrV6Result> ocr = new ArrayList<>();
		ocr.add(box("合同编号", 0.98f, 100, 100, 200, 140));

		OcrStructuredResult r = parse(OcrFieldSchema.builder("采购合同")
			.field("contractNo").labels("合同编号").add()
			.build(), ocr);

		assertEquals("采购合同", r.getDocumentType());
		assertEquals(1, r.getRawResults().size());
	}

	@Test
	void parse_rawResultsIsCopied_notSharedWithCaller() {
		List<PPOcrV6Result> ocr = new ArrayList<>();
		ocr.add(box("合同编号", 0.98f, 100, 100, 200, 140));

		OcrStructuredResult r = parse(OcrFieldSchema.builder("采购合同")
			.field("contractNo").labels("合同编号").add()
			.build(), ocr);

		assertNotSame(ocr, r.getRawResults(), "rawResults 必须拷贝，不能与调用方共享同一 list");
	}

	@Test
	void parse_emptyInput_noExceptionAndAllFieldsMissing() {
		OcrStructuredResult r = parse(OcrFieldSchema.builder("采购合同")
			.field("contractNo").labels("合同编号").add()
			.build(), new ArrayList<>());

		assertNull(r.getValue("contractNo"));
		assertTrue(r.getMissingFields().contains("contractNo"));
		assertTrue(r.isNeedsReview());
	}

	// ==================================================================
	// 辅助
	// ==================================================================

	private static OcrStructuredResult parse(OcrFieldSchema schema, List<PPOcrV6Result> ocr) {
		return new OcrStructuredParser(schema).parseResults(ocr);
	}

	/**
	 * 构造一个合成 OCR 框（矩形）。
	 */
	private static PPOcrV6Result box(String text, float score, int x0, int y0, int x1, int y1) {
		return new PPOcrV6Result(text, score,
			new int[][]{{x0, y0}, {x1, y0}, {x1, y1}, {x0, y1}});
	}
}
