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

import lombok.extern.slf4j.Slf4j;
import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Engine;
import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Result;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.BaseStructuredParser;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.LabelMatcher;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.LabelMatcher.LabeledMatch;
import net.dreamlu.mica.ai.ppocr.utils.CollUtil;

import static net.dreamlu.mica.ai.ppocr.structured.parser.core.LabelMatcher.maxY;
import static net.dreamlu.mica.ai.ppocr.structured.parser.core.LabelMatcher.minY;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 行驶证 OCR 结构化解析器。
 *
 * <p>采用"标签定位 + 位置匹配"策略：对每个字段标签（号牌号码/车辆类型/所有人/车辆识别代号/发证日期），
 * 找到标签框后，在 x 起点位于标签右边缘右侧（容忍边界 1px 相接）、y 范围与标签框重叠的
 * 候选值框中，取最靠左（x 最小）的文本作为字段值。
 *
 * <p>输出结果会填充 {@code VehicleLicenseResult#getRawResults()}（完整 OCR 结果）
 * 与 {@code VehicleLicenseResult#getFieldBoxes()}（字段名 → box 坐标列表），
 * 方便调用方在页面上复原并高亮对应字段。
 */
@Slf4j
public class VehicleLicenseParser extends BaseStructuredParser<VehicleLicenseResult> {

	// ==================================================================
	// 正则常量
	// ==================================================================

	/**
	 * 省简称闭集（GA 36.1《机动车号牌》使用的 31 个省级行政区简称 + 使/领/港/澳/学/警 等特殊前缀）。
	 * 中国大陆车牌首字必为其中之一；用闭集取代全汉字区，可过滤 OCR 把品牌型号"牌"、误识省份"售"等当车牌首字。
	 */
	private static final String PLATE_PROVINCES =
		"京津沪渝冀豫云辽黑湘皖鲁新苏浙赣鄂桂甘晋蒙陕吉闽贵粤青藏川宁琼使领港澳学警";
	/**
	 * 车牌正则：首字限定为合法省简称，兼容普通车牌（省简称+字母+5~6位数字字母）
	 * 与挂车车牌（省简称+字母+4~5位数字字母+挂）。如 "鲁A12345"、"津A0000挂"、"京A0000挂"。
	 */
	private static final Pattern PLATE_PATTERN =
		Pattern.compile("[" + PLATE_PROVINCES + "][A-Z][A-Z0-9]{4,5}挂?");
	/**
	 * 车辆识别代号（VIN）初筛正则：宽容 17 位字母数字，用于 OCR 噪声场景兜底命中候选串。
	 * 含 I/O 时由 {@link #normalizeVinMatch} 做 I→1、O→0 归一化后用严格正则校验。
	 */
	private static final Pattern VIN_PATTERN = Pattern.compile("[A-Z0-9]{17}");
	/**
	 * VIN 严格校验正则（ISO 3779）：字符集为 [A-HJ-NPR-Z0-9]（不含 I/O/Q），
	 * 用于归一化后校验清洗结果。Q 不接受：VIN 中出现 Q 几乎必为误识（可能误识自 0 或 O，映射不确定）。
	 */
	private static final Pattern VIN_PATTERN_STRICT = Pattern.compile("[A-HJ-NPR-Z0-9]{17}");
	/**
	 * 日期：yyyy-MM-dd。
	 */
	private static final Pattern DATE_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
	/**
	 * 纯英文/空白（用于车辆类型下沿的兜底判断）。
	 */
	private static final Pattern ENGLISH_TEXT_PATTERN = Pattern.compile("[A-Za-z\\s]+");
	/**
	 * 纯英文/空白/点号（布局兜底时排除的噪声文本）。
	 */
	private static final Pattern ENGLISH_TEXT_DOT_PATTERN = Pattern.compile("[A-Za-z\\s.]+");
	/**
	 * 含中文字符（公司名/人名特征）。
	 */
	private static final Pattern CHINESE_TEXT_PATTERN = Pattern.compile(".*[\\u4e00-\\u9fa5].*");
	/**
	 * 车辆类型值（如 "重型半挂牵引车"、"重型集装箱半挂车"）。
	 */
	private static final Pattern VEHICLE_TYPE_VALUE_PATTERN =
		Pattern.compile("(重型|中型|轻型|小型|大型|微型|普通).*(牵引车|客车|轿车|货车|挂车|面包车|专用车)");
	/**
	 * 行驶证车辆类型标准词表（GA 802《机动车类型 术语和定义》常见取值）。
	 * 用于对 OCR 单字误识的近正确值做模糊纠错（如 "重型集装箱半技车" → "重型集装箱半挂车"）。
	 */
	private static final List<String> VEHICLE_TYPE_VOCABULARY = CollUtil.listOf(
		// 牵引车
		"重型半挂牵引车", "中型半挂牵引车", "轻型半挂牵引车",
		// 半挂车
		"重型集装箱半挂车", "重型厢式半挂车", "重型仓栅式半挂车", "重型罐式半挂车",
		"重型平板半挂车", "重型低平板半挂车", "重型自卸半挂车", "重型栏板半挂车",
		"中型集装箱半挂车", "中型厢式半挂车", "中型罐式半挂车", "中型自卸半挂车",
		// 货车
		"重型厢式货车", "重型仓栅式货车", "重型罐式货车", "重型平板货车", "重型自卸货车", "重型栏板货车",
		"中型厢式货车", "中型仓栅式货车", "中型罐式货车", "中型自卸货车", "中型栏板货车",
		"轻型厢式货车", "轻型仓栅式货车", "轻型罐式货车", "轻型自卸货车", "轻型栏板货车", "轻型多用途货车",
		"微型厢式货车", "微型罐式货车", "微型自卸货车", "微型栏板货车",
		// 客车 / 轿车
		"大型普通客车", "中型普通客车", "小型普通客车", "微型普通客车",
		"小型越野客车", "小型专用客车", "小型轿车", "微型轿车", "小型面包车",
		// 专项作业车
		"重型专项作业车", "中型专项作业车", "轻型专项作业车"
	);
	/** 车辆类型词表纠错：最小编辑距离阈值（超过则视为不同类型，不纠） */
	private static final int VEHICLE_TYPE_FUZZY_MAX_DISTANCE = 2;
	/** 车辆类型词表纠错：参与纠错的最小值长度（过短的噪声易误纠） */
	private static final int VEHICLE_TYPE_FUZZY_MIN_LENGTH = 5;
	/**
	 * 地址特征关键字（省市区县路街道号镇村）。
	 */
	private static final Pattern ADDRESS_KEYWORD_PATTERN = Pattern.compile(".*[省市区县路街道号镇村].*");

	// ==================================================================
	// 字段标签常量
	// ==================================================================

	private static final String LABEL_PLATE_NO = "号牌号码";
	private static final String LABEL_OWNER = "所有人";
	/** OCR 常把"所有人"识别成残缺"所人"（缺"有"） */
	private static final String LABEL_OWNER_PARTIAL = "所人";
	private static final String LABEL_OWNER_EN = "Owner";
	private static final String LABEL_VEHICLE_TYPE = "车辆类型";
	private static final String LABEL_VIN = "车辆识别代号";
	private static final String LABEL_ISSUE_DATE = "发证日期";

	// ==================================================================
	// 布局兜底常量
	// ==================================================================

	/** 车辆类型标签候选（中文/英文） */
	private static final String[] VEHICLE_TYPE_LABELS = {LABEL_VEHICLE_TYPE, "VehicleType"};
	/** 住址标签候选（含英文别名） */
	private static final String[] ADDRESS_LABELS = {"住址", "住", "址", "Address", "Adder"};
	/** 残缺"所有人"前缀（OCR 把"所有人"误识成"人"/"有人"/"所人"/"所X人"并与值合并） */
	private static final String[] OWNER_PARTIAL_PREFIXES = {"有人", "所人", "人"};
	/**
	 * 残缺"所有人"前缀正则：兼容 2 字"所人"与 3 字变体"所X人"
	 * （OCR 把中间的"有"误识成"精/想/身/看/复"等任意汉字，如 "所精人山东XX物流有限公司"）。
	 */
	private static final Pattern OWNER_MANGLED_PREFIX_PATTERN = Pattern.compile("^所.?人");
	/** 保护"中国人民…"等合法公司名：剥"人"后以"国"开头则拒绝 */
	private static final String RENMIN_GUARD = "国";
	/** 地址前缀（"址山东省…"） */
	private static final String ADDRESS_PREFIX = "址";
	/** 公司特征关键字：含则视为公司名而非地址 */
	private static final Set<String> COMPANY_KEYWORDS = CollUtil.newHashSet(
		"有限公司", "运输", "物流", "租赁", "商贸", "个体"
	);
	/** 所有人版面布局兜底时需排除的已知标签 / 标题 / 英文标签噪声 */
	private static final Set<String> OWNER_NOISE_LABELS = CollUtil.newHashSet(
		LABEL_PLATE_NO, LABEL_VEHICLE_TYPE, LABEL_VIN, "发动机号码", "注册日期", LABEL_ISSUE_DATE,
		"使用性质", "品牌型号", "检验有效期", "强制报废期止", "档案编号", "核定载人数",
		"总质量", "整备质量", "核定载质量", "外廓尺寸", "准牵引总质量", "检验记录",
		"中华人民共和国机动车行驶证",
		"PlateNo", "VehicleType", LABEL_OWNER_EN, "Model", "Address", "UseCharacter",
		"VIN", "EngineNo", "RegisterDate", "IssueDate"
	);

	/**
	 * 构造行驶证解析器，绑定推理引擎。
	 *
	 * @param engine PP-OCRv6 推理引擎；可为 null（仅在仅调用 {@link #parseResults(List)} 时）
	 */
	public VehicleLicenseParser(PPOcrV6Engine engine) {
		super(engine);
	}

	@Override
	public VehicleLicenseResult parseResults(List<PPOcrV6Result> results) {
		VehicleLicenseResult result = new VehicleLicenseResult();
		// 塞原始 OCR 结果，供调用方做可视化
		result.setRawResults(new ArrayList<>(results));

		// 1. 车牌
		LabeledMatch plateMatch = parsePlateNo(results);
		result.setPlateNo(plateMatch.value());
		LabelMatcher.applyFieldBox(result, "plateNo", plateMatch);

		// 2. 所有人
		LabeledMatch ownerMatch = parseOwner(results);
		result.setOwner(ownerMatch.value());
		LabelMatcher.applyFieldBox(result, "owner", ownerMatch);

		// 3. 车辆类型
		LabeledMatch vehicleTypeMatch = parseVehicleType(results);
		result.setVehicleType(vehicleTypeMatch.value());
		LabelMatcher.applyFieldBox(result, "vehicleType", vehicleTypeMatch);

		// 4. VIN
		LabeledMatch vinMatch = parseVin(results);
		result.setVin(vinMatch.value());
		LabelMatcher.applyFieldBox(result, "vin", vinMatch);

		// 5. 发证日期
		LabeledMatch dateMatch = parseIssueDate(results);
		result.setIssueDate(dateMatch.value());
		LabelMatcher.applyFieldBox(result, "issueDate", dateMatch);

		return result;
	}

	// ==================================================================
	// 各字段解析
	// ==================================================================

	/**
	 * 解析车牌：号牌号码标签定位 → 全图子串搜索兜底。
	 *
	 * <p>省简称闭集校验由 {@link #PLATE_PATTERN} 承担；品牌型号"牌"标记的串味由
	 * {@link #matchPlateSubstring} 的"牌"字相邻前缀跳过规则处理。
	 *
	 * <p>当合并框"号牌号码售0SAF1挂"等场景下,标签剥出值因首字非省简称被严格正则拒,
	 * 此时转到全图子串搜索兜底:其它位置(检验记录栏、副页等)如有合法省简称车牌可直接命中。
	 */
	private static LabeledMatch parsePlateNo(List<PPOcrV6Result> results) {
		// 1) 主页面号牌号码标签定位（合并框剥前缀 / 标签右侧值框 / 正则兜底）
		LabeledMatch match = strictPlateFromLabel(results, LABEL_PLATE_NO);
		if (!match.hasValue()) {
			// 2) 全图子串搜索兜底（标签缺失或主页号牌号码 OCR 首字误识时）
			match = matchPlateSubstring(results, PLATE_PATTERN);
		}
		return normalizeAmbiguousAlnumMatch(match, "车牌");
	}

	/**
	 * 号牌号码标签定位 + 严格省简称校验。
	 *
	 * <p>合并框"号牌号码鲁A12345"或"号牌号码售0SAF1挂"(首字 OCR 误识)都先经合并框剥前缀;
	 * 严格正则兜底时若首字非省简称(如"售"等 OCR 误识),置 null 等待全图子串搜索兜底;
	 * 合并框"号牌号码售0SAF1挂"中"售"被误识自"鲁",但全图其它位置若有合法车牌(检验记录栏等)可救场。
	 */
	private static LabeledMatch strictPlateFromLabel(List<PPOcrV6Result> results, String label) {
		LabeledMatch m = LabelMatcher.matchValueFromPrefixWithBox(results, label);
		if (m.hasValue() && PLATE_PATTERN.matcher(m.value()).matches()) {
			return m;
		}
		// 合并框剥出值首字非省简称(如"售"误识"鲁")时,放弃该候选,走标签右侧值框定位
		LabeledMatch m2 = LabelMatcher.matchValueWithBox(results, label);
		if (m2.hasValue() && PLATE_PATTERN.matcher(m2.value()).matches()) {
			return m2;
		}
		// 标签右侧值框也不合法(同合并框、OCR 误识)时,返回 null 让 parsePlateNo 走全图子串搜索
		return LabeledMatch.textOnly(null);
	}

	/**
	 * 全图子串搜索车牌，排除品牌型号字段（OCR 把品牌型号值印成"XX牌…"，含"牌"标记，非车牌）。
	 *
	 * @param results OCR 结果列表
	 * @param pattern 车牌正则（严格或宽松）
	 * @return 首个命中的车牌值 + 值框；无匹配时返回仅含 null value 的 LabeledMatch
	 */
	private static LabeledMatch matchPlateSubstring(List<PPOcrV6Result> results, Pattern pattern) {
		return LabelMatcher.matchSubstringWithBox(results, text -> {
			Matcher m = pattern.matcher(text);
			while (m.find()) {
				// 品牌型号字段值形如"豪沃牌鲁A12345…"，"牌"后是车型代码而非车牌首字，
				// 仅当候选车牌紧邻前缀字符"牌"时才跳过；不应 blanket 排除整框（否则会漏掉
				// "号牌号码鲁A12345…"这类标签与值合并框中的真实车牌）。
				if (m.start() > 0 && text.charAt(m.start() - 1) == '牌') continue;
				return m.group();
			}
			return null;
		});
	}

	/**
	 * 解析所有人：合并框（"所有人xxx"）→ 中文标签 → 残缺标签"所人" → 残缺前缀"人xxx"合并框 → 英文别名 → 版面布局兜底。
	 *
	 * <p>OCR 常把"所有人"+"姓名"识别成单框"所有人郑昆"——先按合并框剥前缀；
	 * 也可能识别成残缺"所人"（缺"有"）或"人"+"公司名"合并框。
	 */
	private static LabeledMatch parseOwner(List<PPOcrV6Result> results) {
		LabeledMatch match = LabelMatcher.matchValueFromPrefixWithBox(results, LABEL_OWNER);
		if (!match.hasValue()) {
			match = LabelMatcher.matchValueWithBox(results, LABEL_OWNER);
		}
		if (!match.hasValue()) {
			match = LabelMatcher.matchValueWithBox(results, LABEL_OWNER_PARTIAL);
			if (match.hasValue()) {
				log.debug("行驶证解析：所有人 按残缺标签\"所人\"命中 \"{}\"", match.value());
			}
		}
		if (!match.hasValue()) {
			// 残缺前缀"人xxx"合并框（如 "人莘县顺发物流有限公司"）优先于英文标签，
			// 因为旋转图中 Owner 标签右侧可能命中噪声片段（如 "国"）。
			match = matchOwnerByPartialPrefix(results);
			if (match.hasValue()) {
				log.debug("行驶证解析：所有人 按残缺前缀\"人\"剥值命中 \"{}\"", match.value());
			}
		}
		if (!match.hasValue()) {
			match = LabelMatcher.matchValueWithBox(results, LABEL_OWNER_EN);
			if (match.hasValue()) {
				log.debug("行驶证解析：所有人 按英文标签 Owner fallback 命中 \"{}\"", match.value());
			} else {
				match = matchOwnerByLayoutFallback(results);
				if (match.hasValue()) {
					log.debug("行驶证解析：所有人 按版面布局 fallback 命中 \"{}\"", match.value());
				}
			}
		}
		// Owner 标签命中合并框（"人莘县顺发物流有限公司"）时，值仍带残缺"人"前缀，统一剥除
		return normalizeOwnerMatch(match);
	}

	/**
	 * 解析车辆类型：合并框剥前缀（"车辆类型重型集装箱半挂车"）+ 值校验 + 值内提取
	 * + 标签定位 + 正则兜底 + 子串搜索兜底 + 标准词表纠错。
	 *
	 * <p>OCR 常见噪声形态（批量复盘 tiny2 档 100 张归纳）：
	 * <ul>
	 *   <li>英文标签误识且与值合并成单框："VehiclcTyre重型半挂牵引车"、"VehicleType重型半挂牵引车"，
	 *       此时值框定位正确但文本带噪声前缀，从值内用正则提取即可；</li>
	 *   <li>标签带冒号剥出空噪声："车辆类型：" 合并框剥出 "："，不应作为值返回；</li>
	 *   <li>值尾噪声："重型集装箱半挂车."；</li>
	 *   <li>值内单字误识："重型集装箱半技车"（距标准词表 Levenshtein=1），交由词表纠错。</li>
	 * </ul>
	 */
	private static LabeledMatch parseVehicleType(List<PPOcrV6Result> results) {
		LabeledMatch match = LabelMatcher.matchValueFromPrefixWithBox(results, LABEL_VEHICLE_TYPE);
		if (!isVehicleTypeValue(match)) {
			// 1) 值框已定位但文本带噪声（"VehiclcTyre重型半挂牵引车"）→ 从值内提取
			LabeledMatch resolved = extractVehicleTypeWithin(match);
			if (!resolved.hasValue()) {
				// 2) 正则兜底（标签缺失 / 值为 "：" 等空噪声）
				resolved = LabelMatcher.labelOrFallbackWithBox(
					match, results, VEHICLE_TYPE_VALUE_PATTERN, "车辆类型", false);
				if (!resolved.hasValue()) {
					// 3) 子串搜索兜底
					resolved = matchSubstringWithBox(results, VEHICLE_TYPE_VALUE_PATTERN);
					if (resolved.hasValue()) {
						log.debug("行驶证解析：车辆类型 子串搜索兜底命中 \"{}\"", resolved.value());
					}
				}
			}
			if (resolved.hasValue()) {
				match = resolved;
			} else if (containsChinese(match.value())) {
				// 4) 兜底保留近正确的原值（如 "重型集装箱半技车"），交给词表纠错
				log.warn("行驶证解析：车辆类型位置匹配 \"{}\" 格式异常且兜底未命中，保留原值待词表纠错", match.value());
			} else {
				// 纯噪声（如 "："）或本就无值 → 置 null
				match = LabeledMatch.textOnly(null);
			}
		}
		return correctVehicleTypeByVocabulary(match);
	}

	/**
	 * 判断车辆类型匹配结果是否已是合格的车辆类型值（完整命中值正则）。
	 */
	private static boolean isVehicleTypeValue(LabeledMatch match) {
		return match.hasValue() && VEHICLE_TYPE_VALUE_PATTERN.matcher(match.value()).matches();
	}

	/**
	 * 从已定位的车辆类型值框文本内提取车辆类型（保留原值框坐标）。
	 *
	 * <p>典型场景：OCR 把英文标签 "VehicleType" 误识成 "VehiclcTyre" 并与中文值合并成
	 * 单框，标签定位拿到的值即整个合并框文本，用值正则 find() 从中切出中文值。
	 *
	 * @param match 标签定位得到的匹配结果（值含噪声前缀/后缀）
	 * @return 提取出的车辆类型 + 原值框；无法提取时返回无值 LabeledMatch
	 */
	private static LabeledMatch extractVehicleTypeWithin(LabeledMatch match) {
		String value = match.value();
		if (value == null) {
			return LabeledMatch.textOnly(null);
		}
		Matcher m = VEHICLE_TYPE_VALUE_PATTERN.matcher(value);
		if (m.find()) {
			log.debug("行驶证解析：车辆类型从噪声值框 \"{}\" 提取 \"{}\"", value, m.group());
			return LabeledMatch.of(m.group(), match.matches());
		}
		return LabeledMatch.textOnly(null);
	}

	/**
	 * 车辆类型标准词表纠错：值不在词表且与某词条 Levenshtein 距离 ≤ 2（长度相差 ≤ 1）时纠为该词条。
	 *
	 * <p>车辆类型取值来自 GA 802 封闭术语集，OCR 单字误识（如 "重型集装箱半技车"、
	 * "重盟半挂章引车"）与标准词表距离极小，可高置信纠回；长度过滤同时排除标题
	 * （"中华人民共和国机动车行驶证"）等长噪声的误纠。
	 *
	 * @param match 车辆类型匹配结果
	 * @return 纠错后的匹配结果（保留原值框）；无纠错时原样返回
	 */
	private static LabeledMatch correctVehicleTypeByVocabulary(LabeledMatch match) {
		String value = match.value();
		if (value == null || VEHICLE_TYPE_VOCABULARY.contains(value)) {
			return match;
		}
		if (value.length() < VEHICLE_TYPE_FUZZY_MIN_LENGTH) {
			return match;
		}
		String best = null;
		int bestDistance = VEHICLE_TYPE_FUZZY_MAX_DISTANCE + 1;
		int bestLenDiff = Integer.MAX_VALUE;
		for (String candidate : VEHICLE_TYPE_VOCABULARY) {
			int lenDiff = Math.abs(candidate.length() - value.length());
			if (lenDiff > 1) {
				continue;
			}
			int distance = levenshtein(value, candidate);
			// 距离为主，长度接近度打破平局（更具体的词条优先）
			if (distance < bestDistance || (distance == bestDistance && lenDiff < bestLenDiff)) {
				best = candidate;
				bestDistance = distance;
				bestLenDiff = lenDiff;
			}
		}
		if (best != null && bestDistance <= VEHICLE_TYPE_FUZZY_MAX_DISTANCE) {
			log.info("行驶证解析：车辆类型词表纠错 \"{}\" -> \"{}\" (distance={})", value, best, bestDistance);
			return LabeledMatch.of(best, match.matches());
		}
		return match;
	}

	/**
	 * 两字符串的 Levenshtein 编辑距离（两行滚动数组实现，车辆类型长度下性能无忧）。
	 *
	 * @param a 字符串 a
	 * @param b 字符串 b
	 * @return 编辑距离
	 */
	private static int levenshtein(String a, String b) {
		int[] prev = new int[b.length() + 1];
		int[] curr = new int[b.length() + 1];
		for (int j = 0; j <= b.length(); j++) {
			prev[j] = j;
		}
		for (int i = 1; i <= a.length(); i++) {
			curr[0] = i;
			for (int j = 1; j <= b.length(); j++) {
				int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
				curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
			}
			int[] tmp = prev;
			prev = curr;
			curr = tmp;
		}
		return prev[b.length()];
	}

	/**
	 * 判断文本是否含中文字符。
	 *
	 * @param text 待判断文本；null 返回 false
	 * @return true 表示含中文
	 */
	private static boolean containsChinese(String text) {
		return text != null && CHINESE_TEXT_PATTERN.matcher(text).matches();
	}

	/**
	 * 解析 VIN：标签定位 + 正则兜底 + 子串搜索兜底（含点号噪声清理）。
	 * 最终做"归一化 + 严格校验"：I→1、O→0、Q→0 后用 ISO 3779 字符集 {@link #VIN_PATTERN_STRICT} 校验。
	 *
	 * <p>不能用 {@link LabelMatcher#labelOrFallbackWithBox}: 该方法命中宽容正则后会直接 return labelMatch,
	 * 绕过后续 normalizeVinMatch,导致 I/O/Q 误识归一化被跳过。改为手动串联:标签定位 + 正则兜底 + 点号清理,
	 * 每步结果都交给 normalizeVinMatch 走归一化+严格校验。
	 */
	private static LabeledMatch parseVin(List<PPOcrV6Result> results) {
		LabeledMatch match = LabelMatcher.matchValueWithBox(results, LABEL_VIN);
		if (!match.hasValue()) {
			match = LabelMatcher.matchPatternWithBox(results, VIN_PATTERN, false);
			if (match.hasValue()) {
				log.debug("行驶证解析：VIN 正则兜底命中 \"{}\"", match.value());
			}
		}
		if (!match.hasValue()) {
			match = matchSubstringWithBox(results, VIN_PATTERN);
			if (match.hasValue()) {
				log.debug("行驶证解析：VIN 子串搜索兜底命中 \"{}\"", match.value());
			}
		}
		if (!match.hasValue()) {
			// 点号噪声兜底：OCR 把 VIN 识别成 "LA9JM4C08T0HL10.1.3"（中间插了点号），
			// 清理非字母数字字符后按 17 位重新匹配。
			match = LabelMatcher.matchSubstringWithBox(results, text -> {
				Matcher m = VIN_PATTERN.matcher(text.replaceAll("[^A-Z0-9]", ""));
				return m.find() ? m.group() : null;
			});
			if (match.hasValue()) {
				log.debug("行驶证解析：VIN 点号噪声清理兜底命中 \"{}\"", match.value());
			}
		}
		return normalizeVinMatch(match);
	}

	/**
	 * 解析发证日期：多标签定位 + 全图 y 最大日期兜底。
	 *
	 * <p>主页可能用"发证日期"、"注册日期"或英文 "IssueDate" / "RegisterDate" 任一标签，
	 * 按顺序尝试命中。一旦标签定位失败（缺标签或值框被噪声占据），走全图 y 最大日期兜底：
	 * 发证日期通常在版式最下，是同版式多日期中 y 最大的那个。
	 */
	private static LabeledMatch parseIssueDate(List<PPOcrV6Result> results) {
		LabeledMatch match = labelOrFallbackForIssueDate(results, LABEL_ISSUE_DATE);
		if (!match.hasValue()) {
			match = labelOrFallbackForIssueDate(results, "注册日期");
		}
		if (!match.hasValue()) {
			match = labelOrFallbackForIssueDate(results, "IssueDate");
		}
		if (!match.hasValue()) {
			match = labelOrFallbackForIssueDate(results, "RegisterDate");
		}
		if (!match.hasValue()) {
			match = matchBottomDateWithBox(results);
			if (match.hasValue()) {
				log.debug("行驶证解析：发证日期 全图 y 最大日期兜底命中 \"{}\"", match.value());
			}
		}
		return match;
	}

	/**
	 * 单标签定位 + DATE_PATTERN 兜底；纯转发，隔离多标签探测逻辑。
	 */
	private static LabeledMatch labelOrFallbackForIssueDate(List<PPOcrV6Result> results, String label) {
		return LabelMatcher.labelOrFallbackWithBox(
			LabelMatcher.matchValueWithBox(results, label),
			results, DATE_PATTERN, "发证日期", true);
	}

	/**
	 * 全图 y 最大日期兜底：发证日期通常在版式最下；多日期并存时取 y 中心最大的那个。
	 */
	private static LabeledMatch matchBottomDateWithBox(List<PPOcrV6Result> results) {
		PPOcrV6Result best = null;
		int bestY = Integer.MIN_VALUE;
		for (PPOcrV6Result r : results) {
			Matcher m = DATE_PATTERN.matcher(r.text());
			if (!m.find()) continue;
			int y = (minY(r) + maxY(r)) / 2;
			if (y > bestY) {
				bestY = y;
				best = r;
			}
		}
		if (best == null) {
			return LabeledMatch.textOnly(null);
		}
		Matcher m = DATE_PATTERN.matcher(best.text());
		m.find();
		return LabeledMatch.of(m.group(), best);
	}

	// ==================================================================
	// 兜底辅助
	// ==================================================================

	/**
	 * 车牌 I/O 字符归一化。中国大陆车牌与 VIN（ISO 3779）的合法字符集均不含 I 与 O
	 * （避免与 1/0 混淆），OCR 结果中出现 I/O 必然是 1/0 的误识（tiny 档模型高发，
	 * 如 "鲁GOT77挂" → "鲁G0T77挂"），按确定性映射 I→1、O→0 归一化。
	 *
	 * @param match     车牌匹配结果
	 * @param fieldName 字段名（日志用）
	 * @return 归一化后的匹配结果；值不含 I/O 时原样返回
	 */
	private static LabeledMatch normalizeAmbiguousAlnumMatch(LabeledMatch match, String fieldName) {
		String value = match.value();
		if (value == null) {
			return match;
		}
		String normalized = value.replace('I', '1').replace('O', '0');
		if (!normalized.equals(value)) {
			log.debug("行驶证解析：{} I/O 误识归一化 \"{}\" -> \"{}\"", fieldName, value, normalized);
			return LabeledMatch.of(normalized, match.matches());
		}
		return match;
	}

	/**
	 * VIN 归一化 + 严格校验。VIN 标准字符集（ISO 3779）为 [A-HJ-NPR-Z0-9]，
	 * 不含 I/O/Q。处理顺序：
	 * <ol>
	 *   <li>对候选串做 I→1、O→0、Q→0 归一化（确定性映射）；</li>
	 *   <li>用 {@link #VIN_PATTERN_STRICT} 校验归一化后结果是否严格符合 VIN 字符集；</li>
	 *   <li>不通过则置 null，丢弃该候选。</li>
	 * </ol>
	 * Q→0：tiny 档模型对 0/O/Q 区分度差，Q 出现几乎必为 0 的误识（O→Q 也可能但概率低），
	 * 与其透传错值不如按"0"做一次归一化救场；即使错救，仍会被严格正则抓到后续的非法字符。
	 *
	 * @param match VIN 匹配结果
	 * @return 归一化并校验通过的结果；校验失败时返回仅含 null value 的 LabeledMatch
	 */
	private static LabeledMatch normalizeVinMatch(LabeledMatch match) {
		String value = match.value();
		if (value == null) {
			return match;
		}
		String normalized = value.replace('I', '1').replace('O', '0').replace('Q', '0');
		if (!VIN_PATTERN_STRICT.matcher(normalized).matches()) {
			log.warn("行驶证解析：VIN 严格校验失败 \"{}\" -> \"{}\"，丢弃", value, normalized);
			return LabeledMatch.textOnly(null);
		}
		if (!normalized.equals(value)) {
			log.debug("行驶证解析：VIN 误识归一化 \"{}\" -> \"{}\"", value, normalized);
			return LabeledMatch.of(normalized, match.matches());
		}
		return match;
	}

	/**
	 * 在所有 OCR 文本上用正则 find() 提取首个匹配（应对标签缺失或值嵌在长合并框中的场景）。
	 *
	 * @param results OCR 识别结果列表
	 * @param pattern 值正则
	 * @return 匹配值 + 值框；无匹配时返回仅含 null value 的 LabeledMatch
	 */
	private static LabeledMatch matchSubstringWithBox(List<PPOcrV6Result> results, Pattern pattern) {
		return LabelMatcher.matchSubstringWithBox(results, text -> {
			Matcher m = pattern.matcher(text);
			return m.find() ? m.group() : null;
		});
	}

	/**
	 * OCR 把"所有人"识别成残缺前缀（"人"/"有人"/"所人"）并与公司/人名合并成单框
	 * （如 "人莘县顺发物流有限公司"、"有人新乐市云翔运输有限公司"、"所人上海润升物流有限公司"）
	 * 时，从合并框剥掉残缺前缀。
	 */
	private static LabeledMatch matchOwnerByPartialPrefix(List<PPOcrV6Result> results) {
		for (PPOcrV6Result box : results) {
			String stripped = stripOwnerRenPrefix(box.text());
			if (stripped != null) {
				log.debug("行驶证解析：所有人 从残缺合并框 \"{}\" 剥出 \"{}\"", box.text(), stripped);
				return LabeledMatch.of(stripped, box);
			}
		}
		return LabeledMatch.textOnly(null);
	}

	/**
	 * 剥掉所有人值开头的残缺"所有人"前缀（OCR 把"所有人"误识成"人"/"有人"/"所人"/"所X人"并与值合并，
	 * 如 "人莘县顺发物流有限公司"、"所精人山东XX物流有限公司"）。
	 * 保护"中国人民财产保险…"等合法以"人"开头的公司名（剥后以"国"开头则拒绝）。
	 *
	 * @param text 所有人文本
	 * @return 剥前缀后的值；不满足剥除条件时返回 null
	 */
	private static String stripOwnerRenPrefix(String text) {
		if (text == null) {
			return null;
		}
		// 1) 3 字残缺变体"所X人"（X 为任意汉字），兼容 2 字"所人"
		Matcher mangled = OWNER_MANGLED_PREFIX_PATTERN.matcher(text);
		if (mangled.find() && text.length() > mangled.end()) {
			return strippedOwnerValueOrNull(text, mangled.end());
		}
		// 2) 既有前缀"有人"/"所人"/"人"
		for (String prefix : OWNER_PARTIAL_PREFIXES) {
			if (text.startsWith(prefix) && text.length() > prefix.length()) {
				return strippedOwnerValueOrNull(text, prefix.length());
			}
		}
		return null;
	}

	/**
	 * 按指定前缀长度剥除后的所有人值；剥后须含中文且非"中国人民…"等"国"开头公司名，否则返回 null。
	 */
	private static String strippedOwnerValueOrNull(String text, int prefixLength) {
		String stripped = text.substring(prefixLength);
		if (!stripped.trim().isEmpty()
			&& CHINESE_TEXT_PATTERN.matcher(stripped).matches()
			&& !stripped.startsWith(RENMIN_GUARD)) {
			return stripped;
		}
		return null;
	}

	/**
	 * 规范化所有人匹配结果：值带残缺"人"前缀（如 "人莘县顺发物流有限公司"）时统一剥除，
	 * 保留原值框。
	 *
	 * @param match 待处理的所有人匹配结果
	 * @return 剥前缀后的匹配结果；不满足剥除条件时原样返回
	 */
	private static LabeledMatch normalizeOwnerMatch(LabeledMatch match) {
		String value = match.value();
		String stripped = stripOwnerRenPrefix(value);
		if (stripped == null || stripped.equals(value)) {
			return match;
		}
		log.debug("行驶证解析：所有人 剥除残缺前缀\"人\" \"{}\" -> \"{}\"", value, stripped);
		return LabeledMatch.of(stripped, match.matches());
	}

	/**
	 * 判断文本是否为已知标签 / 标题 / 车辆类型值等噪声（布局兜底时排除）。
	 */
	private static boolean isOwnerNoise(String text) {
		return OWNER_NOISE_LABELS.contains(text)
			|| OWNER_NOISE_LABELS.stream().anyMatch(text::startsWith)
			|| VEHICLE_TYPE_VALUE_PATTERN.matcher(text).find();
	}

	/**
	 * 判断文本是否为地址（布局兜底时排除）。
	 */
	private static boolean isLikelyAddress(String text) {
		// 含公司特征的不视为地址（如 "聊城市侨润物流有限公司"）
		return text.startsWith(ADDRESS_PREFIX)
			|| (ADDRESS_KEYWORD_PATTERN.matcher(text).matches() && !containsCompanyKeyword(text));
	}

	/**
	 * 判断文本是否含公司特征关键字。
	 */
	private static boolean containsCompanyKeyword(String text) {
		return COMPANY_KEYWORDS.stream().anyMatch(text::contains);
	}

	/**
	 * 定位车辆类型下沿（y 最大值），作为所有人布局兜底的上边界。
	 *
	 * @return 车辆类型下沿；标签缺失且无"车"字文本时返回 {@link Integer#MIN_VALUE}
	 */
	private static int findVehicleTypeBottom(List<PPOcrV6Result> results) {
		int bottom = Integer.MIN_VALUE;
		for (String label : VEHICLE_TYPE_LABELS) {
			PPOcrV6Result box = LabelMatcher.findLabelBox(results, label);
			if (box != null) {
				bottom = Math.max(bottom, LabelMatcher.maxY(box));
			}
		}
		if (bottom != Integer.MIN_VALUE) return bottom;
		// 标签缺失时，用含"车"的文本兜底
		for (PPOcrV6Result box : results) {
			String text = box.text();
			if (!ENGLISH_TEXT_PATTERN.matcher(text).matches()
				&& (text.contains("轿车") || text.contains("客车") || text.contains("货车") || text.contains("车"))) {
				bottom = Math.max(bottom, LabelMatcher.maxY(box));
			}
		}
		return bottom;
	}

	/**
	 * 定位住址上沿（y 最小值），作为所有人布局兜底的下边界。
	 *
	 * @return 住址上沿；标签缺失且无地址关键字文本时返回 {@link Integer#MAX_VALUE}
	 */
	private static int findAddressTop(List<PPOcrV6Result> results) {
		int top = Integer.MAX_VALUE;
		for (String label : ADDRESS_LABELS) {
			PPOcrV6Result box = LabelMatcher.findLabelBox(results, label);
			if (box != null) {
				top = Math.min(top, LabelMatcher.minY(box));
			}
		}
		if (top != Integer.MAX_VALUE) return top;
		// 标签缺失时，用含地址关键字的文本兜底
		for (PPOcrV6Result box : results) {
			if (ADDRESS_KEYWORD_PATTERN.matcher(box.text()).matches()) {
				top = Math.min(top, LabelMatcher.minY(box));
			}
		}
		return top;
	}

	/**
	 * 所有人版面布局兜底：在"车辆类型下沿"与"住址上沿"之间的 y 带内，
	 * 取最宽的非噪声文本作为所有人（无法精准定位 box，所以 fieldBoxes 不填）。
	 */
	private static LabeledMatch matchOwnerByLayoutFallback(List<PPOcrV6Result> results) {
		int vehicleTypeBottom = findVehicleTypeBottom(results);
		if (vehicleTypeBottom == Integer.MIN_VALUE) {
			return LabeledMatch.textOnly(null);
		}
		int addressTop = findAddressTop(results);
		if (addressTop == Integer.MAX_VALUE || addressTop <= vehicleTypeBottom) {
			return LabeledMatch.textOnly(null);
		}

		String best = null;
		int bestWidth = -1;
		for (PPOcrV6Result box : results) {
			if (!isOwnerCandidate(box, vehicleTypeBottom, addressTop)) continue;
			int width = LabelMatcher.maxX(box) - LabelMatcher.minX(box);
			if (width > bestWidth) {
				bestWidth = width;
				best = box.text();
			}
		}
		return LabeledMatch.textOnly(best);
	}

	/**
	 * 判断 OCR 框是否为所有人布局兜底的候选：
	 * 非空、非纯英文噪声、非已知标签/车辆类型值、非地址，且位于"车辆类型下沿"与"住址上沿"之间。
	 *
	 * @param box              待判断的 OCR 框
	 * @param vehicleTypeBottom 车辆类型下沿（y 下限）
	 * @param addressTop        住址上沿（y 上限）
	 * @return true 表示可作为所有人候选
	 */
	private static boolean isOwnerCandidate(PPOcrV6Result box, int vehicleTypeBottom, int addressTop) {
		String text = box.text();
		return !text.isEmpty()
			&& !ENGLISH_TEXT_DOT_PATTERN.matcher(text).matches()
			&& !isOwnerNoise(text)
			&& !isLikelyAddress(text)
			&& LabelMatcher.maxY(box) >= vehicleTypeBottom
			&& LabelMatcher.minY(box) <= addressTop;
	}
}
