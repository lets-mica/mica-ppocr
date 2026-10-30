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

package net.dreamlu.mica.ai.ppocr.structured.parser.taxi;

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
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 出租车票 OCR 结构化解析器。
 *
 * <p>兼容全国各地出租车票版式（套打偏移、油墨污损、标签被吞、标签与值合并等）；
 * 字段对齐百度 OCR 出租车票接口。
 *
 * <p>策略概览：
 * <ul>
 *   <li><b>发票代码 / 号码</b>：标签优先 + 12/8 位纯数字正则兜底；</li>
 *   <li><b>车牌号</b>：标签定位（"车牌号"/"车号"）+ 全图正则兜底
 *       （兼容无省份字头 6-7 位字母数字，含横线归一化）；</li>
 *   <li><b>日期</b>：标签定位（"日期"/"开票日期"）+ 宽松日期正则
 *       （容忍 2 位年份、OCR 噪声分隔符）；</li>
 *   <li><b>上下车时间</b>：标签定位 + 时间范围切分 + 关键字兜底
 *       （"上车"/"下车" fragment，从合并框里切时间）；</li>
 *   <li><b>里程</b>：标签定位 + 几何兜底（y 中心最近值框优先，避免误选上方"单价"行）；</li>
 *   <li><b>金额四类</b>：标签定位 + 关键字兜底 + 底部正则兜底（总金额）；</li>
 *   <li><b>开票城市</b>：标签定位 + fragment 兜底 + 票面机构框中切城市名。</li>
 * </ul>
 *
 * <p>输出结果会填充 {@code TaxiReceiptResult#getRawResults()} 与
 * {@code TaxiReceiptResult#getFieldBoxes()}，便于页面高亮。
 */
@Slf4j
public class TaxiReceiptParser extends BaseStructuredParser<TaxiReceiptResult> {

	// ========================================================================
	// 几何兜底阈值
	// ========================================================================

	/**
	 * 几何兜底 y 重叠容差（像素），用于容忍边界 1px 相接。
	 */
	private static final int Y_OVERLAP_DELTA = 5;
	/**
	 * 短 label 兜底 y 距离权重（vs x 距离权重 1）。
	 */
	private static final int Y_DISTANCE_WEIGHT = 20;
	/**
	 * y 方向偏好（below/above）减分值。
	 */
	private static final int Y_HINT_SCORE = 5000;
	/**
	 * hint 正则命中减分值（极强优先）。
	 */
	private static final int HINT_REGEX_SCORE = 100_000;
	/**
	 * 总金额 y 必须低于"金额"label y 至少此像素。
	 */
	private static final int TOTAL_AMOUNT_Y_GAP = 30;
	/**
	 * 兜底最小 OCR 置信度。
	 */
	private static final float MIN_SCORE = 0.5f;
	/**
	 * 总金额最小置信度（更严格，过滤噪声）。
	 */
	private static final float TOTAL_MIN_SCORE = 0.5f;

	// ========================================================================
	// 正则常量
	// ========================================================================

	/**
	 * 发票代码：12 位纯数字。
	 */
	private static final Pattern INVOICE_CODE_PATTERN = Pattern.compile("\\d{12}");

	/**
	 * 发票号码：8 位纯数字。
	 */
	private static final Pattern INVOICE_NO_PATTERN = Pattern.compile("\\d{8}");

	/**
	 * 车牌号：省份简称 + 字母 + 5~6 位字母数字。
	 */
	private static final Pattern PLATE_NUMBER_PATTERN = Pattern.compile(
		"[\\u4e00-\\u9fa5][A-Z][A-Z0-9]{5,6}");

	/**
	 * 噪声文本（绝不可能是车牌的框）：上下单、TAXI/TAXIN 等英文残留。
	 * 严格用词边界，避免误杀"TaXIN"以外的真实车号框。
	 */
	private static final Pattern PLATE_NOISE_PATTERN = Pattern.compile(
		"(?i)\\b(taxi|上下单|等候)\\b");

	/**
	 * 宽松日期：支持 2 位或 4 位年份、"-./年" 分隔符、月日可为 1~2 位、
	 * OCR 噪声分隔符（"0:3" 也接受，会在归一化阶段把 : 替回 -）。
	 */
	private static final Pattern DATE_PATTERN = Pattern.compile(
		"\\d{2,4}[-./年:：]\\d{1,2}[-./月:：]\\d{1,2}日?");

	/**
	 * 极端宽松日期：仅数字+分隔符（兜底用，先抓再校验）。
	 * 包含 ":" ":" 作为 OCR 噪声分隔符的容忍。
	 */
	private static final Pattern DATE_LOOSE_PATTERN = Pattern.compile(
		"\\d{2,4}[-./年:：]\\d{1,2}[-./月:：]\\d{1,2}");

	/**
	 * 时间：HH:mm（24 小时制）。
	 */
	private static final Pattern TIME_PATTERN = Pattern.compile(
		"([01]?\\d|2[0-3]):[0-5]\\d");

	/**
	 * HH:mm 完整片段（不分组）。用 {@code (?:...)} 避免 capture group 编号污染。
	 */
	private static final String HHMM = "(?:[01]?\\d|2[0-3]):[0-5]\\d";

	/**
	 * 时间范围：HH:mm-HH:mm / HH:mm~HH:mm。
	 * 火车票/出租车票 OCR 偶尔把 "15:01-15:24" 识别为单个合并框，需要切分。
	 * <p>group(1) = 上车时间（含分钟），group(2) = 下车时间（含分钟）。
	 */
	private static final Pattern TIME_RANGE_PATTERN = Pattern.compile(
		"(" + HHMM + ")\\s*[-~]\\s*(" + HHMM + ")");

	/**
	 * 里程：纯数字 + 小数点 + 公里（兼容 "12.5km"、"14.2公里"）。
	 */
	private static final Pattern MILEAGE_PATTERN = Pattern.compile(
		"\\d+(?:\\.\\d|-\\d)?\\s*(?:km|公里)");

	/**
	 * 金额：¥/￥ + 数字 + 两位小数（容许 OCR 噪声 "-" 当小数点，"元" 可选后缀）。
	 */
	private static final Pattern AMOUNT_PATTERN = Pattern.compile(
		"[¥￥]?\\s*\\d+(?:[.-]\\d{1,2})?\\s*元?");

	/**
	 * 严格金额（带 ¥/￥ 前缀 + 至少 2 位数字 + 可选小数）：用于底部总金额兜底，
	 * 避免误抓"单价 2.30"等（且避免"¥0"单字金额被误命中）。
	 */
	private static final Pattern AMOUNT_STRICT_PATTERN = Pattern.compile(
		"[¥￥]\\s*\\d{2,}(?:[.-]\\d{1,2})?\\s*元?");

	/**
	 * 中文城市名：2~6 字（避免长字符串误命中）。
	 */
	private static final Pattern CITY_PATTERN = Pattern.compile("[\\u4e00-\\u9fa5]{2,6}");

	/**
	 * 行政区划后缀：真实城市名几乎都带其一。
	 * 不强制要求"市"（如"北京市"是 3 字含"市"，但"州"也可单字作为州名）。
	 * 强要求后缀可过滤掉"卡号/原额/余额"这类非城市 2 字词。
	 */
	private static final Pattern CITY_SUFFIX_PATTERN = Pattern.compile(
		"[\\u4e00-\\u9fa5]*(?:市|省|县|区|州|旗|镇|盟|地区|市辖区|自治州|自治县)$");

	/**
	 * 常见噪声词（不可能是城市的）：含其一即排除。
	 * 覆盖：发票、税务、附加、票价、车费、单价、公里、金额、号码 等。
	 */
	private static final Set<String> CITY_NOISE_KEYWORDS = CollUtil.setOf(
		"发票", "税务", "附加", "票价", "车费", "单价", "公里", "金额",
		"号码", "城市", "开票", "国家", "总局", "总局监制", "印务",
		"卡号", "原额", "余额", "密码", "合计", "总计"
	);

	/**
	 * 中国汽车牌照省份简称（避免误识别）。
	 */
	private static final Set<String> PLATE_PROVINCES = CollUtil.setOf(
		"京", "津", "沪", "渝", "冀", "豫", "云", "辽", "黑", "湘",
		"皖", "鲁", "新", "苏", "浙", "赣", "鄂", "桂", "甘", "晋",
		"蒙", "陕", "吉", "闽", "贵", "粤", "川", "青藏", "宁", "琼"
	);

	/**
	 * 短 label 兜底：找"上"/"下"/"上客"/"下客"/"车" 短 label 框。
	 */
	private static final Set<String> SHORT_LABELS = CollUtil.setOf("上", "下", "上客", "下客", "车");

	// ========================================================================
	// 入口
	// ========================================================================

	/**
	 * 构造出租车票解析器，绑定推理引擎。
	 *
	 * @param engine PP-OCRv6 推理引擎；可为 null（仅在仅调用 {@link #parseResults(List)} 时）
	 */
	public TaxiReceiptParser(PPOcrV6Engine engine) {
		super(engine);
	}

	/**
	 * 设置字段值并回填字段框到 {@code fieldBoxes}。
	 *
	 * @param r     结果对象
	 * @param name  字段名（fieldBoxes key）
	 * @param match 字段匹配结果
	 */
	private static void applyField(TaxiReceiptResult r, String name, LabeledMatch match) {
		if (match == null) return;
		if (match.value() != null) {
			switch (name) {
				case "invoiceCode":
					r.setInvoiceCode(match.value());
					break;
				case "invoiceNo":
					r.setInvoiceNo(match.value());
					break;
				case "plateNumber":
					r.setPlateNumber(match.value());
					break;
				case "date":
					r.setDate(match.value());
					break;
				case "boardingTime":
					r.setBoardingTime(match.value());
					break;
				case "alightingTime":
					r.setAlightingTime(match.value());
					break;
				case "mileage":
					r.setMileage(match.value());
					break;
				case "amount":
					r.setAmount(match.value());
					break;
				case "fuelSurcharge":
					r.setFuelSurcharge(match.value());
					break;
				case "bookingFee":
					r.setBookingFee(match.value());
					break;
				case "totalAmount":
					r.setTotalAmount(match.value());
					break;
				case "city":
					r.setCity(match.value());
					break;
				default: {
					/* no-op */
					break;
				}
			}
		}
		LabelMatcher.applyFieldBox(r, name, match);
	}

	private static LabeledMatch parseInvoiceCode(List<PPOcrV6Result> results) {
		LabeledMatch m = LabelMatcher.matchValueFromPrefixWithBox(results, "发票代码");
		if (m.hasValue()) {
			Matcher regex = INVOICE_CODE_PATTERN.matcher(m.value());
			if (regex.find()) return LabeledMatch.of(regex.group(), m.matches());
		}
		// 兜底：要求整框文本恰好是 12 位数字（避免误抓含 8 位发票号码的长串）
		LabeledMatch fallback = LabelMatcher.matchPatternWithBox(results, INVOICE_CODE_PATTERN, false);
		if (fallback.hasValue()) return fallback;
		return LabelMatcher.matchSubstringWithBox(results, text -> {
			Matcher regex = INVOICE_CODE_PATTERN.matcher(text);
			return regex.find() ? regex.group() : null;
		});
	}

	// ========================================================================
	// 票号
	// ========================================================================

	private static LabeledMatch parseInvoiceNo(List<PPOcrV6Result> results) {
		LabeledMatch m = LabelMatcher.matchValueFromPrefixWithBox(results, "发票号码");
		if (m.hasValue()) {
			Matcher regex = INVOICE_NO_PATTERN.matcher(m.value());
			if (regex.find()) return LabeledMatch.of(regex.group(), m.matches());
		}
		// 兜底：要求整框文本恰好是 8 位数字（避免误抓含 12 位发票代码的长串）
		LabeledMatch fallback = LabelMatcher.matchPatternWithBox(results, INVOICE_NO_PATTERN, false);
		if (fallback.hasValue()) return fallback;
		return LabelMatcher.matchSubstringWithBox(results, text -> {
			Matcher regex = INVOICE_NO_PATTERN.matcher(text);
			return regex.find() ? regex.group() : null;
		});
	}

	private static LabeledMatch parsePlateNumber(List<PPOcrV6Result> results) {
		// 1) 标签定位（"车牌号"/"车号"）
		for (String label : CollUtil.listOf("车牌号", "车号")) {
			LabeledMatch m = LabelMatcher.matchValueFromPrefixWithBox(results, label);
			if (m.hasValue()) {
				Matcher regex = PLATE_NUMBER_PATTERN.matcher(m.value());
				if (regex.find()) return LabeledMatch.of(regex.group(), m.matches());
			}
		}
		// 2) 兜底：扫所有框找省份简称开头 + 字母 + 5~6 位字母数字
		for (PPOcrV6Result r : results) {
			String text = r.text().trim();
			if (text.length() < 6 || text.length() > 8) continue;
			if (!PLATE_PROVINCES.contains(text.substring(0, 1))) continue;
			if (PLATE_NUMBER_PATTERN.matcher(text).matches()) {
				log.debug("出租车票解析：车牌号按完整正则兜底 \"{}\"", text);
				return LabeledMatch.of(text, r);
			}
		}
		// 3) 二级兜底：无省份字头的 6~7 位字母数字（含横线归一化）
		//   覆盖 BU1346 / B-S4272 / AT3816 / H-W0220 等。
		for (PPOcrV6Result r : results) {
			String text = r.text().trim();
			if (text.length() < 5 || text.length() > 15) continue;
			// 整词匹配：纯 TAXI 标签（用 \A \z 而不是 \b，因为 \b 对中文无效）
			if (PLATE_NOISE_PATTERN.matcher(text).find()) continue;
			// 整个文本去掉非字母数字后是 5~7 位
			String alnum = text.replaceAll("[^A-Za-z0-9]", "");
			// 避免把 "TaXINBU1346" 这种 TAXI 复合串整串当车牌。
			// 启发式：滑动窗口，从 alnum 中找最优的 5-7 位车号子串（2 字母 + 4-5 数字）。
			String candidate = pickBestPlateWindow(alnum);
			if (candidate == null) continue;
			// 至少含 3 位数字（车号一般 4-5 位数字）
			long digitCount = candidate.chars().filter(Character::isDigit).count();
			if (digitCount < 3) continue;
			// 至少含 1 位字母（第二位必为字母）
			long letterCount = candidate.chars().filter(Character::isLetter).count();
			if (letterCount < 1) continue;
			// 第一个字符必须是字母（车号格式"字母+字母/数字..."）
			if (!Character.isLetter(candidate.charAt(0))) continue;
			log.debug("出租车票解析：车牌号按无省份字头兜底 \"{}\" → \"{}\"", text, candidate);
			return LabeledMatch.of(candidate, r);
		}
		return LabeledMatch.textOnly(null);
	}

	// ========================================================================
	// 行程
	// ========================================================================

	private static LabeledMatch parseDate(List<PPOcrV6Result> results) {
		// 1) 标签 "日期" / "开票日期"（先尝试"开票日期"，再尝试"日期"）
		for (String label : CollUtil.listOf("开票日期", "日期")) {
			LabeledMatch m = LabelMatcher.matchValueFromPrefixWithBox(results, label);
			if (m.hasValue()) {
				String normalized = normalizeDate(m.value());
				if (normalized != null) return LabeledMatch.of(normalized, m.matches());
			}
		}
		// 2) 标签 fragment 兜底（"日" / "期：" 单独成框，如 taxi4）
		LabeledMatch frag = LabelMatcher.matchValueByLabelKeywordWithBox(results, CollUtil.listOf("期", "日"));
		if (frag.hasValue()) {
			String normalized = normalizeDate(frag.value());
			if (normalized != null) return LabeledMatch.of(normalized, frag.matches());
		}
		// 3) 全图扫日期：宽松正则（含 2 位年份、OCR 噪声分隔符）
		for (PPOcrV6Result r : results) {
			String text = r.text();
			if (text == null || text.isEmpty()) continue;
			String normalized = normalizeDate(text);
			if (normalized != null) return LabeledMatch.of(normalized, r);
		}
		return LabeledMatch.textOnly(null);
	}

	/**
	 * 通用时间标签匹配：依次尝试多个标签 + 时间范围切分。
	 *
	 * <p>支持 "15:01-15:24" 形式的合并框，自动切分上车/下车时间；
	 * 解决 OCR 把整段时间识别为单框时 fragment "时间" 误命中
	 * 同一框导致 boarding=alighting=15:01 的问题。
	 *
	 * <p>三级回退：
	 * <ol>
	 *   <li>独立 label + 值（matchValueFromPrefix）；</li>
	 *   <li>合并框（"上车时间 09:00"）；</li>
	 *   <li>fragment 关键字（"上车"/"下车" 单独成框 + 右侧时间值框，或合并框中切时间）。</li>
	 * </ol>
	 *
	 * @return [上车时间, 下车时间]；任一为 null 表示未识别
	 */
	private static TimeRange parseTimeRange(List<PPOcrV6Result> results, String... labels) {
		// 1) 正常 label 流程
		for (String label : labels) {
			LabeledMatch m = LabelMatcher.matchValueFromPrefixWithBox(results, label);
			if (m == null || !m.hasValue()) continue;
			String value = m.value();
			// 优先切分 "HH:mm-HH:mm" 范围
			Matcher range = TIME_RANGE_PATTERN.matcher(value);
			if (range.find()) {
				return TimeRange.range(range.group(1), range.group(2), m.matches());
			}
			// 单时间
			Matcher tm = TIME_PATTERN.matcher(value);
			if (tm.find()) {
				return TimeRange.single(tm.group(), m.matches());
			}
		}
		// 2) fragment 兜底：从含"上车"或"下车"关键字的框里切时间
		//    处理 OCR 把"上车K0000>21:17"识别成合并框的场景
		String boardingHint = pickKeyword(labels, '上');
		String alightingHint = pickKeyword(labels, '下');
		TimeRange result = TimeRange.empty();
		LabeledMatch[] matches = new LabeledMatch[2];
		if (boardingHint != null) {
			LabeledMatch m = LabelMatcher.matchValueByKeywordWithBox(results, CollUtil.listOf(boardingHint),
				TaxiReceiptParser::extractTimeFromText);
			if (m.hasValue()) {
				result = result.withBoarding(m.value());
				matches[0] = m;
			}
		}
		if (alightingHint != null) {
			LabeledMatch m = LabelMatcher.matchValueByKeywordWithBox(results, CollUtil.listOf(alightingHint),
				TaxiReceiptParser::extractTimeFromText);
			if (m.hasValue()) {
				result = result.withAlighting(m.value());
				matches[1] = m;
			}
		}
		// 3) 短 label 兜底：单字"上"/"下"/"车" label + 右侧 y 重叠时间值
		//    处理 OCR 把"上车"识别成"车："+"21:56"的场景（如 taxi4）
		if (result.boarding() == null || result.alighting() == null) {
			TimeRange shortLabels = parseTimeByShortLabel(results);
			if (shortLabels.boarding() != null && result.boarding() == null) {
				result = result.withBoarding(shortLabels.boarding());
			}
			if (shortLabels.alighting() != null && result.alighting() == null) {
				result = result.withAlighting(shortLabels.alighting());
			}
		}
		// 合并 matches（用于 fieldBoxes 回填）
		List<PPOcrV6Result> allMatches = new ArrayList<>();
		if (matches[0] != null) allMatches.addAll(matches[0].matches());
		if (matches[1] != null) allMatches.addAll(matches[1].matches());
		return result.withMatches(allMatches);
	}

	/**
	 * 从 labels 里挑以指定首字开头的关键字。
	 */
	private static String pickKeyword(String[] labels, char firstChar) {
		for (String label : labels) {
			if (label != null && !label.isEmpty() && label.charAt(0) == firstChar) {
				return label;
			}
		}
		return null;
	}

	/**
	 * 从任意文本里抽出时间（优先时间范围，回退到单时间）。
	 */
	private static String extractTimeFromText(String text) {
		if (text == null || text.isEmpty()) return null;
		Matcher range = TIME_RANGE_PATTERN.matcher(text);
		if (range.find()) {
			return range.group(1) + '-' + range.group(2);
		}
		Matcher m = TIME_PATTERN.matcher(text);
		return m.find() ? m.group() : null;
	}

	/**
	 * 短 label 兜底：找"上"/"下"单字 label 框（OCR 把"上车"识别成"车："），
	 * 再在右侧 y 重叠的 HH:mm 框里取值。
	 *
	 * <p>典型场景（taxi4 重庆版）：OCR 把"上车"识别成单字"车："框 + 右侧 "21:56"
	 * 独立时间值框。
	 *
	 * <p>slot 分配规则：
	 * <ul>
	 *   <li>"上" / "上客" → slot 0（上车）；</li>
	 *   <li>"下" / "下客" → slot 1（下车）；</li>
	 *   <li>单字"车"：按 y 顺序，top → slot 0，bottom → slot 1。</li>
	 * </ul>
	 */
	private static TimeRange parseTimeByShortLabel(List<PPOcrV6Result> results) {
		// 找单字"上"/"下"/"上客"/"下客"/"车" /"车：" label 框
		List<PPOcrV6Result> labels = new ArrayList<>();
		for (PPOcrV6Result r : results) {
			String text = r.text().trim();
			// 标准化：去掉末尾中文/英文冒号、空格
			String normalized = text.replaceAll("[::：\\s]+$", "");
			if (SHORT_LABELS.contains(normalized)) {
				labels.add(r);
			}
		}
		if (labels.isEmpty()) return TimeRange.empty();
		// 按 y 排序
		labels.sort((a, b) -> {
			int ya = (LabelMatcher.minY(a) + LabelMatcher.maxY(a)) / 2;
			int yb = (LabelMatcher.minY(b) + LabelMatcher.maxY(b)) / 2;
			return Integer.compare(ya, yb);
		});
		String[] times = new String[2];
		int carIdx = 0;
		for (PPOcrV6Result label : labels) {
			String labelText = label.text().trim();
			int slot;
			if (labelText.startsWith("上")) slot = 0;
			else if (labelText.startsWith("下")) slot = 1;
			else {
				// 单字"车"：按 y 顺序，第一个 = slot 0，第二个 = slot 1
				slot = carIdx++;
			}
			if (slot > 1) continue;
			if (times[slot] != null) continue;
			// 在 label 右侧 y 重叠找 HH:mm
			times[slot] = findTimeOnRight(results, label);
		}
		return TimeRange.times(times[0], times[1]);
	}

	/**
	 * 在 label 框右侧 y 重叠的候选框中找 HH:mm 时间值。
	 */
	private static String findTimeOnRight(List<PPOcrV6Result> results, PPOcrV6Result label) {
		int labelCenterX = (LabelMatcher.minX(label) + LabelMatcher.maxX(label)) / 2;
		int labelCenterY = (LabelMatcher.minY(label) + LabelMatcher.maxY(label)) / 2;
		int labelMaxX = LabelMatcher.maxX(label);
		PPOcrV6Result best = null;
		int bestScore = Integer.MAX_VALUE;
		for (PPOcrV6Result r : results) {
			if (r == label) continue;
			String text = r.text();
			if (text == null || text.isEmpty()) continue;
			if (!TIME_PATTERN.matcher(text).matches()) continue;
			int x0 = LabelMatcher.minX(r);
			int rCenterX = (x0 + LabelMatcher.maxX(r)) / 2;
			if (rCenterX <= labelCenterX) continue;
			if (LabelMatcher.maxY(r) < LabelMatcher.minY(label)
				|| LabelMatcher.minY(r) > LabelMatcher.maxY(label)) continue;
			int dy = Math.abs((LabelMatcher.minY(r) + LabelMatcher.maxY(r)) / 2 - labelCenterY);
			int dx = x0 - labelMaxX;
			// 权重：y 距离 × 20 + x 距离 × 1，让 y 对齐成为决定性因素
			int score = dy * Y_DISTANCE_WEIGHT + dx;
			if (score < bestScore) {
				bestScore = score;
				best = r;
			}
		}
		return best == null ? null : best.text().trim();
	}

	private static LabeledMatch parseMileage(List<PPOcrV6Result> results) {
		// 0) 合并框 "里程 8.5公里" 形式（label 与 value 合并在一个框）
		//    直接从所有框里找含"里程"前缀的合并框
		for (PPOcrV6Result r : results) {
			String text = r.text();
			if (text != null && text.startsWith("里程") && text.length() > 2) {
				String normalized = normalizeNumber(text);
				if (normalized != null && !normalized.isEmpty()
					&& normalized.matches("\\d+(\\.\\d+)?")) {
					return LabeledMatch.of(normalized, r);
				}
			}
		}
		// P1 优化：直接走几何兜底（用 preferYDir="below" 偏好 label 下方的候选），
		// 不再用 LabelMatcher.matchValueFromPrefix（其 dx*10+dy 公式在 taxi1 中
		// 会选到"单价 2-30"而不是"里程 14-2"）。
		// 1) 几何兜底：找"里程"label 框 + 右侧 y 中心最接近的值框
		String hit = pickValueByYProximity(results, "里程",
			TaxiReceiptParser::normalizeMileageText, MILEAGE_PATTERN, "below");
		if (hit != null) {
			// 重新跑一次以拿到 box（pickValueByYProximity 只返回 value）
			PPOcrV6Result box = pickValueBoxByYProximity(results, "里程",
				TaxiReceiptParser::normalizeMileageText, MILEAGE_PATTERN, "below");
			return LabeledMatch.of(hit, box);
		}
		// 2) 兜底：扫所有框匹配 "数字km/公里"
		for (PPOcrV6Result r : results) {
			String text = r.text();
			if (text == null || text.isEmpty()) continue;
			Matcher regex = MILEAGE_PATTERN.matcher(text);
			if (!regex.find()) continue;
			String normalized = normalizeNumber(regex.group());
			if (normalized != null && !normalized.isEmpty()) {
				return LabeledMatch.of(normalized, r);
			}
		}
		return LabeledMatch.textOnly(null);
	}

	/**
	 * 归一化里程文本：把"14-2" → "14.2"、"2-30" → "2.30"（OCR 横线误识别为小数点）。
	 */
	private static String normalizeMileageText(String text) {
		String normalized = normalizeNumber(text);
		if (normalized == null || normalized.isEmpty()) return null;
		// 必须是合法数字（含至少 1 位整数部分）
		if (!normalized.matches("\\d+(\\.\\d+)?")) return null;
		return normalized;
	}

	/**
	 * 通用金额标签匹配：标签定位 + 关键字兜底。
	 *
	 * @param primaryLabel 主标签
	 * @param yHint        几何兜底的 y 方向偏好（"below" / "above" / null）
	 * @param altKeywords  OCR 漏识别标签时的兜底关键字（任一命中即视为候选框）
	 */
	private static LabeledMatch parseAmountWithYHint(List<PPOcrV6Result> results, String primaryLabel,
													 String yHint, String... altKeywords) {
		// 1) 主标签 + 合并框剥值
		LabeledMatch m = LabelMatcher.matchValueFromPrefixWithBox(results, primaryLabel);
		if (m.hasValue()) {
			String amt = cleanAmount(m.value());
			if (amt != null) return LabeledMatch.of(amt, m.matches());
		}
		// 2) 关键字兜底（label 完全漏识别，但"附加费"/"叫车"等关键字还在）
		if (altKeywords != null && altKeywords.length > 0) {
			LabeledMatch kw = LabelMatcher.matchValueByKeywordWithBox(results, CollUtil.listOf(altKeywords),
				TaxiReceiptParser::cleanAmount);
			if (kw.hasValue()) return kw;
		}
		// 3) 几何兜底：找 label 框 + 右侧 y 中心最近含数字的框
		String yHit = pickValueByYProximity(results, primaryLabel,
			TaxiReceiptParser::cleanAmount, null, yHint, altKeywords);
		if (yHit != null) {
			PPOcrV6Result box = pickValueBoxByYProximity(results, primaryLabel,
				TaxiReceiptParser::cleanAmount, null, yHint, altKeywords);
			return LabeledMatch.of(yHit, box);
		}
		return LabeledMatch.textOnly(null);
	}

	// ========================================================================
	// 金额
	// ========================================================================

	private static LabeledMatch parseTotalAmount(List<PPOcrV6Result> results) {
		// 1) 标签"总金额"（独立或合并框）。
		//    注意：不能用 findLabelBox("总金额") —— 它会把 "金额" 框当 fragment 返回，
		//    进而误用"金额"行的值 ¥40.60 当总金额。改用整框文本完全匹配 / 以 "总金额" 开头。
		PPOcrV6Result totalLabel = findExactOrPrefixBox(results, "总金额");
		if (totalLabel != null) {
			String labelText = totalLabel.text();
			// 1a) 合并框（"总金额 26.00"）：直接从 labelText 剥值
			if (labelText.length() > "总金额".length()) {
				String stripped = labelText.substring("总金额".length()).trim();
				String amt = cleanAmount(stripped);
				if (amt != null) return LabeledMatch.of(amt, totalLabel);
			}
			// 1b) 独立 label 框：右侧 y 重叠区域找值框
			LabeledMatch m = findValueRightOfLabel(results, totalLabel);
			if (m.hasValue()) {
				String amt = cleanAmount(m.value());
				if (amt != null) return LabeledMatch.of(amt, m.matches());
			}
		}
		// 2) 关键字"合计" / "总计" / "额" + y 重叠值框（label fragment）
		//   关键：单字"额"在 endsWith 模式下会误匹配"金额" label 框（"金额".endsWith("额")），
		//   因此需要先排除"金额"label 框，再做 fragment 匹配。
		PPOcrV6Result amountLabel = LabelMatcher.findLabelBox(results, "金额");
		List<PPOcrV6Result> fragResults = amountLabel == null
			? results
			: filterOut(results, amountLabel);
		for (String keyword : CollUtil.listOf("合计", "总计", "额")) {
			LabeledMatch frag = LabelMatcher.matchValueByLabelKeywordWithBox(fragResults, CollUtil.listOf(keyword));
			if (!frag.hasValue()) continue;
			String amt = cleanAmount(frag.value());
			if (amt == null) continue;
			// 校验：返回值所在 y 应明显低于"金额"label y（避免把"金额"误当"总金额"）
			if (amountLabel == null) return LabeledMatch.of(amt, frag.matches());
			int amountLabelY = centerY(amountLabel);
			int valueY = frag.matches().isEmpty() ? 0 : centerY(frag.matches().get(0));
			if (valueY > amountLabelY + TOTAL_AMOUNT_Y_GAP) return LabeledMatch.of(amt, frag.matches());
		}
		// 3) 底部正则兜底：票面最底部带 ¥ + 2位以上数字的框
		//    关键：要求 y 明显低于"金额"label y（避免误命中"金额"值框）
		int amountLabelY = amountLabel != null ? centerY(amountLabel) : -1;
		PPOcrV6Result bestBottom = null;
		String bestHit = null;
		int bestY = Integer.MIN_VALUE;
		for (PPOcrV6Result r : results) {
			String text = r.text();
			if (text == null || text.isEmpty()) continue;
			if (r.score() < TOTAL_MIN_SCORE) continue;
			Matcher regex = AMOUNT_STRICT_PATTERN.matcher(text);
			if (!regex.find()) continue;
			int y = centerY(r);
			// 必须明显低于"金额"label
			if (amountLabelY > 0 && y < amountLabelY + TOTAL_AMOUNT_Y_GAP) continue;
			if (y > bestY) {
				bestY = y;
				bestBottom = r;
				bestHit = regex.group();
			}
		}
		if (bestBottom != null) {
			String value = cleanAmount(bestHit);
			if (value != null) return LabeledMatch.of(value, bestBottom);
		}
		return LabeledMatch.textOnly(null);
	}

	/**
	 * 数字串归一化（含 OCR 噪声容错：把横线 "-" 也当作小数点）。
	 * 出租车票 OCR 在小字号/等宽字体下常把小数点误识别为横线（如 "40-60" 实为 "40.60"）。
	 */
	private static String normalizeNumber(String s) {
		if (s == null) return null;
		return s.replace('-', '.').replaceAll("[^0-9.]", "");
	}

	/**
	 * 严格的标签框定位：只接受"整框文本 == label"或"整框文本以 label 开头（合并框）"。
	 * 相比 {@link LabelMatcher#findLabelBox}，<b>不接受 fragment 匹配</b>：
	 * 避免 "总金额" 误命中 "金额" 框、"上车时间" 误命中 "时间" 框等 substring 误判。
	 *
	 * @param results OCR 识别结果列表
	 * @param label   字段标签
	 * @return 严格匹配的标签框；无匹配时返回 null
	 */
	private static PPOcrV6Result findExactOrPrefixBox(List<PPOcrV6Result> results, String label) {
		PPOcrV6Result exactBest = null;
		PPOcrV6Result prefixBest = null;
		int prefixBestLen = -1;
		for (PPOcrV6Result r : results) {
			String text = r.text();
			if (text == null || text.isEmpty()) continue;
			if (text.equals(label)) {
				exactBest = r;
			} else if (text.startsWith(label) && text.length() > prefixBestLen) {
				prefixBestLen = text.length();
				prefixBest = r;
			}
		}
		return exactBest != null ? exactBest : prefixBest;
	}

	/**
	 * 在标签框右侧 y 重叠区域内找最近的非标签框（用于已知 label 框的快速取值）。
	 * 与 {@link LabelMatcher#matchValueByCenterWithBox} 行为一致，但接受 PPOcrV6Result 而非 String。
	 */
	private static LabeledMatch findValueRightOfLabel(List<PPOcrV6Result> results, PPOcrV6Result labelBox) {
		if (labelBox == null) return LabeledMatch.textOnly(null);
		int labelCenterX = (LabelMatcher.minX(labelBox) + LabelMatcher.maxX(labelBox)) / 2;
		int labelMinY = LabelMatcher.minY(labelBox);
		int labelMaxY = LabelMatcher.maxY(labelBox);
		int labelMaxX = LabelMatcher.maxX(labelBox);
		PPOcrV6Result best = null;
		int bestScore = Integer.MAX_VALUE;
		for (PPOcrV6Result r : results) {
			if (r == labelBox) continue;
			String text = r.text();
			if (text == null || text.isEmpty()) continue;
			int x0 = LabelMatcher.minX(r);
			int rCenterX = (x0 + LabelMatcher.maxX(r)) / 2;
			if (rCenterX <= labelCenterX) continue;
			if (LabelMatcher.maxY(r) < labelMinY || LabelMatcher.minY(r) > labelMaxY) continue;
			int dy = Math.abs((LabelMatcher.minY(r) + LabelMatcher.maxY(r)) / 2 - (labelMinY + labelMaxY) / 2);
			int dx = x0 - labelMaxX;
			int score = dx * 10 + dy;
			if (score < bestScore) {
				bestScore = score;
				best = r;
			}
		}
		return best == null ? LabeledMatch.textOnly(null) : LabeledMatch.of(best.text(), best);
	}

	/**
	 * 从文本清洗金额：归一化横线、去前缀后缀、提取纯数字。
	 */
	private static String cleanAmount(String text) {
		if (text == null || text.isEmpty()) return null;
		Matcher m = AMOUNT_PATTERN.matcher(text);
		if (!m.find()) return null;
		String hit = m.group();
		// 优先纯数字：先归一化横线（OCR 噪声）再清非数字字符
		String digits = hit.replace('-', '.').replaceAll("[^0-9.]", "");
		if (digits.isEmpty()) return null;
		// 避免单数字（如 "1" / "2"）——金额一般是两位以上数字
		String[] parts = digits.split("\\.");
		if (parts.length > 0 && parts[0].length() < 1) return null;
		return digits;
	}

	private static LabeledMatch parseCity(List<PPOcrV6Result> results) {
		// 1) 标签定位（"开票城市"）
		LabeledMatch m = LabelMatcher.matchValueFromPrefixWithBox(results, "开票城市");
		if (m.hasValue()) {
			String city = cleanCity(m.value());
			if (city != null) return LabeledMatch.of(city, m.matches());
		}
		// 2) 关键字兜底（"城市" 单独成框 + 右侧值）
		LabeledMatch byKw = LabelMatcher.matchValueByKeywordWithBox(results, CollUtil.listOf("开票城市", "城市"),
			TaxiReceiptParser::cleanCity);
		if (byKw.hasValue()) return byKw;
		// 3) 标签 fragment 兜底（"开票" / "城市" fragment 右侧 y 重叠值）
		LabeledMatch frag = LabelMatcher.matchValueByLabelKeywordWithBox(results, CollUtil.listOf("开票", "城市"));
		if (frag.hasValue()) {
			String city = cleanCity(frag.value());
			if (city != null) return LabeledMatch.of(city, frag.matches());
		}
		// 4) P1 优化：从"XX市税务局"/"XX市物价局"等机构框中切出城市名
		//    模式：2-4 字 + "市/省/县/区/州" 后缀 + 机构关键字
		Pattern cityExtractor = Pattern.compile("([\\u4e00-\\u9fa5]{2,4}(?:市|省))");
		for (PPOcrV6Result r : results) {
			String text = r.text().trim();
			if (text.length() < 4 || text.length() > 12) continue;
			// 用正则匹配 "XX市+机构关键字" 模式
			Matcher cityM = cityExtractor.matcher(text);
			if (!cityM.find()) continue;
			String cityCandidate = cityM.group(1);
			// 排除明显非城市的（如"国家税务总局"前缀）
			if (cityCandidate.startsWith("国") || cityCandidate.startsWith("总")) continue;
			// 必须含 1 个行政区划后缀
			if (CITY_SUFFIX_PATTERN.matcher(cityCandidate).matches()) {
				log.debug("出租车票解析：开票城市从机构框 \"{}\" 切出 \"{}\"", text, cityCandidate);
				return LabeledMatch.of(cityCandidate, r);
			}
		}
		// 5) 兜底：扫所有 OCR 框找带行政区划后缀的纯中文城市名
		for (PPOcrV6Result r : results) {
			String text = r.text().trim();
			if (!CITY_PATTERN.matcher(text).matches()) continue;
			if (text.length() < 2 || text.length() > 6) continue;
			if (containsAny(text, CITY_NOISE_KEYWORDS)) continue;
			// 必须带行政区划后缀（过滤"卡号/原额/余额"等非城市词）
			if (!CITY_SUFFIX_PATTERN.matcher(text).matches()) continue;
			log.debug("出租车票解析：开票城市按中文框兜底 \"{}\"", text);
			return LabeledMatch.of(text, r);
		}
		return LabeledMatch.textOnly(null);
	}

	// ========================================================================
	// 其他
	// ========================================================================

	/**
	 * 清洗候选城市文本：必须纯中文、长度合理、且不含噪声词。
	 */
	private static String cleanCity(String text) {
		if (text == null || text.isEmpty()) return null;
		Matcher m = CITY_PATTERN.matcher(text);
		if (!m.find()) return null;
		String city = m.group();
		if (containsAny(city, CITY_NOISE_KEYWORDS)) return null;
		return city;
	}

	/**
	 * 几何兜底：找 label 框（支持多关键字模糊匹配） + 右侧 y 中心最近的
	 * 含有效内容的值框，按 y 中心距离 + x 距离综合打分。
	 *
	 * <p>比 {@link LabelMatcher#matchValueByCenterWithBox} 优先 y 对齐（避免
	 * 上方"单价"行被当成"里程"值），适合出租车票这类"label 在左、值紧邻
	 * 右侧、y 完全对齐"的版面。
	 *
	 * <p>任一关键字命中即视为 label 框候选。值框必须能被 valueExtractor 切出非空结果。
	 *
	 * @param hintRegex   可选的"提示正则"，值框文本若匹配则减 {@value #HINT_REGEX_SCORE} 分（极强优先）。
	 *                    用于强制偏好带单位/关键字的候选（如里程的"km"）。
	 * @param preferYDir  可选 y 方向偏好："below" 偏好 y 中心大于 label 中心的候选，
	 *                    "above" 偏好 y 中心小于 label 中心的候选。null = 中性。
	 * @param altKeywords OCR 漏识别标签时的备选关键字
	 */
	private static String pickValueByYProximity(List<PPOcrV6Result> results,
												String primaryLabel,
												Function<String, String> valueExtractor,
												Pattern hintRegex,
												String preferYDir,
												String... altKeywords) {
		PPOcrV6Result box = pickValueBoxByYProximity(results, primaryLabel, valueExtractor, hintRegex, preferYDir, altKeywords);
		if (box == null) return null;
		return valueExtractor.apply(box.text());
	}

	// ========================================================================
	// 共享工具
	// ========================================================================

	/**
	 * 几何兜底（同 {@link #pickValueByYProximity}），返回命中的值框而非文本。
	 */
	private static PPOcrV6Result pickValueBoxByYProximity(List<PPOcrV6Result> results,
														  String primaryLabel,
														  Function<String, String> valueExtractor,
														  Pattern hintRegex,
														  String preferYDir,
														  String... altKeywords) {
		// 1) 找 label 框（先主标签，再 alt 关键字，再 fragment）
		PPOcrV6Result labelBox = LabelMatcher.findLabelBox(results, primaryLabel);
		if (labelBox == null && altKeywords != null && altKeywords.length > 0) {
			List<PPOcrV6Result> candidates = LabelMatcher.findBoxesByKeyword(results, altKeywords);
			// 优先短文本（避免"燃油附加费"+"Fueloilsurcharge"两个框里挑后者）
			PPOcrV6Result best = null;
			int bestLen = Integer.MAX_VALUE;
			for (PPOcrV6Result c : candidates) {
				if (c.text().length() < bestLen) {
					bestLen = c.text().length();
					best = c;
				}
			}
			labelBox = best;
		}
		if (labelBox == null) return null;
		// 2) 在 label 右侧 y 重叠的候选框中，按 y 中心距离 + x 距离选最优
		int labelCenterX = (LabelMatcher.minX(labelBox) + LabelMatcher.maxX(labelBox)) / 2;
		int labelCenterY = (LabelMatcher.minY(labelBox) + LabelMatcher.maxY(labelBox)) / 2;
		int labelMaxX = LabelMatcher.maxX(labelBox);
		int labelMinY = LabelMatcher.minY(labelBox);
		int labelMaxY = LabelMatcher.maxY(labelBox);
		PPOcrV6Result best = null;
		int bestScore = Integer.MAX_VALUE;
		for (PPOcrV6Result r : results) {
			if (r == labelBox) continue;
			String text = r.text();
			if (text == null || text.isEmpty()) continue;
			int x0 = LabelMatcher.minX(r);
			int rCenterX = (x0 + LabelMatcher.maxX(r)) / 2;
			if (rCenterX <= labelCenterX) continue;
			// y 重叠（要求至少 5 像素实际重叠，避免 OCR 边界 1px 接触误判）
			int rMinY = LabelMatcher.minY(r);
			int rMaxY = LabelMatcher.maxY(r);
			if (rMaxY < labelMinY + Y_OVERLAP_DELTA || rMinY > labelMaxY - Y_OVERLAP_DELTA) continue;
			String value = valueExtractor.apply(text);
			if (value == null) continue;
			int rCenterY = (rMinY + rMaxY) / 2;
			int dy = Math.abs(rCenterY - labelCenterY);
			int dx = x0 - labelMaxX;
			// 权重：y 距离 × 20 + x 距离 × 1，让 y 对齐成为决定性因素
			int score = dy * Y_DISTANCE_WEIGHT + dx;
			// hint 优先：值框文本若匹配 hintRegex，减 100000 分（极强优先）
			if (hintRegex != null && hintRegex.matcher(text).find()) {
				score -= HINT_REGEX_SCORE;
			}
			// y 方向偏好：below 偏好 y > labelCenter，above 反之
			if ("below".equals(preferYDir) && rCenterY > labelCenterY) {
				score -= Y_HINT_SCORE;
			} else if ("above".equals(preferYDir) && rCenterY < labelCenterY) {
				score -= Y_HINT_SCORE;
			}
			if (score < bestScore) {
				bestScore = score;
				best = r;
			}
		}
		if (best != null) {
			log.debug("出租车票解析：{} 按 y 中心最近兜底 (label={}, y score={})",
				primaryLabel, labelBox.text(), bestScore);
		}
		return best;
	}

	/**
	 * 不带 hint 的简化版（向后兼容）。
	 */
	private static String pickValueByYProximity(List<PPOcrV6Result> results,
												String primaryLabel,
												Function<String, String> valueExtractor,
												String... altKeywords) {
		return pickValueByYProximity(results, primaryLabel, valueExtractor, null, null, altKeywords);
	}

	/**
	 * 日期归一化：2 位年份 → 4 位、OCR 噪声分隔符（":" "：") → "-"
	 * 但保留"2024年12月08日"这种原始格式不被破坏。
	 *
	 * <p>处理优先级：
	 * <ol>
	 *   <li>已经是规范格式（"yyyy-MM-dd" / "yyyy/MM/dd" / "yyyy.MM.dd" / "yyyy年MM月dd日"）→ 原样返回；</li>
	 *   <li>OCR 噪声（"0:3"、"2021-0:3-13"）→ 归一化分隔符；</li>
	 *   <li>2 位年份（"21-04-29"）→ 补"20"前缀。</li>
	 * </ol>
	 */
	private static String normalizeDate(String text) {
		if (text == null || text.isEmpty()) return null;
		// 0) 先处理 OCR 噪声分隔符：把 ":" "：" 视作不存在
		//    例 "2021-0:3-13" → "2021-03-13"（删除 ":" 后 0 和 3 紧贴）
		//    实际：删除 ":" 后是 "2021-03-13"，因为原字符串 0 紧接 ":" 紧接 3，删 ":" 后 0 紧接 3 → "03"
		String cleaned = text.replaceAll("[:：]", "");
		// 1) 规范格式：原样返回（保留 yyyy年MM月dd日）
		Matcher standard = DATE_PATTERN.matcher(cleaned);
		if (standard.find()) {
			String hit = standard.group();
			String yearMatch = hit.replaceAll("[-/.年月]", "-");
			String[] parts = yearMatch.split("-");
			if (parts.length == 3 && parts[0].length() == 4) {
				// 修复 OCR 噪声：日期段含 ":" 时按 0 补位
				return fixOcrNoise(hit);
			}
		}
		// 2) 宽松匹配：2 位年份 / OCR 噪声分隔符
		Matcher m = DATE_LOOSE_PATTERN.matcher(cleaned);
		if (!m.find()) {
			m = DATE_PATTERN.matcher(cleaned);
			if (!m.find()) return null;
		}
		// 统一分隔符为 '-'
		String normalized = m.group()
			.replaceAll("[/．]", "-")
			.replaceAll("[:：]", "-")
			.replaceAll("[年]", "-")
			.replaceAll("[月]", "-")
			.replaceAll("日?$", "");
		String[] parts = normalized.split("-");
		if (parts.length != 3) return null;
		String y = parts[0];
		String mo = parts[1];
		String d = parts[2];
		if (y.length() == 2) {
			y = "20" + y;
		}
		if (y.length() != 4) return null;
		if (mo.length() == 1) mo = '0' + mo;
		if (d.length() == 1) d = '0' + d;
		if (mo.length() != 2 || d.length() != 2) return null;
		return y + '-' + mo + '-' + d;
	}

	/**
	 * 修复日期中 OCR 噪声分隔符（如 "2021-0:3-13"）。
	 * 统一输出 "yyyy-MM-dd" 格式。
	 */
	private static String fixOcrNoise(String date) {
		if (date == null || date.isEmpty()) return date;
		// 切分 [年, 月, 日]：先删除噪声分隔符
		String cleaned = date.replaceAll("[:：]", "");
		// 按 [-/.年月日] 切分
		String[] parts = cleaned.split("[-/.年月日]");
		if (parts.length != 3) return date;
		String yearPart = parts[0];
		String monthPart = parts[1];
		String dayPart = parts[2];
		if (yearPart.isEmpty() || monthPart.isEmpty() || dayPart.isEmpty()) return date;
		// 年份补 0
		if (yearPart.length() == 2) yearPart = "20" + yearPart;
		// 月日补 0
		if (monthPart.length() == 1) monthPart = '0' + monthPart;
		if (dayPart.length() == 1) dayPart = '0' + dayPart;
		// 校验
		if (yearPart.length() != 4) return date;
		if (monthPart.length() != 2 || dayPart.length() != 2) return date;
		return yearPart + '-' + monthPart + '-' + dayPart;
	}

	/**
	 * 计算 OCR 框的 y 中心。
	 */
	private static int centerY(PPOcrV6Result r) {
		return (LabelMatcher.minY(r) + LabelMatcher.maxY(r)) / 2;
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
	 * 从 alnum 字符串中滑动窗口取最优车号子串。
	 *
	 * <p>典型场景：OCR 把"TAXI" + 车号识别为复合串，如 "TaXINBU1346" →
	 * 通过滑动窗口从中选出 5-7 位的车号子串，优先选"2 字母 + 4-5 数字"型（如 "BU1346"）。
	 *
	 * <p>评分规则：
	 * <ul>
	 *   <li>长度 5-7 位 + ≥3 数字 + ≥1 字母 是基本条件；</li>
	 *   <li>分数越低越好：score = |letters - 2| * 10 + |digits - 5| * 5 + start*2；</li>
	 *   <li>优先字符串后半部分（更可能是真实车号）。</li>
	 * </ul>
	 */
	private static String pickBestPlateWindow(String alnum) {
		if (alnum == null || alnum.length() < 5) return null;
		// 长度 ≤ 7 直接用
		if (alnum.length() <= 7) {
			// 验证基本条件
			if (!Character.isLetter(alnum.charAt(0))) return null;
			long digitCount = alnum.chars().filter(Character::isDigit).count();
			long letterCount = alnum.chars().filter(Character::isLetter).count();
			if (digitCount < 3 || letterCount < 1) return null;
			return alnum;
		}
		String best = null;
		int bestScore = Integer.MAX_VALUE;
		// 滑动窗口：长度 5、6、7
		for (int winLen = 5; winLen <= 7; winLen++) {
			for (int start = 0; start + winLen <= alnum.length(); start++) {
				String sub = alnum.substring(start, start + winLen);
				// 第一个字符必须是字母
				if (!Character.isLetter(sub.charAt(0))) continue;
				long digitCount = sub.chars().filter(Character::isDigit).count();
				long letterCount = sub.chars().filter(Character::isLetter).count();
				if (digitCount < 3 || letterCount < 1) continue;
				// 评分：偏好 2 字母 + 5 数字
				int score = (int) (Math.abs(letterCount - 2) * 10
					+ Math.abs(digitCount - 5) * 5);
				// 优先字符串后半部分（更可能是真实车号）
				score += start * 2;
				if (score < bestScore) {
					bestScore = score;
					best = sub;
				}
			}
		}
		return best;
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

	/**
	 * 从列表中移除指定 box，返回新列表（原列表不变）。
	 *
	 * <p>用于把"金额"等主 label 框从 fragment 匹配候选中排除，
	 * 避免单字关键字（如"额"）通过 endsWith 误命中主 label。
	 */
	private static List<PPOcrV6Result> filterOut(List<PPOcrV6Result> results, PPOcrV6Result toRemove) {
		if (results == null || toRemove == null) return results;
		List<PPOcrV6Result> out = new ArrayList<>(results.size());
		for (PPOcrV6Result r : results) {
			if (r != toRemove) out.add(r);
		}
		return out;
	}

	@Override
	public TaxiReceiptResult parseResults(List<PPOcrV6Result> results) {
		TaxiReceiptResult r = new TaxiReceiptResult();
		r.setRawResults(new ArrayList<>(results));

		// 票号
		applyField(r, "invoiceCode", parseInvoiceCode(results));
		applyField(r, "invoiceNo", parseInvoiceNo(results));

		// 行程
		applyField(r, "plateNumber", parsePlateNumber(results));
		applyField(r, "date", parseDate(results));
		TimeRange times = parseTimeRange(results, "上车时间", "上客时间", "上车");
		TimeRange alighting = parseTimeRange(results, "下车时间", "下客时间", "下车");
		// 下车时间优先取 alighting 范围的后半；如 alighting 只有单时间，
		// 用 boarding 范围的后半（OCR 把"15:01-15:24"识别为合并框被两个标签都命中的场景）；
		// 最后退到 alighting 单时间。
		String boardingTime = firstNonNull(times.boarding(), times.single());
		String alightingTime = firstNonNull(alighting.alighting(), times.alighting(), alighting.single());
		applyField(r, "boardingTime", LabeledMatch.of(boardingTime, times.matches()));
		applyField(r, "alightingTime", LabeledMatch.of(alightingTime, alighting.matches()));
		applyField(r, "mileage", parseMileage(results));

		// 金额（保持 P1 实现：preferYDir hint + 关键字兜底）
		// P2 互斥分配实验表明对出租车金额行场景副作用大于收益（短 label 共享 box 误判），
		// 暂时只在 LabelMatcher 中保留算法，不在出租车/火车票解析器里启用。
		applyField(r, "amount", parseAmountWithYHint(results, "金额", null, "Fare"));
		applyField(r, "fuelSurcharge", parseAmountWithYHint(results, "燃油附加费", "below", "Fuel", "附加费"));
		applyField(r, "bookingFee", parseAmountWithYHint(results, "预约叫车服务费", "below", "叫车服务费", "叫车"));
		applyField(r, "totalAmount", parseTotalAmount(results));

		// 其他
		applyField(r, "city", parseCity(results));

		return r;
	}

	/**
	 * 时间范围记录：分别保存上车、下车、单独命中时间。
	 */
	@lombok.Value
	@Accessors(fluent = true)
	private static class TimeRange {
		private final String boarding;
		private final String alighting;
		private final String single;
		private final List<PPOcrV6Result> matches;

		/**
		 * 空记录。
		 */
		static TimeRange empty() {
			return new TimeRange(null, null, null, CollUtil.listOf());
		}

		/**
		 * 时间范围命中（"HH:mm-HH:mm"）。
		 */
		static TimeRange range(String boarding, String alighting, List<PPOcrV6Result> matches) {
			return new TimeRange(boarding, alighting, null, matches);
		}

		/**
		 * 单时间命中。
		 */
		static TimeRange single(String time, List<PPOcrV6Result> matches) {
			return new TimeRange(null, null, time, matches);
		}

		/**
		 * 短 label 兜底命中。
		 */
		static TimeRange times(String boarding, String alighting) {
			return new TimeRange(boarding, alighting, null, CollUtil.listOf());
		}

		/**
		 * 更新上车时间。
		 */
		TimeRange withBoarding(String v) {
			return new TimeRange(v, alighting, single, matches);
		}

		/**
		 * 更新下车时间。
		 */
		TimeRange withAlighting(String v) {
			return new TimeRange(boarding, v, single, matches);
		}

		/**
		 * 更新匹配框。
		 */
		TimeRange withMatches(List<PPOcrV6Result> newMatches) {
			return new TimeRange(boarding, alighting, single, newMatches);
		}
	}
}
