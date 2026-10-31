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

import lombok.extern.slf4j.Slf4j;
import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Engine;
import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Result;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.BaseStructuredParser;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.FieldConfidence;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.FieldSource;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.LabelMatcher;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.ParsedField;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.ValidationResult;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.Validations;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 身份证 OCR 结构化解析器（正反面合一）。
 *
 * <p>策略概览：
 * <ul>
 *   <li><b>版面判定</b>：扫描 OCR 框是否存在 "公民身份号码" 或 "姓名" 标签 → 正面；
 *       若存在 "签发机关" 或 "有效期限" 标签 → 反面；两者都没出现 → UNKNOWN。</li>
 *   <li><b>正面字段</b>：姓名/性别/民族/出生日期/住址 按标签定位；
 *       公民身份号码用 18/15 位正则兜底（应对 OCR 残缺 "公民身份号码" 标签）。</li>
 *   <li><b>反面字段</b>：签发机关 按 "签发机关" 标签定位；
 *       有效期限 从 "YYYY.MM.DD[-YYYY.MM.DD]" 文本中按 "." 分隔符切出起止。</li>
 *   <li><b>15 位兼容</b>：早期签发的 15 位身份证号（无校验位、第 7-12 位为 YYMMDD）
 *       一并支持；出生日期 OCR 残缺时从身份证号推算（15 位 YY 默认按 19YY 补全）。</li>
 *   <li><b>正确性层</b>：身份证号走 GB 11643 校验位（{@link Validations#validateIdCard}）——
 *       校验不过说明至少认错了一个字符，该字段置信度直接归零并标记需复核；
 *       性别缺失时由身份证号的性别位回填。</li>
 * </ul>
 */
@Slf4j
public class IdCardParser extends BaseStructuredParser<IdCardResult> {

	/**
	 * 正面字段标签（合并框切分用）：OCR 可能把 "性别男民族汉" 双标签连写进同一框。
	 */
	private static final String[] FRONT_LABELS = {"姓名", "性别", "民族", "出生", "住址", "公民身份号码"};
	/**
	 * 公民身份号码：18 位（末位 X 允许）。
	 */
	private static final Pattern ID_NUMBER_18_PATTERN = Pattern.compile("[0-9]{17}[0-9X]");
	/**
	 * 公民身份号码：15 位（早期身份证号，无校验位）。
	 */
	private static final Pattern ID_NUMBER_15_PATTERN = Pattern.compile("[0-9]{15}");
	/**
	 * 出生日期：yyyy 年 MM 月 dd 日（容忍空格、可选"日"字）。
	 */
	private static final Pattern BIRTH_DATE_PATTERN = Pattern.compile(
		"\\d{4}\\s*年\\s*\\d{1,2}\\s*月\\s*\\d{1,2}\\s*日?");
	/**
	 * 有效期限格式：YYYY.MM.DD[-YYYY.MM.DD] 或长期（"长期"）。
	 */
	private static final Pattern VALID_TERM_PATTERN = Pattern.compile(
		"\\d{4}\\.\\d{2}\\.\\d{2}(-\\d{4}\\.\\d{2}\\.\\d{2})?|长期");
	/**
	 * 住址关键字正则（省/市/县/区/镇/村/乡/旗/盟/州），用于无"住址"标签时按内容筛选。
	 */
	private static final Pattern ADDR_KEYWORD_PATTERN = Pattern.compile("[省市县区镇乡村旗盟州]");
	/**
	 * 日期关键字正则（年/月/日），用于排除被误判为地址的日期框。
	 */
	private static final Pattern DATE_KEYWORD_PATTERN = Pattern.compile("[年月日]");

	/**
	 * 构造身份证解析器，绑定推理引擎。
	 *
	 * @param engine PP-OCRv6 推理引擎；可为 null（仅在仅调用 {@link #parseResults(List)} 时）
	 */
	public IdCardParser(PPOcrV6Engine engine) {
		super(engine);
	}

	/**
	 * 版面判定：扫描特定标签的存在性。
	 *
	 * <p>先判断反面：反面字少、OCR 不易识别错误，优先级高于正面。
	 * 避免反面 OCR 残片（如"身"/"份"被匹配为"公民身份号码"残缺标签）导致误判为正面。
	 */
	private static IdCardSide detectSide(List<PPOcrV6Result> results) {
		// 先判断反面（反面字少，OCR 不易出错）
		boolean back = LabelMatcher.findLabelBox(results, "签发机关") != null
			|| LabelMatcher.findLabelBox(results, "有效期限") != null;
		if (back) {
			return IdCardSide.BACK;
		}
		boolean front = LabelMatcher.findLabelBox(results, "姓名") != null
			|| LabelMatcher.findLabelBox(results, "公民身份号码") != null;
		if (front) {
			return IdCardSide.FRONT;
		}
		log.warn("身份证解析：未能识别版面（无正面/反面特征标签）");
		return IdCardSide.UNKNOWN;
	}

	/**
	 * 性别提取：优先尝试合并框切割（兼容 "性别男民族汉" 双标签连写合并框 + "别男民族汉" "性" 字缺失场景），
	 * 未命中时走标准标签定位，最后兜底"标签下方 x 重叠"的值框查找
	 * （兼容 doc_ori 90° 旋转后值框堆叠在标签正下方的场景）。
	 */
	private static ParsedField parseGender(List<PPOcrV6Result> results) {
		// 1. 合并框切割：标准 "性别男民族汉" + 残片 "别男民族汉" / "性男民族汉" 单框
		LabelMatcher.LabeledMatch merged = LabelMatcher.matchSubstringWithBox(results, text -> {
			String std = cutAtNextLabel(afterLabel(text, "性别"));
			if (std != null) {
				return std;
			}
			// 残片兜底：text 以 "性" 或 "别" 起头，紧跟 "男" / "女"（小模型漏识别 "性别" 之一字）
			if (text.length() >= 2) {
				char first = text.charAt(0);
				char second = text.charAt(1);
				if ((first == '性' || first == '别') && (second == '男' || second == '女')) {
					return cutAtNextLabel(text.substring(1));
				}
			}
			return null;
		});
		if (merged.hasValue()) {
			return LabelMatcher.toParsedField(merged, FieldSource.LABEL);
		}
		// 2. 标准标签定位（值在右侧）
		LabelMatcher.LabeledMatch labelValue = LabelMatcher.matchValueFromPrefixWithBox(results, "性别");
		String cut = cutAtNextLabel(labelValue.value());
		if (cut != null) {
			return LabelMatcher.toParsedField(labelValue, FieldSource.LABEL, cut);
		}
		// 3. 兜底：值框堆叠在"性别"标签正下方（同 x 范围、y 在下方）
		LabelMatcher.LabeledMatch below = matchValueBelowLabel(results, "性别", "男|女");
		if (below.hasValue()) {
			return LabelMatcher.toParsedField(below, FieldSource.LABEL);
		}
		return ParsedField.missing();
	}

	/**
	 * 在指定标签框"正下方 + x 重叠"区域找一个值框，按 valuePattern 在文本首部切出值。
	 *
	 * <p>典型场景：doc_ori 90° 旋转后，"性别"label 和 "男民族汉" 值框 x 范围几乎相同、
	 * 上下堆叠；标准 {@code rCenterX > labelCenterX} 策略无法命中，此方法兜底。
	 *
	 * @param results      OCR 结果列表
	 * @param label        字段标签（用于定位 label 框）
	 * @param valuePattern 值文本首部需匹配的正则（如 "男|女"）
	 * @return 切出的字段值 + 值框；无候选时返回仅含 null value 的 LabeledMatch
	 */
	private static LabelMatcher.LabeledMatch matchValueBelowLabel(List<PPOcrV6Result> results, String label, String valuePattern) {
		PPOcrV6Result labelBox = LabelMatcher.findLabelBox(results, label);
		if (labelBox == null) {
			return LabelMatcher.LabeledMatch.textOnly(null);
		}
		int labelMinX = LabelMatcher.minX(labelBox);
		int labelMaxX = LabelMatcher.maxX(labelBox);
		int labelMaxY = LabelMatcher.maxY(labelBox);
		java.util.regex.Pattern p = java.util.regex.Pattern.compile(valuePattern);
		for (PPOcrV6Result r : results) {
			if (r == labelBox) {
				continue;
			}
			String text = r.text();
			if (text == null || text.isEmpty()) {
				continue;
			}
			int rMinX = LabelMatcher.minX(r);
			int rMaxX = LabelMatcher.maxX(r);
			int rMinY = LabelMatcher.minY(r);
			int rMaxY = LabelMatcher.maxY(r);
			// x 必须与 label 重叠（允许 5px 偏移，兼容轻微倾斜）
			if (rMaxX < labelMinX - 5 || rMinX > labelMaxX + 5) {
				continue;
			}
			// 候选必须在 label 下方或紧贴下方（rMaxY >= labelMaxY 附近）
			if (rMaxY < labelMaxY - 5) {
				continue;
			}
			// 取距 label 最近的候选（y 距离最小）
			int yDist = rMinY - labelMaxY;
			if (yDist < -10) {
				// 候选主要在 label 上方，不视为"下方"候选
				continue;
			}
			// 值文本首部必须匹配 valuePattern
			java.util.regex.Matcher m = p.matcher(text);
			if (!m.find() || m.start() > 2) {
				// 只接受首部匹配（容忍 1-2 字前缀噪声如空格）
				continue;
			}
			// 切出值：到下一个正面标签为止
			String value = text.substring(m.start());
			String cut = cutAtNextLabel(value);
			if (cut == null) {
				// 与旧实现保持一致：首个候选切不出值即判定该字段无值
				return LabelMatcher.LabeledMatch.missingValue();
			}
			return LabelMatcher.LabeledMatch.of(cut, r);
		}
		return LabelMatcher.LabeledMatch.textOnly(null);
	}

	/**
	 * 民族提取：优先尝试合并框切割（兼容 "性别男民族汉" 双标签连写合并框），未命中时走标准标签定位。
	 */
	private static ParsedField parseNation(List<PPOcrV6Result> results) {
		LabelMatcher.LabeledMatch merged = LabelMatcher.matchSubstringWithBox(results, text -> afterLabel(text, "民族"));
		if (merged.hasValue()) {
			return LabelMatcher.toParsedField(merged, FieldSource.LABEL);
		}
		LabelMatcher.LabeledMatch label = LabelMatcher.matchValueFromPrefixWithBox(results, "民族");
		if (label.hasValue()) {
			return LabelMatcher.toParsedField(label, FieldSource.LABEL);
		}
		return ParsedField.missing();
	}

	/**
	 * 取指定标签之后的文本，截断到下一个正面标签（合并框 "性别男民族汉" 切分用）。
	 *
	 * @param text  OCR 文本
	 * @param label 当前字段标签
	 * @return 标签之后到下一个标签之间的值；无标签或无值返回 null
	 */
	private static String afterLabel(String text, String label) {
		int idx = text.indexOf(label);
		if (idx < 0) {
			return null;
		}
		String rest = text.substring(idx + label.length());
		int end = rest.length();
		for (String next : FRONT_LABELS) {
			if (next.equals(label)) {
				continue;
			}
			int j = rest.indexOf(next);
			if (j >= 0 && j < end) {
				end = j;
			}
		}
		String value = rest.substring(0, end).trim();
		return value.isEmpty() ? null : value;
	}

	/**
	 * 合并框值截断：性别值后紧接 "民族汉" 时，只保留到下一个标签前。
	 */
	private static String cutAtNextLabel(String value) {
		if (value == null) {
			return null;
		}
		int end = value.length();
		for (String next : FRONT_LABELS) {
			if (next.equals("性别")) {
				continue;
			}
			int j = value.indexOf(next);
			if (j >= 0 && j < end) {
				end = j;
			}
		}
		String cut = value.substring(0, end).trim();
		return cut.isEmpty() ? null : cut;
	}

	/**
	 * 出生日期提取：按"出生"标签定位，可能跨多框（"1996 年 11 月 2 日"）。
	 *
	 * <p>取最靠左的 y 重叠框，与"出生"标签同行。
	 * 若标签定位结果不可用，回退正则（4 位年 + 1~2 位月 + 1~2 位日）；
	 * 仍不可用时，从身份证号推算（15 位 YY 默认按 19YY 补全，18 位 YYYY 直接取）。
	 */
	private static ParsedField parseBirthDate(List<PPOcrV6Result> results, String idNumber) {
		// 支持"出生1966年11月2日"合并框识别
		LabelMatcher.LabeledMatch labelValue = LabelMatcher.matchValueFromPrefixWithBox(results, "出生");
		if (labelValue.hasValue()) {
			// 出生日期格式较自由（"1966 年 11 月 2 日"），先信任标签定位结果
			return LabelMatcher.toParsedField(labelValue, FieldSource.LABEL);
		}
		LabelMatcher.LabeledMatch pattern = LabelMatcher.matchPatternWithBox(results, BIRTH_DATE_PATTERN, false);
		if (pattern.hasValue()) {
			return LabelMatcher.toParsedField(pattern, FieldSource.PATTERN);
		}
		// 兜底：身份证号推算（兼容 15 位/18 位 + OCR "出生" 标签整体残缺场景）
		String fromId = birthDateFromIdNumber(idNumber);
		if (fromId != null) {
			log.debug("身份证解析：出生日期身份证号推算命中 \"{}\"", fromId);
			return ParsedField.derived(fromId);
		}
		return ParsedField.missing();
	}

	/**
	 * 住址提取：按"住址"标签定位，支持合并框及跨多框/跨行（如"四川省金堂县平桥乡清堰" + "1组"）。
	 *
	 * <p>住址可能换行，需要拼接多个几何重叠或延伸的右侧/下方框。
	 *
	 * <p>核心算法逻辑：
	 * <ol>
	 *   <li>先用 {@link LabelMatcher#findLabelBox} 找"住址"标签；如果 OCR 把"住址"识别成"住址XXX"合并框，
	 *       则返回的 labelBox 是合并框，从中剥出独立的地址第一行（{@code firstLineFromMerged}）。</li>
	 *   <li><b>区域排斥代替单一 y 下界</b>：用"身份证号码框所在的矩形区域"作为排除区，候选
	 *       与号码框在 x/y 两方向均有<b>过半重叠</b>时剔除（任意相交会误杀倾斜图片中
	 *       续行底边与号码行顶边擦边的场景）。这同时覆盖两种布局：
	 *       <ul>
	 *         <li>标准布局（号码在地址下方）—— 候选与号码 y 重叠但 x 互不覆盖的情况被自然放过；</li>
	 *         <li>旋转布局（doc_ori 90° 旋转后号码在地址左侧同 y 范围）—— 原先仅用
	 *             {@code bottomLimitY = idMinY} 会把"地址续行（位于号码下方）"误剔，
	 *             区域排斥用 x 不重叠来放过续行、用 y 重叠来剔除真正落入号码列的噪声。</li>
	 *       </ul>
	 *   </li>
	 *   <li>X 轴放宽判定：废除对绝对 labelCenterX 的约束，使用 {@code rMaxX >= labelMinX - 10} 判定，
	 *       确保左下角短续行（如"1组"）不被误杀。</li>
	 *   <li>二维几何拓扑排序：同行按 X 升序，跨行按 Y 升序，保证多框拼接顺序准确无误。</li>
	 * </ol>
	 */

	/**
	 * 从身份证号推算出生日期（"yyyy 年 MM 月 dd 日" 格式）。
	 *
	 * <p>15 位身份证号为 YYMMDD，按 GB 11643-1999 早期签发规则默认按 19YY 补全。
	 * 18 位身份证号为 YYYYMMDD。
	 *
	 * @param idNumber 15/18 位身份证号
	 * @return 出生日期；长度不匹配返回 null
	 */
	private static String birthDateFromIdNumber(String idNumber) {
		if (idNumber == null) {
			return null;
		}
		if (idNumber.length() == 18) {
			return formatBirthDate(idNumber.substring(6, 10), idNumber.substring(10, 12),
				idNumber.substring(12, 14));
		}
		if (idNumber.length() == 15) {
			// 15 位 YY 默认按 19YY 补全（早期 15 位身份证号均为 19XX 年签发）
			return formatBirthDate("19" + idNumber.substring(6, 8), idNumber.substring(8, 10),
				idNumber.substring(10, 12));
		}
		return null;
	}

	/**
	 * 组装并校验身份证号推出的出生日期。
	 *
	 * <p>号码被 OCR 认错时，出生日期段可能拼出「13 月」「32 日」这类非法日期。
	 * 此处用 {@link LocalDate#of(int, int, int)} 校验月份范围、当月天数与闰年规则，
	 * 非法则返回 {@code null} —— <b>宁可不给日期，也不给一个假日期</b>。
	 *
	 * @param year  年（4 位字符串）
	 * @param month 月（2 位字符串）
	 * @param day   日（2 位字符串）
	 * @return 合法则返回 {@code yyyy年MM月dd日}，否则返回 null
	 */
	private static String formatBirthDate(String year, String month, String day) {
		int y = parseDigits(year);
		int m = parseDigits(month);
		int d = parseDigits(day);
		if (y < 0 || m < 0 || d < 0) {
			return null;
		}
		try {
			// LocalDate.of 会校验月份范围、当月天数与闰年规则
			LocalDate.of(y, m, d);
		} catch (DateTimeException e) {
			return null;
		}
		return year + "年" + month + "月" + day + "日";
	}

	/**
	 * 解析数字字符串，非法返回 -1。
	 *
	 * @param s 数字字符串
	 * @return 数值；含非数字字符或为空时返回 -1
	 */
	private static int parseDigits(String s) {
		if (s == null || s.isEmpty() || s.length() > 4) {
			return -1;
		}
		int value = 0;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c < '0' || c > '9') {
				return -1;
			}
			value = value * 10 + (c - '0');
		}
		return value;
	}

	private static String parseAddress(List<PPOcrV6Result> results) {
		// 先用 findLabelBox 找独立"住址"标签；如果 OCR 把"住址"识别成"住址XXX"合并框，
		// 则返回的 labelBox 是合并框，需要从中剥出独立的"住址"标签框（构造虚拟框）。
		PPOcrV6Result labelBox = LabelMatcher.findLabelBox(results, "住址");
		if (labelBox == null) {
			// 兜底：标签完全缺失（small 模型常见），按 score + 地址关键字过滤从所有框拼出地址
			log.warn("身份证解析：未找到标签 \"住址\"，启用无标签兜底");
			return parseAddressWithoutLabel(results);
		}

		// 1. 定位"身份证号码"框（可能与 label 合并），用其区域作为住址的排除区
		PPOcrV6Result idBox = findIdNumberBox(results);
		int idMinX = idBox == null ? Integer.MIN_VALUE : LabelMatcher.minX(idBox);
		int idMaxX = idBox == null ? Integer.MAX_VALUE : LabelMatcher.maxX(idBox);
		int idMinY = idBox == null ? Integer.MIN_VALUE : LabelMatcher.minY(idBox);
		int idMaxY = idBox == null ? Integer.MAX_VALUE : LabelMatcher.maxY(idBox);

		// 2. 检查是否是合并框（"住址"被识别成"住址XXX"）；若是，剥出"住址"文本部分的值（地址第一行）
		String labelText = labelBox.text();
		List<PPOcrV6Result> candidates = new ArrayList<>();
		String firstLineFromMerged = null;
		if (labelText.startsWith("住址") && labelText.length() > 2) {
			// 合并框：第一行地址已含在 labelBox 中，剥前缀得到
			firstLineFromMerged = labelText.substring(2);
		}

		int labelMinX = LabelMatcher.minX(labelBox);
		int labelMinY = LabelMatcher.minY(labelBox);
		int labelMaxY = LabelMatcher.maxY(labelBox);
		int oneLineHeight = Math.max(labelMaxY - labelMinY, 15);

		// 3. 收集候选框：x/y 几何约束 + 区域排斥
		for (PPOcrV6Result r : results) {
			if (r == labelBox) {
				continue;
			}
			String text = r.text();
			if (text.isEmpty()) {
				continue;
			}

			// 跳过含身份证号/姓名/性别/民族/出生等其他已知标签及纯日期/纯英文标签的干扰框
			if (containsAnyLabelOrId(text)) {
				continue;
			}

			int rMinX = LabelMatcher.minX(r);
			int rMaxX = LabelMatcher.maxX(r);
			int rMinY = LabelMatcher.minY(r);
			int rMaxY = LabelMatcher.maxY(r);

			// a. 值框右边缘必须在"住址"标签左边缘之后（允许 10px 倾斜/容差，兼容左下角短文本续行）
			if (rMaxX < labelMinX - 10) {
				continue;
			}
			// b. 值框下边缘不能高于"住址"标签上边缘
			if (rMaxY < labelMinY - 5) {
				continue;
			}
			// c. 区域排斥：候选与号码框在 x/y 两个方向均有"过半"重叠时，才视为号码列噪声剔除。
			//    用重叠比例代替任意相交：倾斜图片中住址续行底边常与号码行顶边轻微擦边
			//    （如 zhy_idcard06 续行 y 底边仅与号码行相交 ~9px），不能因此误杀。
			if (idBox != null) {
				int overlapW = Math.min(rMaxX, idMaxX) - Math.max(rMinX, idMinX);
				int overlapH = Math.min(rMaxY, idMaxY) - Math.max(rMinY, idMinY);
				if (overlapW > 0 && overlapH > 0
					&& overlapW * 2 >= (rMaxX - rMinX)
					&& overlapH * 2 >= (rMaxY - rMinY)) {
					continue;
				}
			}
			// d2. 标准布局下住址续行不会出现在号码框下方：剔除号码框下方超过一行的远处噪声
			//    （如水印残片 "SV"/"SV2"，score 低且位于图片右下角）
			if (idBox != null && rMinY > idMaxY + oneLineHeight) {
				continue;
			}
			// d. 若未识别到号码框下界，限制最多在住址标签下方延伸 4 行（防止远处噪声）
			if (idBox == null && rMinY > labelMaxY + 4 * oneLineHeight) {
				continue;
			}

			candidates.add(r);
		}

		// 4. 二维几何拓扑排序：优先按 y 升序（从上到下），同行（y 差值在 10px 内）按 x 升序（从左到右）
		candidates.sort((r1, r2) -> {
			int yDiff = LabelMatcher.minY(r1) - LabelMatcher.minY(r2);
			if (Math.abs(yDiff) <= 10) {
				return Integer.compare(LabelMatcher.minX(r1), LabelMatcher.minX(r2));
			}
			return Integer.compare(LabelMatcher.minY(r1), LabelMatcher.minY(r2));
		});

		// 5. 组装与清洗
		StringBuilder sb = new StringBuilder();
		if (firstLineFromMerged != null) {
			sb.append(firstLineFromMerged);
		}
		// 按排序顺序拼接后续跨行框
		for (PPOcrV6Result r : candidates) {
			if (sb.length() > 0) {
				sb.append(' ');
			}
			sb.append(r.text());
		}

		// 去掉内部空白（OCR 噪声 + 拼接引入的空格）
		String result = sb.toString().replaceAll("\\s+", "");
		return result.isEmpty() ? null : result;
	}

	/**
	 * 住址无标签兜底：当 OCR 完全没识别出"住址"label（small 模型常见），
	 * 按内容模式（地址关键字 + 置信度阈值）从所有框中筛选并拼出地址。
	 *
	 * <p>这是个<b>有损兜底</b>：短地址（如"山东"）会被"必须含地址关键字"过滤掉，
	 * 实际场景 95%+ 是多字地址，可接受。匹配规则（按顺序）：
	 * <ol>
	 *   <li>置信度 ≥ 0.5（砍掉"降""州"这类 score 0.1~0.4 的 OCR 碎屑）；</li>
	 *   <li>不含已知正面/反面字段关键字（姓名/性别/民族/出生/住址/公民身份号码/签发机关/有效期限）；</li>
	 *   <li>不含日期关键字（年/月/日），排除被误判为地址的日期框；</li>
	 *   <li>含至少一个地址关键字（省/市/县/区/镇/村/乡/旗/盟/州）；</li>
	 *   <li>长度 ≥ 3（砍掉单字噪声）。</li>
	 * </ol>
	 * 按 y 升序、同行按 x 升序拼接；多行用空格连接后用 {@code replaceAll("\\s+", "")} 清洗。
	 */
	private static String parseAddressWithoutLabel(List<PPOcrV6Result> results) {
		List<PPOcrV6Result> candidates = new ArrayList<>();
		for (PPOcrV6Result r : results) {
			String text = r.text();
			if (text == null || text.isEmpty()) {
				continue;
			}
			if (r.score() < 0.5f) {
				continue;
			}
			if (containsAnyLabelOrId(text)) {
				continue;
			}
			if (DATE_KEYWORD_PATTERN.matcher(text).find()) {
				continue;
			}
			if (!ADDR_KEYWORD_PATTERN.matcher(text).find()) {
				continue;
			}
			if (text.length() < 3) {
				continue;
			}
			candidates.add(r);
		}
		if (candidates.isEmpty()) {
			return null;
		}
		// 二维拓扑排序：先 y 升序，同行（y 差 <= 20px）按 x **降序**
		// 关键：ID 卡竖版文字下，第一行地址在右、第二行（缩进）在左，故同 y 时 x 大者优先
		candidates.sort((r1, r2) -> {
			int yDiff = LabelMatcher.minY(r1) - LabelMatcher.minY(r2);
			if (Math.abs(yDiff) <= 20) {
				return Integer.compare(LabelMatcher.minX(r2), LabelMatcher.minX(r1));
			}
			return yDiff;
		});
		StringBuilder sb = new StringBuilder();
		for (PPOcrV6Result r : candidates) {
			if (sb.length() > 0) {
				sb.append(' ');
			}
			sb.append(r.text());
		}
		String result = sb.toString().replaceAll("\\s+", "");
		return result.isEmpty() ? null : result;
	}

	/**
	 * 定位"公民身份号码"或身份证号文本框。
	 *
	 * <p>优先匹配含 18/15 位身份证号的框（最可能是 label + value 合并框或纯号码框），
	 * 未命中再回退到任意含"公民身份号码"/"身份证号"关键字的框。
	 *
	 * @return 号码框；未识别到时返回 null
	 */
	private static PPOcrV6Result findIdNumberBox(List<PPOcrV6Result> results) {
		PPOcrV6Result idPatternBox = null;
		PPOcrV6Result labelOnlyBox = null;
		for (PPOcrV6Result r : results) {
			String text = r.text();
			if (text == null || text.isEmpty()) {
				continue;
			}
			if (ID_NUMBER_18_PATTERN.matcher(text).find() || ID_NUMBER_15_PATTERN.matcher(text).find()) {
				idPatternBox = r;
				break;
			}
			if (labelOnlyBox == null
				&& (text.contains("公民身份号码") || text.contains("身份证号"))) {
				labelOnlyBox = r;
			}
		}
		return idPatternBox != null ? idPatternBox : labelOnlyBox;
	}

	/**
	 * 检查文本是否包含其他已知正面/反面字段关键字或身份证号
	 */
	private static boolean containsAnyLabelOrId(String text) {
		if (text.contains("公民身份号码") || text.contains("身份证号")
			|| text.contains("姓名") || text.contains("性别")
			|| text.contains("民族") || text.contains("出生")
			|| text.contains("签发机关") || text.contains("有效期限")) {
			return true;
		}
		return ID_NUMBER_18_PATTERN.matcher(text).find()
			|| ID_NUMBER_15_PATTERN.matcher(text).find();
	}

	/**
	 * 公民身份号码：标签定位优先，正则 find() 兜底（18 位优先，15 位兜底）。
	 *
	 * <p>标签可能残缺（"公民身份号3625..."）或与号码合并成同一框
	 * （"公民身份号码3625..."），故正则兜底用 find() 从任意文本中提取。
	 * 18 位号码的子序列（前 15 位数字）可能误匹配 15 位正则，故先尝试 18 位。
	 */
	private static ParsedField parseIdNumber(List<PPOcrV6Result> results) {
		LabelMatcher.LabeledMatch labelValue = LabelMatcher.matchValueFromPrefixWithBox(results, "公民身份号码");
		if (labelValue.hasValue() && isValidIdNumber(labelValue.value())) {
			return LabelMatcher.toParsedField(labelValue, FieldSource.LABEL);
		}
		// 兜底：18 位优先（避免误把 18 位的前 15 位识别成 15 位号码）
		LabelMatcher.LabeledMatch fallback = LabelMatcher.matchSubstringWithBox(results, text -> {
			Matcher m = ID_NUMBER_18_PATTERN.matcher(text);
			if (m.find()) {
				return m.group();
			}
			m = ID_NUMBER_15_PATTERN.matcher(text);
			return m.find() ? m.group() : null;
		});
		if (fallback.hasValue()) {
			log.debug("身份证解析：身份证号正则兜底命中 \"{}\"", fallback.value());
			return LabelMatcher.toParsedField(fallback, FieldSource.PATTERN);
		}
		log.warn("身份证解析：未匹配到身份证号");
		return ParsedField.missing();
	}

	/**
	 * 校验文本是否为 15 位或 18 位身份证号。
	 *
	 * @param text 待校验文本
	 * @return true 表示文本完整匹配 15/18 位身份证号
	 */
	private static boolean isValidIdNumber(String text) {
		return text != null
			&& (ID_NUMBER_18_PATTERN.matcher(text).matches()
			|| ID_NUMBER_15_PATTERN.matcher(text).matches());
	}

	/**
	 * 有效期限：按 "有效期限" 标签定位；解析 "YYYY.MM.DD[-YYYY.MM.DD]" 格式。
	 *
	 * @return [validFrom, validTo]，单段时两端相同；解析失败返回 null
	 */
	private static String[] parseValidTerm(List<PPOcrV6Result> results) {
		String labelValue = LabelMatcher.matchValue(results, "有效期限");
		if (labelValue == null) {
			labelValue = LabelMatcher.matchPattern(results, VALID_TERM_PATTERN, false);
		}
		if (labelValue == null) {
			log.warn("身份证解析：未匹配到有效期限");
			return null;
		}
		// "长期"：两端都填 "长期"
		if (labelValue.contains("长期")) {
			return new String[]{"长期", "长期"};
		}
		String[] parts = labelValue.split("-");
		if (parts.length == 1) {
			return new String[]{parts[0], parts[0]};
		}
		return new String[]{parts[0], parts[1]};
	}

	/**
	 * 姓名：标签定位（兼容 "姓名" 与值合并成一框）。
	 */
	private static ParsedField parseName(List<PPOcrV6Result> results) {
		LabelMatcher.LabeledMatch match = LabelMatcher.matchValueFromPrefixWithBox(results, "姓名");
		if (match.hasValue()) {
			return LabelMatcher.toParsedField(match, FieldSource.LABEL);
		}
		return ParsedField.missing();
	}

	/**
	 * 住址：多框拼接，OCR 置信度取首个片段。
	 */
	private static ParsedField parseAddressField(List<PPOcrV6Result> results) {
		String address = parseAddress(results);
		return address == null ? ParsedField.missing() : ParsedField.label(address, null);
	}

	@Override
	public IdCardResult parseResults(List<PPOcrV6Result> results) {
		IdCardSide side = detectSide(results);
		IdCardResult r = new IdCardResult();
		r.setRawResults(new ArrayList<>(results));
		r.setSide(side);
		if (side == IdCardSide.FRONT) {
			ParsedField name = parseName(results);
			ParsedField idNumber = parseIdNumber(results);
			ParsedField gender = parseGender(results);
			// 性别回填：OCR 把性别值糊掉时，身份证号的性别位（倒数第二位）能救回来
			if (!gender.hasValue() && idNumber.hasValue()) {
				String backfilled = Validations.genderFromIdNumber(idNumber.value());
				if (backfilled != null) {
					log.debug("身份证解析：性别由身份证号回填为 \"{}\"", backfilled);
					gender = ParsedField.backfill(backfilled);
				}
			}
			ParsedField nation = parseNation(results);
			ParsedField birthDate = parseBirthDate(results, idNumber.value());
			ParsedField address = parseAddressField(results);

			r.setName(name.value());
			r.setGender(gender.value());
			r.setNation(nation.value());
			r.setIdNumber(idNumber.value());
			r.setBirthDate(birthDate.value());
			r.setAddress(address.value());

			fillFrontConfidences(r, name, gender, nation, birthDate, address, idNumber);
		} else if (side == IdCardSide.BACK) {
			LabelMatcher.LabeledMatch authority = LabelMatcher.matchValueFromPrefixWithBox(results, "签发机关");
			r.setIssuingAuthority(authority.value());
			r.setValidFrom(null);
			r.setValidTo(null);
			String[] term = parseValidTerm(results);
			if (term != null) {
				r.setValidFrom(term[0]);
				r.setValidTo(term[1]);
				r.putFieldConfidence(FieldConfidence.of("validFrom",
					ParsedField.label(term[0], null), null));
				r.putFieldConfidence(FieldConfidence.of("validTo",
					ParsedField.label(term[1], null), null));
			}
			r.putFieldConfidence(FieldConfidence.of("issuingAuthority",
				LabelMatcher.toParsedField(authority, FieldSource.LABEL), null));
		}
		return r;
	}

	/**
	 * 填充正面字段的置信度信息。
	 *
	 * <p><b>正确性层的核心动作</b>：用 GB 11643 校验位给身份证号做一次"数学自洽"检查 ——
	 * 格式正则只能看出"长得像不像"，校验位能看出"至少认错了一个字符"。
	 * 校验不通过时，该字段置信度<b>直接归零</b>（而不是打折），并把整条结果标记为需复核。
	 *
	 * @param r          解析结果
	 * @param name       姓名
	 * @param gender     性别
	 * @param nation     民族
	 * @param birthDate  出生日期
	 * @param address    住址
	 * @param idNumber   公民身份号码
	 */
	private static void fillFrontConfidences(IdCardResult r, ParsedField name, ParsedField gender,
											ParsedField nation, ParsedField birthDate,
											ParsedField address, ParsedField idNumber) {
		ValidationResult idValidation = Validations.validateIdCard(idNumber.value());
		if (idValidation.mismatched()) {
			log.warn("身份证解析：{}", idValidation.message());
		}
		r.putFieldConfidence(FieldConfidence.of("idNumber", idNumber, idValidation));
		r.putFieldConfidence(FieldConfidence.of("name", name, null));
		r.putFieldConfidence(FieldConfidence.of("gender", gender, null));
		r.putFieldConfidence(FieldConfidence.of("nation", nation, null));
		r.putFieldConfidence(FieldConfidence.of("birthDate", birthDate, null));
		r.putFieldConfidence(FieldConfidence.of("address", address, null));

		// 正面这 6 个字段都应存在，缺失即需复核
		markMissing(r, "name", name);
		markMissing(r, "gender", gender);
		markMissing(r, "nation", nation);
		markMissing(r, "birthDate", birthDate);
		markMissing(r, "address", address);
		markMissing(r, "idNumber", idNumber);
	}

	/**
	 * 字段缺失时标记需复核（正面版式上该字段本应存在）。
	 *
	 * @param r      解析结果
	 * @param field  字段名
	 * @param parsed 字段解析结果
	 */
	private static void markMissing(IdCardResult r, String field, ParsedField parsed) {
		if (!parsed.hasValue()) {
			r.markNeedsReview("正面应有 " + field + " 字段，但未识别到");
		}
	}
}
