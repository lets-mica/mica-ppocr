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

package net.dreamlu.mica.ai.ppocr.structured.parser.train;

import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Engine;
import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Result;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.BaseStructuredParser;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.LabelMatcher;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.LabelMatcher.LabeledMatch;
import net.dreamlu.mica.ai.ppocr.utils.CollUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 火车票 OCR 结构化解析器。
 *
 * <p>兼容中国铁路纸质票（蓝/红票）与电子客票版式；字段对齐百度 OCR 火车票接口。
 *
 * <p>策略概览：
 * <ul>
 *   <li><b>始发 / 到达站</b>：标签定位 + 全图顶部兜底（放宽 minX 阈值到 500 像素，兼容 OCR 框偏左）；</li>
 *   <li><b>车次</b>：标签定位 + 全图正则兜底（[GDCZTKYL]\d{1,4}，跳过"原票"上下文避免误识别 L 开头补票号）；</li>
 *   <li><b>出发日期 / 时间</b>：从合并框"YYYY年MM月DD日HH:MM开"统一切分；</li>
 *   <li><b>金额</b>：标签定位 + 关键字兜底 + 纯数字/¥ 金额正则；</li>
 *   <li><b>票号</b>：放宽到 7-10 位（实际纸质票号多为 7-8 位），与电子客票 20/25 位不冲突；</li>
 *   <li><b>乘客姓名</b>：标签定位 + 合并框（身份证+姓名）剥值；</li>
 *   <li><b>身份证</b>：标签定位 + 部分脱敏保留（业务可自行决定是否使用）；</li>
 *   <li><b>售站</b>：标签定位 + 票面底部"XX售"模式兜底；</li>
 *   <li><b>席别</b>：标签定位 + 关键字兜底；</li>
 *   <li><b>改签标识</b>：标签定位 + 关键字兜底。</li>
 * </ul>
 *
 * <p>输出结果会填充 {@code TrainTicketResult#getRawResults()} 与
 * {@code TrainTicketResult#getFieldBoxes()}，便于页面高亮。
 */
@Slf4j
public class TrainTicketParser extends BaseStructuredParser<TrainTicketResult> {

	// ========================================================================
	// 几何兜底阈值（票面顶部区域：始发/到达站；底部区域：售站）
	// ========================================================================

	/**
	 * 票面顶部 y 阈值：始发站/到达站按位置兜底时的 y 上限。
	 */
	private static final int STATION_MAX_Y = 400;
	/**
	 * 始发站左侧 x 阈值：放宽原 300 阈值以适配实际票面（OCR 框偏左）。
	 */
	private static final int STATION_LEFT_MAX_X = 500;
	/**
	 * 售站底部阈值：相对全图 y 中位数的偏移比例（最大 y 的 2/3）。
	 */
	private static final int SELL_STATION_BOTTOM_RATIO_NUM = 2;
	private static final int SELL_STATION_BOTTOM_RATIO_DEN = 3;

	// ========================================================================
	// 正则常量
	// ========================================================================

	/**
	 * 车次号：高铁/动车 G/D、城际 C、直达 Z、特快 T、快速 K、普快/临时/旅游 Y/L。
	 * 兼容 OCR 误识别：把 0 误识别为 O/I/L（"GO000" → "G0000"）。
	 * 长度限制 1-4 位数字。
	 */
	private static final Pattern TRAIN_NUMBER_PATTERN = Pattern.compile("[GDCZTKYL][\\dOIl]{1,4}");

	/**
	 * 车票号：7-10 位纯数字。覆盖 E014470 / R093443 / U028534 等 7 位票号。
	 * 部分票有分隔空格/短横线，统一归一化。
	 */
	private static final Pattern TICKET_NO_PATTERN = Pattern.compile("\\d{7,10}");

	/**
	 * 字母前缀票号：1 字母 + 6-7 位数字（如 E014470 / R093443 / U028534）。
	 */
	private static final Pattern ALPHA_TICKET_PATTERN = Pattern.compile("[A-Z]\\d{6,7}");

	/**
	 * 发票号码：20 位纯数字。
	 */
	private static final Pattern INVOICE_NO_PATTERN = Pattern.compile("\\d{20}");

	/**
	 * 电子客票号：25 位纯数字。
	 */
	private static final Pattern ETICKET_NO_PATTERN = Pattern.compile("\\d{25}");

	/**
	 * 日期：yyyy年MM月dd日 / yyyy-MM-dd / yyyy/MM/dd / yyyy.MM.dd。
	 * 宽松分隔符（含 [:：] 容忍 OCR 噪声）。
	 */
	private static final Pattern DATE_PATTERN = Pattern.compile(
		"\\d{2,4}[-./年:：]\\d{1,2}[-./月:：]\\d{1,2}日?");

	/**
	 * HH:mm 完整片段（不分组）。
	 */
	private static final String HHMM = "(?:[01]?\\d|2[0-3]):[0-5]\\d";

	/**
	 * 时间：HH:mm（24 小时制）。
	 */
	private static final Pattern TIME_PATTERN = Pattern.compile(HHMM);

	/**
	 * 金额：支持 ￥ / ¥ 前缀，"元" 后缀，保留两位小数。
	 * 容许 OCR 噪声 "-" 当小数点。
	 */
	private static final Pattern AMOUNT_PATTERN = Pattern.compile(
		"[¥￥]\\s*\\d+(?:[.-]\\d{1,2})?\\s*元?");

	/**
	 * 座位号：02车12A号 / 02车12号 / 02车12A / 5车12C。
	 * 兼容 OCR 把 "号" 漏识别或末尾多余空格。
	 */
	private static final Pattern SEAT_NUMBER_PATTERN = Pattern.compile(
		"\\d{1,3}车\\d{1,4}[A-Z]?号?");

	/**
	 * 身份证号：18 位（末位 X 支持）。
	 * 兼容星号脱敏：先剥星号再校验位数。
	 */
	private static final Pattern ID_NUMBER_PATTERN = Pattern.compile(
		"\\d{17}[\\dXx]");

	/**
	 * 4~6 字中文姓名（OCR 残缺兜底：2~6 字）。
	 */
	private static final Pattern NAME_PATTERN = Pattern.compile("[\\u4e00-\\u9fa5]{2,6}");

	/**
	 * 站名模式：中文地名 + 方位 / 行政区划后缀，或常见无后缀城市名（京/沪/津/渝/港/澳等直辖市）。
	 * 用于在姓名兜底中排除"平顶山西""上海""北京"等不带"站"字的站名。
	 */
	private static final Pattern STATION_SUFFIX_PATTERN = Pattern.compile(
		"[\\u4e00-\\u9fa5]+(?:西|东|南|北|市|县|州|区|省|旗|镇|乡|村|海|口|津|渝|港|澳|京|沪)$");

	/**
	 * 售站兜底正则：票面底部"XX售"或"XX局XX售"（如"天津售"、"银川售"、"济局青客补"）。
	 */
	private static final Pattern SELL_STATION_PATTERN = Pattern.compile(
		"([\\u4e00-\\u9fa5]{2,8})[售$]");

	/**
	 * 机构名特征字：包含其一即视为非人名。
	 * 覆盖常见票面/官方机构关键词（国家税务总局、铁路局、汽车运管所、客运段等）。
	 */
	private static final Set<Character> INSTITUTION_KEY_CHARS = CollUtil.setOf(
		'局', '司', '所', '院', '处', '部', '厅', '署',
		'税', '运', '铁', '邮', '公', '证', '发', '联',
		'会', '学', '校', '厂', '店', '馆', '场',
		'总', '队', '股', '行', '团', '组', '社');

	/**
	 * 席别关键字（按长到短排序，避免"商务座"误匹配"座"）。
	 */
	private static final List<String> SEAT_CLASS_KEYWORDS = CollUtil.listOf(
		"高级软卧", "商务座", "特等座", "二等座", "一等座",
		"软卧", "硬卧", "二等卧", "一等卧",
		"软座", "硬座"
	);

	/**
	 * 改签标识关键字。
	 */
	private static final List<String> CHANGED_FLAG_KEYWORDS = CollUtil.listOf(
		"始发改签", "退票", "改签"
	);

	/**
	 * 出发日期+时间合并框模式：2014年09月09日15:52开。
	 * 优先匹配完整的"日期+时间+开"格式。
	 */
	private static final Pattern DATE_TIME_OPEN_PATTERN = Pattern.compile(
		"(\\d{4}[-./年:：]\\d{1,2}[-./月:：]\\d{1,2}日?)" + HHMM + "开?");

	/**
	 * 仅日期+时间（无"开"字）的合并框模式。
	 */
	private static final Pattern DATE_TIME_PATTERN = Pattern.compile(
		"(\\d{4}[-./年:：]\\d{1,2}[-./月:：]\\d{1,2}日?)" + "\\s*" + HHMM);

	/**
	 * 噪声上下文词：含其一即跳过（避免误识别为车次）。
	 * 典型：原票、原票号、补票（火车票"原票：L0956"格式不应作为车次）。
	 */
	private static final Set<String> TRAIN_NUMBER_NOISE_KEYWORDS = CollUtil.setOf(
		"原票", "补", "越站", "事由", "加收");

	/**
	 * 合并框「身份证+姓名」中身份证部分的匹配模式（允许中间含脱敏星号）。
	 */
	private static final Pattern MERGED_ID_PART_PATTERN = Pattern.compile(
		"\\d{6,17}[\\dXx*]{1,4}\\d*[\\dXx]?");

	/**
	 * 合并框「身份证+姓名」尾部姓名的匹配模式（2-6 位中文）。
	 */
	private static final Pattern MERGED_NAME_PATTERN = Pattern.compile("([\\u4e00-\\u9fa5]{2,6})");

	/**
	 * 脱敏身份证号（含 *）的匹配模式。
	 */
	private static final Pattern MASKED_ID_PATTERN = Pattern.compile("(\\d+[*\\dXx]{0,8}[\\dXx])");

	// ========================================================================
	// 入口
	// ========================================================================
	/**
	 * 乘客姓名兜底中需要排除的车票常见字。
	 */
	private static final Set<Character> TRAIN_NOISE_TICKET_CHARS = CollUtil.setOf(
		'站', '座', '车', '票', '卧', '元', '￥', '¥');
	/**
	 * 票号兜底中需要排除的字符（含金额符、身份证 *、小数点）。
	 */
	private static final Set<String> TICKET_NOISE_CHARS = CollUtil.setOf("￥", "¥", "元", "*", ".");
	/**
	 * 站名兜底中需要排除的机构关键字（单字粒度）。
	 */
	private static final Set<Character> STATION_NOISE_CHARS = CollUtil.setOf(
		'局', '司', '所', '院', '处', '部', '厅', '署', '税',
		'运', '铁', '国');

	// ========================================================================
	// 行程：始发/到达/车次/日期/时间/座位/席别
	// ========================================================================

	/**
	 * 构造火车票解析器，绑定推理引擎。
	 *
	 * @param engine PP-OCRv6 推理引擎；可为 null（仅在仅调用 {@link #parseResults(List)} 时）
	 */
	public TrainTicketParser(PPOcrV6Engine engine) {
		super(engine);
	}

	/**
	 * 设置字段值并回填字段框到 {@code fieldBoxes}。
	 *
	 * @param r     结果对象
	 * @param name  字段名（fieldBoxes key）
	 * @param match 字段匹配结果
	 */
	private static void applyField(TrainTicketResult r, String name, LabeledMatch match) {
		if (match == null) return;
		if (match.value() != null) {
			switch (name) {
				case "departure":
					r.setDeparture(match.value());
					break;
				case "arrival":
					r.setArrival(match.value());
					break;
				case "trainNumber":
					r.setTrainNumber(match.value());
					break;
				case "seatNumber":
					r.setSeatNumber(match.value());
					break;
				case "seatClass":
					r.setSeatClass(match.value());
					break;
				case "passengerName":
					r.setPassengerName(match.value());
					break;
				case "idNumber":
					r.setIdNumber(match.value());
					break;
				case "amount":
					r.setAmount(match.value());
					break;
				case "amountExcludingTax":
					r.setAmountExcludingTax(match.value());
					break;
				case "ticketNo":
					r.setTicketNo(match.value());
					break;
				case "invoiceNo":
					r.setInvoiceNo(match.value());
					break;
				case "eTicketNo":
					r.setETicketNo(match.value());
					break;
				case "invoiceDate":
					r.setInvoiceDate(match.value());
					break;
				case "sellStation":
					r.setSellStation(match.value());
					break;
				case "serialNumber":
					r.setSerialNumber(match.value());
					break;
				case "changedFlag":
					r.setChangedFlag(match.value());
					break;
				default: {
					/* no-op */
					break;
				}
			}
		}
		LabelMatcher.applyFieldBox(r, name, match);
	}

	/**
	 * 始发站解析：
	 * <ol>
	 *   <li>标签 "始发站" / "出发站"（兼容合并框）；</li>
	 *   <li>兜底：票面顶部 y≤{@value #STATION_MAX_Y}、minX≤{@value #STATION_LEFT_MAX_X} 的"X站"框。</li>
	 * </ol>
	 */
	private static LabeledMatch parseDeparture(List<PPOcrV6Result> results) {
		LabeledMatch m = matchStationByLabels(results, "始发站", "出发站");
		if (m.hasValue()) return m;
		return pickStationByPosition(results, STATION_MAX_Y, STATION_LEFT_MAX_X, "left", "始发");
	}

	/**
	 * 到达站解析：
	 * <ol>
	 *   <li>标签 "到达站" / "目的站"；</li>
	 *   <li>兜底：票面顶部 y≤{@value #STATION_MAX_Y}、minX > imgMaxX * 0.5 的"X站"框。</li>
	 * </ol>
	 */
	private static LabeledMatch parseArrival(List<PPOcrV6Result> results) {
		LabeledMatch m = matchStationByLabels(results, "到达站", "目的站");
		if (m.hasValue()) return m;
		return pickStationByPosition(results, STATION_MAX_Y, Integer.MAX_VALUE, "right", "到达");
	}

	/**
	 * 按多个标签依次尝试，从合并框剥出站名。
	 */
	private static LabeledMatch matchStationByLabels(List<PPOcrV6Result> results, String... labels) {
		for (String label : labels) {
			LabeledMatch m = LabelMatcher.matchValueFromPrefixWithBox(results, label);
			if (m.hasValue()) {
				String cleaned = cleanStation(m.value());
				return LabeledMatch.of(cleaned, m.matches());
			}
		}
		return LabeledMatch.textOnly(null);
	}

	/**
	 * 车次号解析：
	 * <ol>
	 *   <li>标签 "车次" / "车次号"；</li>
	 *   <li>兜底：扫所有框匹配车次正则，跳过"原票"等噪声上下文。</li>
	 * </ol>
	 */
	private static LabeledMatch parseTrainNumber(List<PPOcrV6Result> results) {
		// 1) 标签
		for (String label : CollUtil.listOf("车次", "车次号")) {
			LabeledMatch m = LabelMatcher.matchValueFromPrefixWithBox(results, label);
			if (m.hasValue()) {
				Matcher regex = TRAIN_NUMBER_PATTERN.matcher(m.value());
				if (regex.find()) {
					return LabeledMatch.of(regex.group(), m.matches());
				}
			}
		}
		// 2) 兜底：扫所有框匹配车次正则，优先短匹配
		PPOcrV6Result best = null;
		String bestNormalized = null;
		int bestLen = Integer.MAX_VALUE;
		for (PPOcrV6Result r : results) {
			String text = r.text();
			if (text == null || text.isEmpty()) continue;
			// 排除含"原票"/"补"等噪声上下文的框
			if (containsAny(text, TRAIN_NUMBER_NOISE_KEYWORDS)) continue;
			Matcher regex = TRAIN_NUMBER_PATTERN.matcher(text);
			while (regex.find()) {
				// OCR 常见误识别：O→0、I/l→1
				String normalized = regex.group()
					.replace('O', '0')
					.replace('I', '1')
					.replace('l', '1');
				if (bestNormalized == null || normalized.length() < bestLen) {
					bestLen = normalized.length();
					bestNormalized = normalized;
					best = r;
				}
			}
		}
		if (best == null) return LabeledMatch.textOnly(null);
		log.debug("火车票解析：车次按正则兜底 \"{}\"", bestNormalized);
		return LabeledMatch.of(bestNormalized, best);
	}

	/**
	 * 同时处理出发日期与时间。
	 *
	 * <p>P0 优化：优先从合并框"YYYY年MM月DD日HH:MM开"统一切分；
	 * 失败时回退到独立框匹配。
	 */
	private static void applyDepartureDateTime(TrainTicketResult r, List<PPOcrV6Result> results) {
		// 1) 优先从"日期+时间"合并框切分
		DateTimeSplit split = extractDateTimeFromMergedBox(results);
		// 2) 日期：合并框 > 标签 > 全图正则
		String date = firstNonNull(split.date(),
			dateByLabel(results, "出发日期", "乘车日期", "日期"));
		if (date != null) r.setDepartureDate(date);
		// 3) 时间：合并框 > 标签
		String time = firstNonNull(split.time(),
			timeByLabel(results, "出发时间", "乘车时间", "时间"));
		if (time != null) r.setDepartureTime(time);
	}

	/**
	 * 按多个日期标签依次尝试。
	 */
	private static String dateByLabel(List<PPOcrV6Result> results, String... labels) {
		for (String label : labels) {
			LabeledMatch m = LabelMatcher.matchValueWithBox(results, label);
			if (m.hasValue()) {
				Matcher regex = DATE_PATTERN.matcher(m.value());
				if (regex.find()) return regex.group();
			}
		}
		// 兜底：扫所有框找日期正则（跳过身份证/金额框）
		for (PPOcrV6Result box : results) {
			String text = box.text();
			if (text == null || text.isEmpty()) continue;
			// 跳过身份证号（18 位，避免误识为日期）
			String stripped = text.replaceAll("[*\\s]", "");
			if (ID_NUMBER_PATTERN.matcher(stripped).find()) continue;
			// 跳过"身份证+姓名"合并框（含 4+ 个 *）
			if (text.contains("****")) continue;
			// 跳过金额框
			if (text.contains("￥") || text.contains("¥") || text.contains("元")) continue;
			Matcher regex = DATE_PATTERN.matcher(text);
			if (regex.find()) return regex.group();
		}
		return null;
	}

	/**
	 * 按多个时间标签依次尝试。
	 */
	private static String timeByLabel(List<PPOcrV6Result> results, String... labels) {
		for (String label : labels) {
			LabeledMatch m = LabelMatcher.matchValueWithBox(results, label);
			if (m.hasValue()) {
				Matcher regex = TIME_PATTERN.matcher(m.value());
				if (regex.find()) return regex.group();
			}
		}
		return null;
	}

	/**
	 * 从"日期+时间"合并框中切出日期与时间。
	 * 支持 "2014年09月09日15:52开"、"2024年12月08日00:00开" 等格式。
	 */
	private static DateTimeSplit extractDateTimeFromMergedBox(List<PPOcrV6Result> results) {
		for (PPOcrV6Result r : results) {
			String text = r.text();
			if (text == null || text.isEmpty()) continue;
			DateTimeSplit split = matchDateTimeOpen(text);
			if (split == null) {
				split = matchDateTime(text);
			}
			if (split != null) {
				log.debug("火车票解析：从合并框 \"{}\" 切出 date=\"{}\" time=\"{}\"", text, split.date(), split.time());
				return split;
			}
		}
		return new DateTimeSplit(null, null);
	}

	/**
	 * 匹配"日期+时间+开"格式的合并框（如 "2014年09月09日15:52开"）。
	 */
	private static DateTimeSplit matchDateTimeOpen(String text) {
		Matcher m = DATE_TIME_OPEN_PATTERN.matcher(text);
		if (!m.find()) return null;
		String date = m.group(1);
		String time = findTimeAfter(text, m.start() + date.length());
		return new DateTimeSplit(date, time);
	}

	/**
	 * 匹配"日期+时间"（无"开"字）的合并框。
	 */
	private static DateTimeSplit matchDateTime(String text) {
		Matcher m = DATE_TIME_PATTERN.matcher(text);
		if (!m.find()) return null;
		String date = m.group(1);
		String time = findTimeAfter(text, m.start() + date.length());
		return new DateTimeSplit(date, time);
	}

	/**
	 * 从 {@code fromIndex} 起在文本中找 HH:mm 时间。
	 */
	private static String findTimeAfter(String text, int fromIndex) {
		if (fromIndex < 0 || fromIndex > text.length()) return null;
		Matcher m = TIME_PATTERN.matcher(text.substring(fromIndex));
		return m.find() ? m.group() : null;
	}

	/**
	 * 座位号：标签 + 正则兜底。
	 *
	 * <p>P0 优化：用 {@code find()} 而非 {@code matches()}，兼容"14车015号上铺"合并框。
	 */
	private static LabeledMatch parseSeatNumber(List<PPOcrV6Result> results) {
		LabeledMatch m = LabelMatcher.matchValueWithBox(results, "座位号");
		if (m.hasValue()) {
			Matcher regex = SEAT_NUMBER_PATTERN.matcher(m.value());
			if (regex.find()) {
				return LabeledMatch.of(trimSeatSuffix(regex.group()), m.matches());
			}
		}
		// 兜底：扫所有框
		for (PPOcrV6Result r : results) {
			String text = r.text();
			if (text == null || text.isEmpty()) continue;
			Matcher regex = SEAT_NUMBER_PATTERN.matcher(text);
			if (regex.find()) {
				return LabeledMatch.of(trimSeatSuffix(regex.group()), r);
			}
		}
		return LabeledMatch.textOnly(null);
	}

	/**
	 * 座位号后缀处理：去掉"上/中/下铺"等铺位标识。
	 */
	private static String trimSeatSuffix(String seat) {
		if (seat == null || seat.isEmpty()) return seat;
		for (int i = 0; i < seat.length(); i++) {
			char c = seat.charAt(i);
			if (c == '上' || c == '中' || c == '下' || c == '铺') {
				return seat.substring(0, i);
			}
		}
		return seat;
	}

	// ========================================================================
	// 乘客
	// ========================================================================

	/**
	 * 席别：标签定位 + 关键字兜底。
	 */
	private static LabeledMatch parseSeatClass(List<PPOcrV6Result> results) {
		// 1) 标签 "席别" / "座位类型"
		for (String label : CollUtil.listOf("席别", "座位类型")) {
			LabeledMatch m = LabelMatcher.matchValueWithBox(results, label);
			if (m.hasValue()) {
				String keyword = findFirstKeyword(m.value(), SEAT_CLASS_KEYWORDS);
				if (keyword != null) return LabeledMatch.of(keyword, m.matches());
				return m;
			}
		}
		// 2) 兜底：扫所有框找席别关键字
		for (PPOcrV6Result r : results) {
			String text = r.text();
			if (text == null || text.isEmpty()) continue;
			String keyword = findFirstKeyword(text, SEAT_CLASS_KEYWORDS);
			if (keyword != null) {
				log.debug("火车票解析：席别按关键字兜底 \"{}\"", keyword);
				return LabeledMatch.of(keyword, r);
			}
		}
		return LabeledMatch.textOnly(null);
	}

	/**
	 * 在文本中查找第一个命中的关键字（按列表顺序）。
	 */
	private static String findFirstKeyword(String text, List<String> keywords) {
		if (text == null) return null;
		for (String kw : keywords) {
			if (text.contains(kw)) return kw;
		}
		return null;
	}

	/**
	 * 乘客姓名：标签定位 + 合并框（身份证+姓名）剥值 + 兜底扫中文框。
	 */
	private static LabeledMatch parsePassengerName(List<PPOcrV6Result> results) {
		// 1) 标签定位（兼容独立框 + 合并框"姓名 张三"）
		for (String label : CollUtil.listOf("姓名", "乘客姓名")) {
			LabeledMatch m = LabelMatcher.matchValueFromPrefixWithBox(results, label);
			if (m.hasValue()) {
				Matcher regex = NAME_PATTERN.matcher(m.value());
				if (regex.find()) {
					return LabeledMatch.of(regex.group(), m.matches());
				}
				return m;
			}
		}
		// 2) 合并框：身份证+姓名"2024231998****156X赵璇丽" → 剥身份证后取姓名
		for (PPOcrV6Result r : results) {
			String text = r.text();
			if (text == null || text.isEmpty()) continue;
			String name = extractNameFromIdMergedBox(text);
			if (name != null) {
				log.debug("火车票解析：姓名从身份证+姓名合并框 \"{}\" 切出 \"{}\"", text, name);
				return LabeledMatch.of(name, r);
			}
		}
		// 3) 兜底：扫所有框找 2~4 字中文（避免 OCR 噪声框混入）
		for (PPOcrV6Result r : results) {
			String text = r.text().trim();
			if (!NAME_PATTERN.matcher(text).matches()) continue;
			if (containsAnyChar(text, TRAIN_NOISE_TICKET_CHARS)) continue;
			if (text.matches(".*[A-Za-z].*") || text.matches(".*\\d.*")) continue;
			if (r.score() < 0.5f) continue;
			if (isStationOrInstitutionName(text)) {
				log.debug("火车票解析：跳过站名/机构名候选 \"{}\"", text);
				continue;
			}
			log.debug("火车票解析：乘客姓名按中文框兜底 \"{}\" (score={})", text, r.score());
			return LabeledMatch.of(text, r);
		}
		return LabeledMatch.textOnly(null);
	}

	/**
	 * 是否站名/机构名（用于姓名兜底时排除）。
	 */
	private static boolean isStationOrInstitutionName(String text) {
		return STATION_SUFFIX_PATTERN.matcher(text).matches()
			|| containsAnyChar(text, INSTITUTION_KEY_CHARS);
	}

	/**
	 * 从身份证+姓名合并框中切出姓名（如 "2024231998****156X赵璇丽" → "赵璇丽"）。
	 *
	 * <p>规则：先匹配身份证（含脱敏），剩余尾部连续 2-6 字中文作为姓名。
	 */
	private static String extractNameFromIdMergedBox(String text) {
		if (text == null || text.isEmpty()) return null;
		// 身份证部分可能含 *（脱敏）
		// 模式：17位数字 + 1位数字/X（中间允许 4 个 *）
		Matcher m = MERGED_ID_PART_PATTERN.matcher(text);
		if (!m.find()) return null;
		String after = text.substring(m.end());
		Matcher nameM = MERGED_NAME_PATTERN.matcher(after);
		if (!nameM.find()) return null;
		String name = nameM.group(1);
		// 排除站名/机构名
		if (isStationOrInstitutionName(name)) return null;
		return name;
	}

	/**
	 * 身份证号：标签定位 + 部分脱敏保留 + 全图兜底。
	 *
	 * <p>P0 优化：OCR 经常输出 "3101082006****0000" 这种 4 星号脱敏形式，
	 * 旧版要求"剥星号后剩 18 位"才返回，但实际 14+4=18 位数字是合理的脱敏形式。
	 * 改为：剥星号后 14-18 位数字都返回脱敏原样。
	 * 进一步：票面"身份证号"label 经常被吞，全图扫 ID 模式兜底。
	 *
	 * <p>关键：从合并框"身份证+姓名"中只切身份证部分（不含尾部中文姓名）。
	 */
	private static LabeledMatch parseIdNumber(List<PPOcrV6Result> results) {
		// 1) 标签定位
		for (String label : CollUtil.listOf("身份证号", "证件号码", "公民身份号码")) {
			LabeledMatch m = LabelMatcher.matchValueWithBox(results, label);
			if (m.hasValue()) {
				String extracted = extractIdNumber(m.value());
				if (extracted != null) {
					return LabeledMatch.of(extracted, m.matches());
				}
			}
		}
		// 2) 全图兜底：找含"****"的合并框
		for (PPOcrV6Result r : results) {
			String text = r.text();
			if (text == null || text.isEmpty()) continue;
			if (isAmountOrDateText(text)) continue;
			// 必须是含 **** 的脱敏形式（避免误抓 18 位社会信用代码、车票号等）
			if (text.contains("****")) {
				String extracted = extractIdNumber(text);
				if (extracted != null) {
					return LabeledMatch.of(extracted, r);
				}
			}
		}
		// 3) 再兜底：扫纯 18 位数字（最后一道防线）
		for (PPOcrV6Result r : results) {
			String text = r.text();
			if (text == null || text.isEmpty()) continue;
			if (isAmountOrDateText(text)) continue;
			Matcher m = ID_NUMBER_PATTERN.matcher(text);
			if (m.find()) return LabeledMatch.of(m.group(), r);
		}
		return LabeledMatch.textOnly(null);
	}

	/**
	 * 判断文本是否为金额或日期（用于身份证号兜底中排除）。
	 */
	private static boolean isAmountOrDateText(String text) {
		if (text.contains("￥") || text.contains("¥") || text.contains("元")) return true;
		return DATE_PATTERN.matcher(text).matches();
	}

	// ========================================================================
	// 金额
	// ========================================================================

	/**
	 * 从文本中提取身份证号（仅 18 位精确 或 14-18 位脱敏，剥尾部中文）。
	 */
	private static String extractIdNumber(String text) {
		if (text == null) return null;
		// 1) 优先按 18 位精确匹配（返回匹配部分，不含尾部）
		Matcher m = ID_NUMBER_PATTERN.matcher(text);
		if (m.find()) return m.group();
		// 2) 兼容脱敏：含 **** 的子串，剥星号后 11-18 位数字，总长 14-19
		//    例如 "2024231998****156X"（10 位 + 4 * + "156X" = 16 字符，剥 * 后 13 位）
		//    例如 "3101082006****0000"（10 位 + 4 * + 4 位 = 18 字符，剥 * 后 14 位）
		//    例如 "3101082006****0000X"（同上 + 末位 X = 19 字符）
		Matcher masked = MASKED_ID_PATTERN.matcher(text);
		while (masked.find()) {
			String idPart = masked.group();
			if (!idPart.contains("*")) continue;
			// 剥 * 后 11-18 位数字（身份证固定 18 位，允许 4-7 位被 * 替换）
			String stripped = idPart.replace("*", "");
			if (stripped.length() >= 11 && stripped.length() <= 18
				&& idPart.length() >= 14 && idPart.length() <= 19) {
				return idPart;
			}
		}
		return null;
	}

	/**
	 * 金额：标签定位 + 关键字兜底。
	 */
	private static LabeledMatch parseAmount(List<PPOcrV6Result> results, String primaryLabel) {
		// 1) 主标签 + 合并框剥值
		for (String label : CollUtil.listOf(primaryLabel, "票价", "金额")) {
			LabeledMatch m = LabelMatcher.matchValueFromPrefixWithBox(results, label);
			if (m.hasValue()) {
				Matcher regex = AMOUNT_PATTERN.matcher(m.value());
				if (regex.find()) {
					return LabeledMatch.of(regex.group(), m.matches());
				}
			}
		}
		// 2) 兜底：扫所有框
		return LabelMatcher.matchSubstringWithBox(results, text -> {
			Matcher regex = AMOUNT_PATTERN.matcher(text);
			return regex.find() ? regex.group() : null;
		});
	}

	// ========================================================================
	// 票号
	// ========================================================================

	/**
	 * 不含税金额：标签 "不含税金额" / "税前金额"。
	 */
	private static LabeledMatch parseAmountExcludingTax(List<PPOcrV6Result> results) {
		for (String label : CollUtil.listOf("不含税金额", "税前金额")) {
			LabeledMatch m = LabelMatcher.matchValueFromPrefixWithBox(results, label);
			if (m.hasValue()) {
				Matcher regex = AMOUNT_PATTERN.matcher(m.value());
				if (regex.find()) {
					return LabeledMatch.of(regex.group(), m.matches());
				}
			}
		}
		return LabeledMatch.textOnly(null);
	}

	/**
	 * 车票号：放宽到 7-10 位（实际纸质票号多为 7-8 位，如 E014470 / R093443 / U028534）。
	 *
	 * <p>P0 优化：旧版要求 10 位，遗漏了所有 7 位票号。
	 * P0 优化：优先匹配字母前缀票号（E/R/U 开头）避免被"xxxxxxxxxxxE014470"
	 * 形式的复合串误抓前面的纯数字段。
	 */
	private static LabeledMatch parseTicketNo(List<PPOcrV6Result> results) {
		// 1) 标签 "车票号"
		for (String label : CollUtil.listOf("车票号", "票号")) {
			LabeledMatch m = LabelMatcher.matchValueWithBox(results, label);
			if (m.hasValue()) {
				// 优先字母前缀（E/R/U 开头）
				Matcher alpha = ALPHA_TICKET_PATTERN.matcher(m.value());
				if (alpha.find()) return LabeledMatch.of(alpha.group(), m.matches());
				Matcher numeric = TICKET_NO_PATTERN.matcher(m.value());
				if (numeric.find()) return LabeledMatch.of(numeric.group(), m.matches());
				// 兼容含空格/短横线的票号
				String stripped = m.value().replaceAll("[\\s-]", "");
				if (stripped.length() >= 7 && stripped.length() <= 10 && stripped.matches("\\d{7,10}")) {
					return LabeledMatch.of(stripped, m.matches());
				}
			}
		}
		// 2) 优先扫字母前缀票号（票面底部"XX售"前的票号通常是字母+数字）
		for (PPOcrV6Result r : results) {
			String text = r.text();
			if (text == null || text.isEmpty()) continue;
			Matcher alpha = ALPHA_TICKET_PATTERN.matcher(text);
			if (alpha.find()) return LabeledMatch.of(alpha.group(), r);
		}
		// 3) 兜底：扫所有 7-10 位连续数字
		PPOcrV6Result best = null;
		String bestHit = null;
		int bestLen = Integer.MAX_VALUE;
		for (PPOcrV6Result r : results) {
			String text = r.text();
			if (text == null || text.isEmpty()) continue;
			// 排除明确是其他长 ID 的框（避免误抓 18+ 位）
			if (containsAny(text, TICKET_NOISE_CHARS)) continue;
			Matcher numeric = TICKET_NO_PATTERN.matcher(text);
			while (numeric.find()) {
				String hit = numeric.group();
				// 排除全 0 的占位符
				if (hit.matches("0+")) continue;
				if (bestHit == null || hit.length() < bestLen) {
					bestLen = hit.length();
					bestHit = hit;
					best = r;
				}
			}
		}
		if (best == null) return LabeledMatch.textOnly(null);
		return LabeledMatch.of(bestHit, best);
	}

	private static LabeledMatch parseInvoiceNo(List<PPOcrV6Result> results) {
		LabeledMatch m = LabelMatcher.matchValueFromPrefixWithBox(results, "发票号码");
		if (m.hasValue()) {
			Matcher regex = INVOICE_NO_PATTERN.matcher(m.value());
			if (regex.find()) return LabeledMatch.of(regex.group(), m.matches());
		}
		return LabelMatcher.matchSubstringWithBox(results, text -> {
			Matcher regex = INVOICE_NO_PATTERN.matcher(text);
			return regex.find() ? regex.group() : null;
		});
	}

	private static LabeledMatch parseETicketNo(List<PPOcrV6Result> results) {
		// 1) 标签定位
		LabeledMatch m = LabelMatcher.matchValueWithBox(results, "电子客票号");
		if (m.hasValue()) {
			Matcher regex = ETICKET_NO_PATTERN.matcher(m.value());
			if (regex.find()) return LabeledMatch.of(regex.group(), m.matches());
		}
		// 2) P1 优化：全图找 25 位纯数字（票面"电子客票号"label 经常被吞）
		for (PPOcrV6Result r : results) {
			String text = r.text();
			if (text == null || text.isEmpty()) continue;
			// 排除明显是其他长 ID 的（脱敏身份证含 *，会含"****"）
			if (text.contains("*")) continue;
			// 排除含字母的（电子客票号纯数字）
			if (text.matches(".*[A-Za-z]+.*")) continue;
			// 排除金额/价格（含 ¥/￥/元）
			if (text.contains("¥") || text.contains("￥") || text.contains("元")) continue;
			// 必须是 25 位连续数字（可能含分隔符）
			String digits = text.replaceAll("[^0-9]", "");
			if (digits.length() == 25) return LabeledMatch.of(digits, r);
		}
		return LabeledMatch.textOnly(null);
	}

	// ========================================================================
	// 其他
	// ========================================================================

	private static LabeledMatch parseInvoiceDate(List<PPOcrV6Result> results) {
		LabeledMatch m = LabelMatcher.matchValueFromPrefixWithBox(results, "开票日期");
		if (m.hasValue()) {
			Matcher regex = DATE_PATTERN.matcher(m.value());
			if (regex.find()) return LabeledMatch.of(regex.group(), m.matches());
		}
		return LabeledMatch.textOnly(null);
	}

	/**
	 * 售站解析：标签定位 + 票面底部"XX售"模式兜底。
	 *
	 * <p>P0 优化：旧版只走标签定位，实际票面"售站"标签经常被吞（如 OCR 框只有"天津售"、
	 * "银川售"等），需要从票面底部兜底。
	 */
	private static LabeledMatch parseSellStation(List<PPOcrV6Result> results) {
		// 1) 标签
		LabeledMatch m = LabelMatcher.matchValueWithBox(results, "售站");
		if (m.hasValue()) return m;
		// 2) 兜底：票面底部（y > 全图 y 中位数）找"XX售"模式
		int maxY = 0;
		for (PPOcrV6Result r : results) {
			maxY = Math.max(maxY, LabelMatcher.maxY(r));
		}
		int bottomThreshold = maxY * SELL_STATION_BOTTOM_RATIO_NUM / SELL_STATION_BOTTOM_RATIO_DEN;
		PPOcrV6Result best = null;
		String bestStation = null;
		int bestY = Integer.MIN_VALUE;
		for (PPOcrV6Result r : results) {
			String text = r.text();
			if (text == null || text.isEmpty()) continue;
			if (LabelMatcher.minY(r) < bottomThreshold) continue;
			Matcher regex = SELL_STATION_PATTERN.matcher(text);
			if (regex.find()) {
				int y = (LabelMatcher.minY(r) + LabelMatcher.maxY(r)) / 2;
				if (y > bestY) {
					bestY = y;
					best = r;
					bestStation = regex.group(1);
				}
			}
		}
		if (best == null) return LabeledMatch.textOnly(null);
		log.debug("火车票解析：售站按底部正则兜底 \"{}\"", bestStation);
		return LabeledMatch.of(bestStation, best);
	}

	private static LabeledMatch parseSerialNumber(List<PPOcrV6Result> results) {
		LabeledMatch m = LabelMatcher.matchValueWithBox(results, "序列号");
		if (m.hasValue()) {
			// 序列号通常是 12~20 位数字字母混合
			String cleaned = m.value().replaceAll("\\s+", "");
			return LabeledMatch.of(cleaned, m.matches());
		}
		return LabeledMatch.textOnly(null);
	}

	private static LabeledMatch parseChangedFlag(List<PPOcrV6Result> results) {
		// 标签 "标识" / "改签标识"
		for (String label : CollUtil.listOf("标识", "改签标识")) {
			LabeledMatch m = LabelMatcher.matchValueFromPrefixWithBox(results, label);
			if (m.hasValue()) {
				String keyword = findFirstKeyword(m.value(), CHANGED_FLAG_KEYWORDS);
				if (keyword != null) return LabeledMatch.of(keyword, m.matches());
			}
		}
		// 兜底：扫所有框
		for (PPOcrV6Result r : results) {
			String text = r.text();
			if (text == null || text.isEmpty()) continue;
			String keyword = findFirstKeyword(text, CHANGED_FLAG_KEYWORDS);
			if (keyword != null) {
				log.debug("火车票解析：改签标识按关键字兜底 \"{}\"", keyword);
				return LabeledMatch.of(keyword, r);
			}
		}
		return LabeledMatch.textOnly(null);
	}

	// ========================================================================
	// 共享工具
	// ========================================================================

	/**
	 * 站名清洗：去"站"后缀、去噪声词、限长 2-6 字。
	 */
	private static String cleanStation(String text) {
		if (text == null || text.isEmpty()) return null;
		String cleaned = text.trim();
		// 去 "站" 后缀
		if (cleaned.endsWith("站")) {
			cleaned = cleaned.substring(0, cleaned.length() - 1);
		}
		if (cleaned.length() < 2 || cleaned.length() > 6) return null;
		if (!cleaned.matches("[\\u4e00-\\u9fa5]{2,6}")) return null;
		return cleaned;
	}

	/**
	 * 按位置兜底选取站名。
	 *
	 * <p>P0 优化：兼容无"站"后缀的站名（如"平顶山西"、"上海"），但只接受
	 * 2-5 字纯中文（避免误识别"国家税务总局"等机构名为站名）。
	 *
	 * <p>到达站（right）优先 y 更大的候选（更靠下，OCR 中始发在到达上方）。
	 *
	 * @param maxY 票面顶部 y 上限（超过此值不算票面顶部）
	 * @param maxX 左侧/右侧 x 边界（Integer.MAX_VALUE 表示不限制）
	 * @param side "left" / "right"，决定取 x 最小还是 x 大于全图中间
	 * @param role 日志用场景名（"始发"/"到达"）
	 * @return 站名（去掉"站"后缀）
	 */
	private static LabeledMatch pickStationByPosition(List<PPOcrV6Result> results,
													  int maxY, int maxX, String side, String role) {
		int imgMaxX = 0;
		for (PPOcrV6Result r : results) {
			imgMaxX = Math.max(imgMaxX, LabelMatcher.maxX(r));
		}
		int threshold = (side.equals("left")) ? maxX : (int) (imgMaxX * 0.5);
		PPOcrV6Result best = null;
		int bestScore = Integer.MAX_VALUE;
		String bestStation = null;
		for (PPOcrV6Result r : results) {
			String text = r.text().trim();
			if (text.length() < 2 || text.length() > 6) continue;
			if (LabelMatcher.maxY(r) > maxY) continue;
			int x0 = LabelMatcher.minX(r);
			if (side.equals("left")) {
				if (x0 > maxX) continue;
			} else {
				if (x0 < threshold) continue;
			}
			// 优先匹配带"站"后缀的框（更确凿）
			boolean hasStationSuffix = text.endsWith("站");
			String station;
			if (hasStationSuffix) {
				station = text.substring(0, text.length() - 1);
			} else {
				// 不带"站"后缀：只接受 2-5 字纯中文
				if (!text.matches("[\\u4e00-\\u9fa5]{2,5}")) continue;
				// 排除常见机构/标签词
				if (containsAnyChar(text, STATION_NOISE_CHARS)) continue;
				station = text;
			}
			if (!station.matches("[\\u4e00-\\u9fa5]{2,5}")) continue;
			// 综合分：有后缀优先（减 100000），然后 x0 越小越好（left）
			int yCenter = (LabelMatcher.minY(r) + LabelMatcher.maxY(r)) / 2;
			int score;
			if (side.equals("left")) {
				// 始发站：x0 越小越好
				score = x0;
			} else {
				// 到达站：y 越大越好（更靠下，OCR 中始发→到达是上下布局）
				score = -yCenter;
			}
			if (!hasStationSuffix) score += 100000; // 不带后缀的优先级低
			if (score < bestScore) {
				bestScore = score;
				best = r;
				bestStation = station;
			}
		}
		if (best == null) return LabeledMatch.textOnly(null);
		log.debug("火车票解析：{} 站按位置兜底 \"{}\"", role, bestStation);
		return LabeledMatch.of(bestStation, best);
	}

	/**
	 * 判断字符串是否含任一关键字。
	 */
	private static boolean containsAny(String text, Set<String> keywords) {
		if (text == null || keywords == null) return false;
		for (String kw : keywords) {
			if (text.contains(kw)) return true;
		}
		return false;
	}

	/**
	 * 判断字符串是否含任一单字关键字。
	 */
	private static boolean containsAnyChar(String text, Set<Character> chars) {
		if (text == null || chars == null) return false;
		for (char c : chars) {
			if (text.indexOf(c) >= 0) return true;
		}
		return false;
	}

	/**
	 * 返回第一个非 null 的参数。
	 */
	private static String firstNonNull(String... values) {
		if (values == null) return null;
		for (String v : values) {
			if (v != null) return v;
		}
		return null;
	}

	@Override
	public TrainTicketResult parseResults(List<PPOcrV6Result> results) {
		TrainTicketResult r = new TrainTicketResult();
		r.setRawResults(new ArrayList<>(results));

		// 行程
		applyField(r, "departure", parseDeparture(results));
		applyField(r, "arrival", parseArrival(results));
		applyField(r, "trainNumber", parseTrainNumber(results));
		applyDepartureDateTime(r, results);
		applyField(r, "seatNumber", parseSeatNumber(results));
		applyField(r, "seatClass", parseSeatClass(results));

		// 乘客
		applyField(r, "passengerName", parsePassengerName(results));
		applyField(r, "idNumber", parseIdNumber(results));

		// 金额
		applyField(r, "amount", parseAmount(results, "车票金额"));
		applyField(r, "amountExcludingTax", parseAmountExcludingTax(results));

		// 票号
		applyField(r, "ticketNo", parseTicketNo(results));
		applyField(r, "invoiceNo", parseInvoiceNo(results));
		applyField(r, "eTicketNo", parseETicketNo(results));

		// 其他
		applyField(r, "invoiceDate", parseInvoiceDate(results));
		applyField(r, "sellStation", parseSellStation(results));
		applyField(r, "serialNumber", parseSerialNumber(results));
		applyField(r, "changedFlag", parseChangedFlag(results));

		return r;
	}

	/**
	 * 日期时间切分结果。
	 */
	@lombok.Value
	@Accessors(fluent = true)
	private static class DateTimeSplit {
		private final String date;
		private final String time;
	}
}
