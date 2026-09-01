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

package net.dreamlu.mica.ai.ppocr.structured.parser.vehicle;

import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Result;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.ParserTestSupport;
import net.dreamlu.mica.ai.ppocr.utils.CollUtil;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VehicleLicenseParserTest extends ParserTestSupport {

	@Test
	void parse_happyPath() {
		// 模拟一张行驶证的关键 OCR 框
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("号牌号码", 100, 200, 180, 220),
			box("鲁A00000", 200, 205, 280, 220),
			box("车辆类型", 100, 300, 180, 320),
			box("小型普通客车", 200, 305, 320, 320),
			box("所有人", 100, 400, 160, 420),
			box("盛瑞传动股份有限公司", 180, 400, 400, 420),
			box("车辆识别代号", 100, 500, 200, 520),
			box("LJXXXXXXXXXXXXXXX", 220, 505, 400, 520),
			box("发证日期", 100, 600, 180, 620),
			box("2018-02-24", 200, 605, 300, 620)
		);
		VehicleLicenseResult r = parse(new VehicleLicenseParser(null), results);
		assertNotNull(r);
		assertEquals("鲁A00000", r.getPlateNo());
		assertEquals("小型普通客车", r.getVehicleType());
		assertEquals("盛瑞传动股份有限公司", r.getOwner());
		assertEquals("LJXXXXXXXXXXXXXXX", r.getVin());
		assertEquals("2018-02-24", r.getIssueDate());
	}

	@Test
	void parse_distinguishesSameDatesByPosition() {
		// 同值日期位于两个标签右侧：位置匹配天然能区分
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("注册日期", 100, 500, 180, 520),
			box("2018-02-24", 200, 505, 300, 520),
			box("发证日期", 100, 600, 180, 620),
			box("2018-02-24", 200, 605, 300, 620)
		);
		VehicleLicenseResult r = parse(new VehicleLicenseParser(null), results);
		// 只关心 issueDate 落在发证日期右侧
		assertEquals("2018-02-24", r.getIssueDate());
	}

	@Test
	void parse_fallbackForPlateByRegex() {
		// "号牌号码" 标签缺失，按正则从全文兜底
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("京A12345", 100, 200, 200, 220),
			box("所有人", 100, 300, 160, 320),
			box("张三", 180, 300, 220, 320)
		);
		VehicleLicenseResult r = parse(new VehicleLicenseParser(null), results);
		assertEquals("京A12345", r.getPlateNo());
		assertEquals("张三", r.getOwner());
	}

	@Test
	void parse_fallbackForVinBySubstring() {
		// "车辆识别代号" 标签缺失 + 正则兜底也失败 + VIN 带前导点号噪声
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("VIN噪声", 100, 200, 200, 220),
			box(".LLXXXXXXXXXXXXXXX", 220, 200, 500, 220)
		);
		VehicleLicenseResult r = parse(new VehicleLicenseParser(null), results);
		assertEquals("LLXXXXXXXXXXXXXXX", r.getVin());
	}

	@Test
	void parse_returnsNullsForMissingFields() {
		// 输入完全为空
		VehicleLicenseResult r = parse(new VehicleLicenseParser(null), CollUtil.listOf());
		assertNotNull(r);
		assertNull(r.getPlateNo());
		assertNull(r.getOwner());
		assertNull(r.getVehicleType());
		assertNull(r.getVin());
		assertNull(r.getIssueDate());
	}

	@Test
	void parse_fallbackForIssueDateBySubstring() {
		// small 模型场景：注册日期+发证日期被识别成单一文本框 "2018-03-052018-03-05"
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("注册日期", 100, 500, 180, 520),
			box("2018-03-052018-03-05", 200, 505, 500, 520),
			box("发证日期", 100, 600, 180, 620)
		);
		VehicleLicenseResult r = parse(new VehicleLicenseParser(null), results);
		assertEquals("2018-03-05", r.getIssueDate());
	}

	@Test
	void parse_handlesPartialLabelOcr() {
		// 残缺标签 OCR："所有人" 被识别成 "所"
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("所", 100, 400, 130, 420),
			box("李四", 150, 400, 200, 420)
		);
		VehicleLicenseResult r = parse(new VehicleLicenseParser(null), results);
		assertEquals("李四", r.getOwner());
	}

	@Test
	void parse_handlesSplitLabelOcr() {
		// "所有人" 被识别成 "所" + "人" 两个框，值在最右侧
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("所", 56, 124, 89, 141),
			box("人", 90, 127, 103, 138),
			box("京通租赁集团有限公司北京分公司", 115, 126, 364, 152)
		);
		VehicleLicenseResult r = parse(new VehicleLicenseParser(null), results);
		assertEquals("京通租赁集团有限公司北京分公司", r.getOwner());
	}

	@Test
	void parse_ownerFallsBackByLayout() {
		// medium 模型：中文标签「所有人」完全缺失，英文标签片段 "Ou" 无法被 Owner.contains 匹配
		// 触发版面布局兜底：利用「车辆类型」下沿 +「住址」上沿之间的 y 带找最宽文本
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("车辆类型", 209, 94, 255, 107),
			box("小型轿车", 279, 102, 346, 122),
			box("Ou", 59, 139, 73, 145),
			box("京通租赁集团有限公司北京分公司", 114, 125, 363, 153),
			box("住", 58, 157, 71, 169),
			box("址", 93, 157, 106, 171),
			box("北京市朝阳区东四环", 112, 156, 265, 182)
		);
		VehicleLicenseResult r = parse(new VehicleLicenseParser(null), results);
		assertEquals("京通租赁集团有限公司北京分公司", r.getOwner());
		assertEquals("小型轿车", r.getVehicleType());
	}

	@Test
	void parse_trailerPlateEndsWithGua() {
		// 挂车车牌以"挂"结尾（如 "津A0000挂"），PLATE_PATTERN 需兼容
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("号牌号码", 50, 143, 108, 162),
			box("津A0000挂", 115, 147, 205, 170),
			box("车辆类型", 233, 154, 285, 167),
			box("重型集装箱半挂车", 306, 144, 448, 168)
		);
		VehicleLicenseResult r = parse(new VehicleLicenseParser(null), results);
		assertEquals("津A0000挂", r.getPlateNo());
	}

	@Test
	void parse_trailerPlateFallbackByRegex() {
		// 挂车车牌 + "号牌号码" 标签缺失，走正则兜底
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("鲁P0000挂", 100, 200, 220, 230),
			box("车辆类型", 100, 300, 180, 320)
		);
		VehicleLicenseResult r = parse(new VehicleLicenseParser(null), results);
		assertEquals("鲁P0000挂", r.getPlateNo());
	}

	@Test
	void parse_plateMergedWithLabel() {
		// OCR 把"号牌号码"标签和值识别成单框 "号牌号码津A00000"（合并框剥前缀）
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("号牌号码津A00000", 1136, 240, 1345, 271),
			box("车辆类型", 599, 340, 687, 358),
			box("重型半挂牵引车", 718, 323, 926, 359)
		);
		VehicleLicenseResult r = parse(new VehicleLicenseParser(null), results);
		assertEquals("津A00000", r.getPlateNo());
	}

	@Test
	void parse_plateInMergedNoisyText() {
		// 车牌嵌在长合并框里（"号牌号码鲁P0000挂检验有效期至2026年04月鲁"），子串搜索兜底
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("号牌号码鲁P0000挂检验有效期至2026年04月鲁", 100, 200, 500, 230),
			box("车辆类型", 100, 300, 180, 320)
		);
		VehicleLicenseResult r = parse(new VehicleLicenseParser(null), results);
		assertEquals("鲁P0000挂", r.getPlateNo());
	}

	@Test
	void parse_plateLabelFirstCharMisreadRecoversByFullImageSubstring() {
		// 主页"号牌号码"标签剥出值"售0SAF1挂"（首字"售"被 OCR 误识自"鲁"），
		// 严格正则 PLATE_PATTERN 因首字非省简称拒收；
		// 但全图其它位置有"鲁A12345"格式的合法车牌（来自检验记录栏/副页等），子串搜索兜底命中
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("号牌号码售0SAF1挂", 100, 200, 500, 230),
			box("检验有效期至2026年04月鲁", 100, 800, 500, 830),
			box("鲁A12345", 100, 850, 300, 880)
		);
		VehicleLicenseResult r = parse(new VehicleLicenseParser(null), results);
		assertEquals("鲁A12345", r.getPlateNo());
	}

	@Test
	void parse_vehicleTypeMergedWithLabel() {
		// OCR 把"车辆类型"标签和值识别成单框 "车辆类型重型集装箱半挂车"（合并框剥前缀）
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("车辆类型重型集装箱半挂车", 18, 101, 193, 120),
			box("号牌号码", 50, 143, 108, 162),
			box("津A0000挂", 115, 147, 205, 170)
		);
		VehicleLicenseResult r = parse(new VehicleLicenseParser(null), results);
		assertEquals("重型集装箱半挂车", r.getVehicleType());
	}

	// ==================================================================
	// 批量复盘 batch-ocr-vehicle-tiny2（100 张）车辆类型失败场景回归
	// 值字段均为通用车型/合成数据，box 坐标与真实样本一致
	// ==================================================================

	@Test
	void parse_vehicleTypeEnglishLabelMergedWithValue() {
		// 批量复盘 bug：tiny2 档把英文标签 "VehicleType" 误识成 "VehiclcTyre" 并与
		// 中文值合并成单框，值框定位正确但文本带噪声前缀，须从值内提取（6/100 张形态）
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("号牌号码", 95, 420, 188, 454),
			box("津A00000", 92, 439, 303, 480),
			box("车辆类型", 393, 430, 484, 462),
			box("VehiclcTyre重型半挂牵引车", 390, 450, 716, 496)
		);
		VehicleLicenseResult r = parse(new VehicleLicenseParser(null), results);
		assertEquals("重型半挂牵引车", r.getVehicleType());
	}

	@Test
	void parse_vehicleTypeEnglishLabelVariants() {
		// 同类形态变体："VehicleType"/"Va"/"Ven" 前缀合并框，均应从值内提取
		for (String merged : new String[]{"VehicleType重型半挂牵引车", "Va重型半挂牵引车", "Ven重型半挂牵引车"}) {
			List<PPOcrV6Result> results = CollUtil.listOf(
				box("车辆类型", 393, 430, 484, 462),
				box(merged, 390, 450, 716, 496)
			);
			VehicleLicenseResult r = parse(new VehicleLicenseParser(null), results);
			assertEquals("重型半挂牵引车", r.getVehicleType(), merged);
		}
	}

	@Test
	void parse_vehicleTypeChinesePrefixNoiseMergedWithValue() {
		// 中文噪声前缀合并框："发重型集装箱半挂车"（"车辆类型"中间字误识成"发"）
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("车辆类型", 393, 430, 484, 462),
			box("发重型集装箱半挂车", 390, 450, 716, 496)
		);
		VehicleLicenseResult r = parse(new VehicleLicenseParser(null), results);
		assertEquals("重型集装箱半挂车", r.getVehicleType());
	}

	@Test
	void parse_vehicleTypeTrailingDotNoise() {
		// 值尾噪声："重型集装箱半挂车." → 值内提取剥掉尾点
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("车辆类型", 393, 430, 484, 462),
			box("重型集装箱半挂车.", 390, 450, 716, 496)
		);
		VehicleLicenseResult r = parse(new VehicleLicenseParser(null), results);
		assertEquals("重型集装箱半挂车", r.getVehicleType());
	}

	@Test
	void parse_vehicleTypeLabelWithColonStripsToNull() {
		// 批量复盘 bug："车辆类型：" 合并框剥出 "："，纯噪声不应作为值返回；
		// 正确值在右侧独立框时走正则兜底命中
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("车辆类型：", 94, 619, 223, 642),
			box("重型厢式半挂车", 240, 619, 460, 642)
		);
		VehicleLicenseResult r = parse(new VehicleLicenseParser(null), results);
		assertEquals("重型厢式半挂车", r.getVehicleType());
	}

	@Test
	void parse_vehicleTypeLabelWithColonOnlyYieldsNull() {
		// "车辆类型：" 合并框且无其他值框 → 纯噪声 "：" 置 null 而非透传
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("车辆类型：", 94, 619, 223, 642)
		);
		VehicleLicenseResult r = parse(new VehicleLicenseParser(null), results);
		assertNull(r.getVehicleType());
	}

	@Test
	void parse_vehicleTypeFuzzyCorrectedByVocabulary() {
		// 批量复盘 bug（OCR 级）：值内单字误识 "重型集装箱半技车"（Levenshtein=1）、
		// "重盟半挂章引车"（distance=2）、"重型集装销半社车"（distance=2），
		// 距 GA 802 标准词表极近，高置信纠回
		String[][] cases = {
			{"重型集装箱半技车", "重型集装箱半挂车"},
			{"重盟半挂章引车", "重型半挂牵引车"},
			{"重型集装销半社车", "重型集装箱半挂车"}
		};
		for (String[] c : cases) {
			List<PPOcrV6Result> results = CollUtil.listOf(
				box("车辆类型", 393, 430, 484, 462),
				box(c[0], 390, 450, 716, 496)
			);
			VehicleLicenseResult r = parse(new VehicleLicenseParser(null), results);
			assertEquals(c[1], r.getVehicleType(), c[0]);
		}
	}

	@Test
	void parse_vehicleTypeVocabularyDoesNotCorrectUnrelatedText() {
		// 词表纠错安全护栏：标题（旋转图场景下被误当值）等长文本距离远超阈值，
		// 不得被误纠成任何词表条目；短噪声（"核定入数"）也不参与纠错
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("车辆类型", 430, 396, 457, 475),
			box("中华人民共和国机动车行驶证", 464, 241, 511, 619)
		);
		VehicleLicenseResult r = parse(new VehicleLicenseParser(null), results);
		// 旋转图标题被误当值属布局问题（已知局限），但词表不得再错上加错
		assertEquals("中华人民共和国机动车行驶证", r.getVehicleType());

		List<PPOcrV6Result> shortNoise = CollUtil.listOf(
			box("车辆类型", 192, 287, 244, 301),
			box("核定入数", 260, 287, 360, 301)
		);
		VehicleLicenseResult r2 = parse(new VehicleLicenseParser(null), shortNoise);
		// "核定入数" 含中文且不命中词表 → 原样保留（与旧行为一致，不引入回归）
		assertEquals("核定入数", r2.getVehicleType());
	}

	@Test
	void parse_vehicleTypeLightTruckPassesThrough() {
		// "轻型" 级别词回归：值正则此前缺失 "轻型" 前缀，补齐后应正常完整命中
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("车辆类型", 393, 430, 484, 462),
			box("轻型厢式货车", 390, 450, 616, 496)
		);
		VehicleLicenseResult r = parse(new VehicleLicenseParser(null), results);
		assertEquals("轻型厢式货车", r.getVehicleType());
	}

	@Test
	void parse_ownerPartialLabelSuoRen() {
		// OCR 把"所有人"识别成残缺"所人"（缺"有"），值在右侧
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("所人", 231, 337, 324, 372),
			box("黄书俭", 326, 358, 423, 396)
		);
		VehicleLicenseResult r = parse(new VehicleLicenseParser(null), results);
		assertEquals("黄书俭", r.getOwner());
	}

	@Test
	void parse_ownerMergedWithRenPrefix() {
		// OCR 把"所有人"识别成"人"并与公司名合并成单框 "人莘县顺发物流有限公司"
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("人莘县顺发物流有限公司", 388, 221, 443, 510),
			box("号牌号码", 50, 143, 108, 162)
		);
		VehicleLicenseResult r = parse(new VehicleLicenseParser(null), results);
		assertEquals("莘县顺发物流有限公司", r.getOwner());
	}

	@Test
	void parse_ownerRenPrefixProtectedForRenmin() {
		// "中国人民财产保险股份有限公司" 是合法以"人"开头的公司名，不应剥前缀
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("所有人", 100, 400, 160, 420),
			box("中国人民财产保险股份有限公司", 180, 400, 400, 420)
		);
		VehicleLicenseResult r = parse(new VehicleLicenseParser(null), results);
		assertEquals("中国人民财产保险股份有限公司", r.getOwner());
	}

	@Test
	void parse_ownerLayoutFallbackExcludesNoise() {
		// 布局兜底应排除车辆类型值（"重型半挂牵引车"）等噪声，选中真正的所有人
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("号牌号码", 100, 200, 180, 220),
			box("冀A00000", 200, 205, 280, 220),
			box("车辆类型", 516, 297, 605, 326),
			box("重型半挂牵引车", 638, 317, 851, 362),
			box("所人", 231, 337, 324, 372),
			box("黄书俭", 326, 358, 423, 396),
			box("住址", 228, 396, 320, 427),
			box("河北省晋州市祁底镇管洽村黄家口街11号", 322, 416, 877, 451)
		);
		VehicleLicenseResult r = parse(new VehicleLicenseParser(null), results);
		assertEquals("黄书俭", r.getOwner());
	}

	@Test
	void parse_ownerExcludesVehicleTypeMergedBox() {
		// 批量复盘 bug（drivingLicensePic20250820092151.png）：OCR 把"车辆类型"标签与值合并成单框
		// "VehiclcType重型半挂牵引车"，布局兜底曾因它最宽而误当所有人；isOwnerNoise 改为含车辆类型值
		// 即用 find() 排除，不再返回车辆类型串味的伪所有人（该图所有人值本身 OCR 缺失）
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("号牌号码", 100, 389, 200, 419),
			box("车辆类型", 383, 395, 484, 422),
			box("VehiclcType重型半挂牵引车", 379, 415, 772, 451),
			box("住", 48, 522, 131, 553),
			box("址", 127, 525, 156, 549),
			box("山东省寿光市纪台镇桃园村190号", 161, 543, 528, 581)
		);
		VehicleLicenseResult r = parse(new VehicleLicenseParser(null), results);
		// 车辆类型串味的伪所有人必须被排除；不再返回 "VehiclcType重型半挂牵引车"
		assertNotEquals("VehiclcType重型半挂牵引车", r.getOwner());
		assertTrue(r.getOwner() == null || !r.getOwner().contains("半挂牵引车"),
			"所有人不应包含车辆类型值: " + r.getOwner());
	}

	@Test
	void parse_realImageOcrTinyVehicle4() {
		// 真实样本回归（值字段已脱敏，box 坐标与真实样本一致）：
		// 关键回归点 —— 「label + 值」被 OCR 合并识别成单框时，解析器应能剥出值。
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("中华人民共和国机动车行驶证", 699, 313, 2255, 465),
			box("VehicleLicenseofthePeople''sRepublicofChina", 710, 431, 2251, 520),
			box("号牌号码", 353, 538, 655, 629),
			box("豫A*****R9", 671, 538, 1111, 660),
			box("车辆类型", 1331, 538, 1628, 621),
			box("小型普通客车", 1734, 550, 2376, 689),
			box("PlateNo.", 359, 617, 620, 685),
			box("VehicleType", 1326, 616, 1629, 685),
			box("所有人张*", 338, 727, 891, 856),
			box("Owner", 360, 822, 549, 880),
			box("址XX县XX村", 574, 916, 1413, 1066),
			box("住", 358, 938, 466, 1018),
			box("Address", 368, 1024, 586, 1075),
			box("使用性质", 361, 1122, 665, 1212),
			box("非营运", 672, 1125, 991, 1244),
			box("品牌型号", 1166, 1121, 1468, 1210),
			box("XX汽车牌XXXXXXX", 1498, 1131, 2578, 1273),
			box("UseCharacter", 367, 1206, 679, 1261),
			box("Model", 1169, 1202, 1350, 1263),
			box("XX省XX市", 385, 1322, 899, 1450),
			box("车辆识别代号", 937, 1320, 1364, 1401),
			box("XXXXXXXXXXXXXXXXX", 1432, 1325, 2346, 1459),
			box("VIN", 939, 1396, 1073, 1459),
			box("XX市XX交", 390, 1494, 906, 1624),
			box("发动机号码", 944, 1504, 1305, 1587),
			box("***533", 1352, 1529, 1693, 1647),
			box("EngineNo.", 952, 1583, 1226, 1653),
			box("通警XX队", 390, 1674, 896, 1793),
			box("注册日期", 952, 1694, 1226, 1770),
			box("发证日期", 1807, 1692, 2065, 1772),
			box("2018-03-12", 1220, 1713, 1759, 1843),
			box("2018-03.13.", 2058, 1730, 2608, 1862),
			box("RegisterDate", 945, 1774, 1221, 1831),
			box("IssueDate", 1813, 1771, 2054, 1827)
		);
		VehicleLicenseResult r = parse(new VehicleLicenseParser(null), results);
		assertEquals("张*", r.getOwner());
		assertEquals("小型普通客车", r.getVehicleType());
		// plateNo 因脱敏字符不符合车牌号正则被解析器视为噪声丢弃，不作硬断言。
	}

	@Test
	void parse_ownerMangledSuoXRenPrefixCompany() {
		// 批量复盘 bug：OCR 把"所有人"中间的"有"误识成"精"，与公司名合并成单框
		// "所精人山东XX物流有限公司"，旧逻辑不剥 3 字残缺前缀（公司名已脱敏）
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("号牌号码", 295, 677, 479, 737),
			box("鲁P*****挂", 500, 681, 796, 768),
			box("所精人山东XX物流有限公司", 276, 782, 1282, 921)
		);
		VehicleLicenseResult r = parse(new VehicleLicenseParser(null), results);
		assertEquals("山东XX物流有限公司", r.getOwner());
	}

	@Test
	void parse_ownerMangledSuoXRenPrefixPerson() {
		// 批量复盘 bug：残缺变体"所X人"对人名同样生效（人名已脱敏为合成值）
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("所想人王某某", 231, 337, 324, 372)
		);
		VehicleLicenseResult r = parse(new VehicleLicenseParser(null), results);
		assertEquals("王某某", r.getOwner());
	}

	@Test
	void parse_ownerLayoutFallbackStripsMangledPrefix() {
		// 批量复盘 bug 的完整触发路径：残缺前缀合并框走"版面布局兜底"时，
		// normalizeOwnerMatch 也必须剥掉 3 字残缺前缀（公司名已脱敏）
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("车辆类型", 891, 701, 1080, 768),
			box("重型集装箱半挂车", 1152, 718, 1679, 833),
			box("所身人XX市XX物流有限公司", 276, 782, 1282, 921),
			box("住", 358, 938, 466, 1018),
			box("址XX省XX市XX区", 409, 912, 1567, 1062)
		);
		VehicleLicenseResult r = parse(new VehicleLicenseParser(null), results);
		assertEquals("XX市XX物流有限公司", r.getOwner());
	}

	@Test
	void parse_plateNormalizesIOMisread() {
		// 批量复盘 bug（OCR 级）：tiny 档把车牌 1/0 误识为 I/O（4/100 张，
		// 如 "鲁GOT77挂"/"津ALIMO挂"）；车牌合法字符集不含 I/O，确定性归一化 I→1、O→0
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("号牌号码", 155, 201, 286, 241),
			box("津ALIMO挂", 289, 208, 498, 259),
			box("车辆类型", 573, 197, 699, 234),
			box("重型集装箱半挂车", 739, 205, 1099, 255)
		);
		VehicleLicenseResult r = parse(new VehicleLicenseParser(null), results);
		assertEquals("津AL1M0挂", r.getPlateNo());
	}

	@Test
	void parse_plateIOMisreadInMergedBox() {
		// 车牌嵌在长合并框（子串搜索兜底路径）中时同样归一化
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("号牌号码鲁P0IY25挂检验有效期至2026年04月鲁", 100, 200, 500, 230)
		);
		VehicleLicenseResult r = parse(new VehicleLicenseParser(null), results);
		assertEquals("鲁P01Y25挂", r.getPlateNo());
	}

	@Test
	void parse_vinNormalizesIOMisread() {
		// 批量复盘 bug（OCR 级）：tiny 档把 VIN 中的 1/0 误识为 I/O（19/100 张）；
		// VIN（ISO 3779）合法字符集不含 I/O/Q，确定性归一化 I→1、O→0（合成 VIN）
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("车辆识别代号", 407, 533, 586, 569),
			box("LXIXOX1X2X3X4X5X6", 620, 535, 1011, 586),
			box("VIN", 406, 564, 466, 596)
		);
		VehicleLicenseResult r = parse(new VehicleLicenseParser(null), results);
		assertEquals("LX1X0X1X2X3X4X5X6", r.getVin());
	}

	@Test
	void parse_vinNormalizesQAsZero() {
		// Q 在 tiny 档模型下几乎必为 0 的误识，按"0"做确定性归一化救场，
		// 归一化结果应通过严格 ISO 3779 校验（17 位）
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("LA99FR34TQTSD0002", 620, 535, 1011, 586)
		);
		VehicleLicenseResult r = parse(new VehicleLicenseParser(null), results);
		assertEquals("LA99FR34T0TSD0002", r.getVin());
	}

	@Test
	void parse_vinRejectsWhenQNormalizeStillInvalid() {
		// 17 位含 I/O/Q 的串经归一化后全变成严格字符集合法字符（I→1、O→0、Q→0），
		// 因此严格正则不会拒收这种"误识拯救"路径；只有"长度 18+ / 纯字符集外字符"才会真正拒收。
		// 这里改测:归一化后仍含 X? 不,X 在 S-Z 段是合法字符。改为: 输入含 ? 等非字母数字字符,
		// 初筛 [A-Z0-9]{17} matches() 失败,直接拒收。
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("LA99FR34T0TSD000?", 620, 535, 1011, 586)
		);
		VehicleLicenseResult r = parse(new VehicleLicenseParser(null), results);
		assertNull(r.getVin());
	}

	@Test
	void parse_vinNormalizesIOMisreadWithDotNoise() {
		// 点号噪声清理兜底路径产出 VIN 后同样归一化（I→1）
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("噪声", 100, 100, 200, 120),
			box("LXI.XOX2X3X4X5X6X7", 100, 500, 600, 520)
		);
		VehicleLicenseResult r = parse(new VehicleLicenseParser(null), results);
		assertEquals("LX1X0X2X3X4X5X6X7", r.getVin());
	}
}
