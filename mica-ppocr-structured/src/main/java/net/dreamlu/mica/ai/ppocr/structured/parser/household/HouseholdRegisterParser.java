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

package net.dreamlu.mica.ai.ppocr.structured.parser.household;

import lombok.extern.slf4j.Slf4j;
import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Engine;
import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Result;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.BaseStructuredParser;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.LabelMatcher;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.LabelMatcher.LabeledMatch;
import net.dreamlu.mica.ai.ppocr.utils.CollUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 户口本（常住人口登记卡）OCR 结构化解析器。
 *
 * <p>策略概览：
 * <ul>
 *   <li><b>标准字段</b>：姓名 / 性别 / 出生地 / 籍贯 / 文化程度 / 服务处所 /
 *       职业 / 身高 / 血型 —— 走{@link LabelMatcher#matchValueWithBox} 的"标签 + 右侧 y 重叠"策略；
 *       OCR 把标签切碎成单字（"姓 名"）时，{@code findLabelBox} 的 fragment 兜底可命中。</li>
 *   <li><b>正则兜底字段</b>：户号 / 公民身份号码 / 出生日期 / 登记日期 —— 标签定位失败时
 *       走正则兜底（户号 7~12 位数字 / 身份证号 18 位 / 日期 yyyy 年 MM 月 dd 日）。</li>
 *   <li><b>多行标签字段</b>：与户主关系 / 何时由何地迁来本址 / 何时由何地迁来本市(县) ——
 *       标签可能被 OCR 切成 2~3 行，用{@link LabelMatcher#matchValueByLabelKeywordWithBox}
 *       按关键字定位（"与户主关系" 用 ["户主", "与户主", "关系"] 等）。</li>
 *   <li><b>合并框清洗</b>：公民身份号码 / 身高 等标签与值被识别成同一框（"110889200111284922身 高"），
 *       走正则 find() 兜底；登记日期合并框（"2003年11月日"）走日期正则切值。</li>
 *   <li><b>跨框合并</b>：OCR 把日期切成 2 框（"1961" + "10月21"），或
 *       登记日期（"2019年11月11" + "日"），或民族（"汉" + "族"） —— 走"同 y 连续右框"合并策略。</li>
 * </ul>
 */
@Slf4j
public class HouseholdRegisterParser extends BaseStructuredParser<HouseholdRegisterResult> {

	/**
	 * 18 位身份证号（末位 X 支持）。
	 */
	private static final Pattern ID_NUMBER_18_PATTERN = Pattern.compile("[0-9]{17}[0-9Xx]");

	/**
	 * 出生日期：yyyy 年 MM 月 dd 日（容忍空格）。
	 */
	private static final Pattern DATE_PATTERN = Pattern.compile(
		"\\d{4}\\s*年\\s*\\d{1,2}\\s*月\\s*\\d{1,2}\\s*日?");

	/**
	 * 更宽松的日期形式（"yyyy-月-dd" / "yyyy.MM.dd" / "yyyy/MM/dd" / "yyyy MM dd" / "yyyy年MM月dd日"）。
	 *
	 * <p>关键约束：每个分隔符（年/月/日之间）必须是 1 个字符（年/./-/// 空格）。
	 * 避免 "2003年11月日" → "2003年11"（漏掉"月"）的错误匹配。
	 */
	private static final Pattern REG_DATE_LOOSE_PATTERN = Pattern.compile(
		"\\d{4}[\\s年.\\-/]\\d{1,2}[\\s月.\\-/]\\d{1,2}\\s*日?");

	/**
	 * 部分日期（"yyyy-MM" / "yyyy年MM月" 无日）。
	 */
	private static final Pattern REG_DATE_PARTIAL_PATTERN = Pattern.compile(
		"\\d{4}[\\s年.\\-/]\\d{1,2}\\s*月?");

	/**
	 * 身高：数字 + 可选"厘米"（3 位身高 + 厘米）。
	 */
	private static final Pattern HEIGHT_PATTERN = Pattern.compile("(\\d{2,3})\\s*(厘米|cm|CM)?");

	/**
	 * 民族关键字：含"族"或为"汉"。
	 */
	private static final Pattern NATION_PATTERN = Pattern.compile("[\\u4e00-\\u9fa5]{1,4}族|^汉$");

	/**
	 * 与户主关系关键字。
	 */
	private static final Set<String> RELATIONSHIP_KEYWORDS = CollUtil.setOf(
		"户主", "独生子", "独生女", "夫", "妻", "子", "女",
		"父", "母", "兄", "弟", "姐", "妹", "孙", "外孙", "其他");

	/**
	 * 户号 label 关键字（容忍"户 号"）。
	 */
	private static final List<String> HOUSEHOLD_NO_LABEL_KEYWORDS = CollUtil.listOf("户号", "户 号", "户号：");

	/**
	 * "与户主关系" label fragment 关键字。
	 */
	private static final List<String> RELATIONSHIP_LABEL_KEYWORDS = CollUtil.listOf(
		"与户主关系", "与 户 主 关 系", "户主或与户主关系", "与户主", "户主关系");

	/**
	 * "何时由何地迁来本市(县)" label fragment 关键字。
	 */
	private static final List<String> MOVE_TO_CITY_LABEL_KEYWORDS = CollUtil.listOf(
		"迁来本", "迁来本市", "迁来本市(县)", "何时由何地迁来本市", "迁来本市（县）");

	/**
	 * "何时由何地迁往本址" label fragment 关键字。
	 */
	private static final List<String> MOVE_TO_ADDRESS_LABEL_KEYWORDS = CollUtil.listOf(
		"迁来本址", "何时由何地迁往本址", "何时由何地迁来本址", "迁来本", "迁往本址");

	/**
	 * 单字中文 label fragment 集合（"无"是合法 value，不在此列）。
	 *
	 * <p>提为类常量：{@link #isLabelFragment(String, boolean)} 在逐框热路径上被反复调用，
	 * 放在方法体内会每次重新分配数组字面量。
	 */
	private static final Set<String> SINGLE_CHAR_LABEL_FRAGMENTS = CollUtil.setOf(
		"姓", "名", "性", "别", "民", "族", "籍", "贯", "份", "职", "业",
		"浙", "所", "血", "型", "婚", "姻", "状", "况", "兵", "役",
		"宗", "教", "信", "仰", "曾", "用", "住", "址", "签", "章", "登",
		"记", "本", "市", "县", "户", "号", "出", "生", "身", "高", "公",
		"文", "化", "程", "度", "承", "办");

	/**
	 * 标签 / 字段关键字集合（节选）。
	 *
	 * <p>提为类常量，原因同 {@link #SINGLE_CHAR_LABEL_FRAGMENTS}。
	 */
	private static final Set<String> LABEL_FRAGMENTS = CollUtil.setOf(
		"姓", "姓 名", "名", "性别", "性 别", "性", "别", "出生地", "出 生 地",
		"民族", "民", "族", "籍贯", "籍", "贯", "出生日期", "出 生 日 期", "出生", "出 生",
		"公民身份", "公民身", "份", "证件编号", "身高", "身 高", "血型", "血 型",
		"文化程度", "文 化 程 度", "婚姻状况", "婚 姻 状 况", "兵役状况", "兵 役 状 况",
		"服务处所", "服 务 处 所", "职业", "职", "业", "何时由何地", "迁来本",
		"迁来本市", "迁来本市(县)", "迁来本市（县）", "何时由何地迁来本",
		"何时由何地迁来本市", "何时由何地迁来本址", "何时由何地迁往本址",
		"承办人签章", "承 办 人 签 章", "签章", "登记日期", "登 记 日 期", "登记",
		"户号", "户 号", "本市", "本市(县)", "本市（县）", "其他住址", "其 他 住 址",
		"宗教信仰", "宗 教 信 仰", "曾用名", "曾 用 名", "与户主关系");

	/**
	 * "承办人签章" label fragment 关键字。
	 */
	private static final List<String> ISSUER_LABEL_KEYWORDS = CollUtil.listOf("承办人签章", "承 办 人 签 章", "签章", "承办人");

	/**
	 * "登记日期" label fragment 关键字。
	 */
	private static final List<String> REG_DATE_LABEL_KEYWORDS = CollUtil.listOf("登记日期", "登 记 日 期", "登记");

	/**
	 * "公民身份证件编号" label 关键字（兼容"公民身份 证 件 编号"分块）。
	 */
	private static final List<String> ID_NUMBER_LABEL_KEYWORDS = CollUtil.listOf(
		"公民身份号码", "公民身份证件编号", "公 民 身 份 证 件 编 号", "公民身份", "身份证号", "证件编号");

	// ==================================================================
	// 构造
	// ==================================================================

	/**
	 * 构造户籍登记簿解析器，注入推理引擎。
	 *
	 * @param engine PP-OCRv6 推理引擎
	 */
	public HouseholdRegisterParser(PPOcrV6Engine engine) {
		super(engine);
	}

	// ==================================================================
	// 入口
	// ==================================================================

	private static void apply(HouseholdRegisterResult r, String name, LabeledMatch match) {
		if (match == null) {
			return;
		}
		String value = match.value();
		if (value == null) {
			LabelMatcher.applyFieldBox(r, name, match);
			return;
		}
		String trimmed = value.trim();
		if (trimmed.isEmpty()) {
			LabelMatcher.applyFieldBox(r, name, match);
			return;
		}
		switch (name) {
			case "householdNo":
				r.setHouseholdNo(trimmed);
				break;
			case "name":
				r.setName(trimmed);
				break;
			case "relationship":
				r.setRelationship(trimmed);
				break;
			case "gender":
				r.setGender(trimmed);
				break;
			case "birthPlace":
				r.setBirthPlace(trimmed);
				break;
			case "ethnicity":
				r.setEthnicity(trimmed);
				break;
			case "nativePlace":
				r.setNativePlace(trimmed);
				break;
			case "birthDate":
				r.setBirthDate(trimmed);
				break;
			case "idNumber":
				r.setIdNumber(trimmed);
				break;
			case "height":
				r.setHeight(trimmed);
				break;
			case "education":
				r.setEducation(trimmed);
				break;
			case "workplace":
				r.setWorkplace(trimmed);
				break;
			case "moveToCityDate":
				r.setMoveToCityDate(trimmed);
				break;
			case "moveToAddress":
				r.setMoveToAddress(trimmed);
				break;
			case "registrationDate":
				r.setRegistrationDate(trimmed);
				break;
			default: {
				/* no-op */
				break;
			}
		}
		LabelMatcher.applyFieldBox(r, name, match);
	}

	// ==================================================================
	// apply：通用回填
	// ==================================================================

	/**
	 * 户号：标签定位（合并框） + 7~12 位数字兜底。
	 *
	 * <p>兜底策略：找"全是 7~12 位数字"的"纯数字框"，避免误把 18 位身份证的前 12 位当作户号。
	 * 排除：
	 * <ul>
	 *   <li>整框就是 18 位身份证号（{@link #ID_NUMBER_18_PATTERN} matches）</li>
	 *   <li>整框含 18 位身份证片段（合并框 "310128198508253218身高"）</li>
	 *   <li>数字总长度 < 7 或 > 12</li>
	 * </ul>
	 */
	private static LabeledMatch parseHouseholdNo(List<PPOcrV6Result> results) {
		for (String kw : HOUSEHOLD_NO_LABEL_KEYWORDS) {
			LabeledMatch m = LabelMatcher.matchValueFromPrefixWithBox(results, kw);
			if (m.hasValue()) {
				String cleaned = cleanHouseholdNo(m.value());
				if (cleaned != null) {
					log.debug("户口本解析：户号 label \"{}\" 命中，剥前缀得 \"{}\"", kw, cleaned);
					return LabeledMatch.of(cleaned, m.matches());
				}
			}
		}
		// 兜底：扫所有框找"纯数字 7~12 位"的框（取 minY 最小，即最顶）
		PPOcrV6Result best = null;
		String bestVal = null;
		int bestY = Integer.MAX_VALUE;
		for (PPOcrV6Result r : results) {
			String text = r.text();
			if (text == null || text.isEmpty()) continue;
			// 整框文本去掉所有非数字字符
			String digitsOnly = text.replaceAll("[^0-9]", "");
			if (digitsOnly.isEmpty()) continue;
			// 整框就是 18 位身份证 → 跳过
			if (ID_NUMBER_18_PATTERN.matcher(digitsOnly).matches()) continue;
			// 整框数字总长度不在 7~12 之间 → 跳过（防止 18 位身份证或长数字被截断）
			if (digitsOnly.length() < 7 || digitsOnly.length() > 12) continue;
			// 整框文本中不能含 18 位身份证片段（合并框 "310128198508253218身高"）
			if (ID_NUMBER_18_PATTERN.matcher(text).find()) continue;
			// 整框文本必须以纯数字为主（去掉非数字后剩余部分应与原文本去掉空白后相等）
			String stripped = text.replaceAll("\\s+", "");
			if (!stripped.equals(digitsOnly)) continue;
			int y = LabelMatcher.minY(r);
			if (y < bestY) {
				bestY = y;
				best = r;
				bestVal = digitsOnly;
			}
		}
		if (best != null) {
			log.debug("户口本解析：户号 正则兜底命中 \"{}\"", bestVal);
			return LabeledMatch.of(bestVal, best);
		}
		log.warn("户口本解析：未匹配到户号");
		return LabeledMatch.textOnly(null);
	}

	// ==================================================================
	// 户号
	// ==================================================================

	private static String cleanHouseholdNo(String raw) {
		if (raw == null) return null;
		// 去掉冒号、空格、中文括号等
		String stripped = raw.replaceAll("[^0-9]", "");
		if (stripped.length() < 7 || stripped.length() > 12) {
			return null;
		}
		return stripped;
	}

	/**
	 * 姓名：label 定位 + 跨框合并（OCR 切碎"姓" / "名"）。
	 *
	 * <p>姓名有两类合并场景：
	 * <ol>
	 *   <li>"姓" + "名" 切成 2 框；
	 *   <li>"姓" + "名" + "王燕" 切成 3 框（label 后还跟 1 个 fragment）—— 取"最右"第一个非 label 框。
	 * </ol>
	 */
	private static LabeledMatch parseName(List<PPOcrV6Result> results) {
		LabeledMatch basic = matchValueWithBoxWithSpaces(results, "姓名");
		if (basic.hasValue()) {
			return mergeNameSiblings(results, basic);
		}
		// 兜底：找"姓"/"名" label 框，右侧 y 重叠 + x 最近（最左）
		List<PPOcrV6Result> labelCandidates = new ArrayList<>();
		for (PPOcrV6Result r : results) {
			String text = r.text();
			if (text == null || text.isEmpty()) continue;
			if (text.equals("姓") || text.equals("姓 名") || text.equals("名")) {
				labelCandidates.add(r);
			}
		}
		if (labelCandidates.isEmpty()) {
			return LabeledMatch.textOnly(null);
		}
		// 取最左的"姓"框
		PPOcrV6Result labelBox = labelCandidates.stream()
			.min(Comparator.comparingInt(LabelMatcher::minX))
			.orElse(null);
		if (labelBox == null) return LabeledMatch.textOnly(null);
		LabeledMatch m = matchValueToRightOfLabel(results, labelBox, "姓");
		if (m.hasValue()) {
			return mergeNameSiblings(results, m);
		}
		return LabeledMatch.textOnly(null);
	}

	// ==================================================================
	// 姓名
	// ==================================================================

	/**
	 * 姓名合并：若值框是单字（如"名"），向右紧邻的"未识别名"框合并；否则保留原值。
	 */
	private static LabeledMatch mergeNameSiblings(List<PPOcrV6Result> results, LabeledMatch m) {
		if (!m.hasValue()) return m;
		List<PPOcrV6Result> boxes = new ArrayList<>(m.matches());
		String value = m.value();
		// 主值已是 2+ 字"完整姓名" → 直接返回，不再合并
		if (value.length() >= 2) {
			return m;
		}
		PPOcrV6Result main = boxes.get(0);
		if (main == null) return m;
		int mainMaxX = LabelMatcher.maxX(main);
		int mainCY = (LabelMatcher.minY(main) + LabelMatcher.maxY(main)) / 2;
		// 合并 1 个右侧紧邻框
		PPOcrV6Result next = null;
		int nextScore = Integer.MAX_VALUE;
		for (PPOcrV6Result r : results) {
			if (boxes.contains(r)) continue;
			String text = r.text();
			if (text == null || text.isEmpty()) continue;
			int x0 = LabelMatcher.minX(r);
			int rCY = (LabelMatcher.minY(r) + LabelMatcher.maxY(r)) / 2;
			// y 中心距离不超过 30px（同行）
			if (Math.abs(rCY - mainCY) > 30) continue;
			int dx = x0 - mainMaxX;
			if (dx < 0 || dx > 80) continue;
			// 只能合并 ≤ 3 字（人名长度）
			if (text.length() > 3) continue;
			int dy = Math.abs(rCY - mainCY);
			int score = dx * 10 + dy;
			if (score < nextScore) {
				nextScore = score;
				next = r;
			}
		}
		if (next == null) return m;
		boxes.add(next);
		return LabeledMatch.of(value + next.text(), boxes);
	}

	/**
	 * 性别：label 定位 + 单字"男"/"女" 校验。
	 *
	 * <p>OCR 偶尔将"性别"切碎成"性" + "别女"（合并框），path：
	 * 1) 先尝试"性别"label；2) 失败再试"性 别" / "性别"；3) 兜底扫所有"X / X男 / X女" 框。
	 */
	private static LabeledMatch parseGender(List<PPOcrV6Result> results) {
		LabeledMatch m = matchValueWithBoxWithSpaces(results, "性别");
		if (m.hasValue()) {
			String cleaned = m.value().replaceAll("\\s+", "");
			if (cleaned.contains("男") || cleaned.contains("女")) {
				return LabeledMatch.of(cleaned.contains("男") ? "男" : "女", m.matches());
			}
		}
		// 兜底：扫所有框找"男" / "女" 单字（位于图像上部 1/3 区域，避免误命中"民族"）
		PPOcrV6Result best = null;
		int bestY = Integer.MAX_VALUE;
		for (PPOcrV6Result r : results) {
			String text = r.text();
			if (text == null || text.isEmpty()) continue;
			if ("男".equals(text) || "女".equals(text)) {
				int y = LabelMatcher.minY(r);
				if (y < bestY) {
					bestY = y;
					best = r;
				}
			}
		}
		if (best != null) {
			log.debug("户口本解析：性别 位置兜底命中 \"{}\"", best.text());
			return LabeledMatch.of(best.text(), best);
		}
		return LabeledMatch.textOnly(null);
	}

	// ==================================================================
	// 性别
	// ==================================================================

	/**
	 * 与户主关系：fragment 关键字定位 + 关系词白名单校验。
	 *
	 * <p>label 已定位但右侧无值时直接返回 null（不兜底扫所有框），
	 * 避免把 gender 行的"女"误当作 relationship 值。
	 *
	 * <p>label 框为下片 fragment（"户主关系"）时使用 strictY=true 严格 y 中心匹配，
	 * 避免把上方行的"女"/"男"（OCR 误识别为 relationship 位置）当作 relationship 值。
	 */
	private static LabeledMatch parseRelationship(List<PPOcrV6Result> results) {
		// 1) 标签变体（多关键字匹配）：按"label 框在右列顶部"原则精确定位
		PPOcrV6Result labelBox = findRelationshipLabelBox(results);
		if (labelBox != null) {
			LabeledMatch m = matchValueToRightOfLabel(results, labelBox, "与户主关系", true);
			if (m.hasValue()) {
				return cleanRelationship(m);
			}
			// label 已定位但右侧无值 → relationship 实际为空，不兜底
			log.debug("户口本解析：与户主关系 标签已定位但右侧无值");
			return LabeledMatch.textOnly(null);
		}
		// 2) 兜底：label 未找到时，扫所有框找含关系词的框（取 minY 最小）
		PPOcrV6Result best = null;
		int bestY = Integer.MAX_VALUE;
		for (PPOcrV6Result r : results) {
			String text = r.text();
			if (text == null || text.isEmpty()) continue;
			if (isRelationshipWord(trimAll(text))) {
				int y = LabelMatcher.minY(r);
				if (y < bestY) {
					bestY = y;
					best = r;
				}
			}
		}
		if (best != null) {
			log.debug("户口本解析：与户主关系 关系词位置兜底命中 \"{}\"", best.text());
			return LabeledMatch.of(trimAll(best.text()), best);
		}
		log.warn("户口本解析：未匹配到与户主关系");
		return LabeledMatch.textOnly(null);
	}

	// ==================================================================
	// 与户主关系
	// ==================================================================

	/**
	 * 定位"与户主关系"标签框。
	 *
	 * <p>OCR 切碎场景："户主或与" + "户主关系" 切成 2 框。
	 * 取最右的 fragment（label 框在右列顶部，value 在 label 右侧）。
	 */
	private static PPOcrV6Result findRelationshipLabelBox(List<PPOcrV6Result> results) {
		// 1) 完整"与户主关系"框
		for (PPOcrV6Result r : results) {
			if ("与户主关系".equals(r.text())) {
				return r;
			}
		}
		// 2) "户主或与" + "户主关系" 双框：取最右的（"户主关系"）
		PPOcrV6Result candidate = null;
		for (PPOcrV6Result r : results) {
			String text = r.text();
			if (text == null) continue;
			if (text.equals("户主或与") || text.equals("户主关系") || text.equals("与户主")) {
				if (candidate == null || LabelMatcher.minX(r) > LabelMatcher.minX(candidate)) {
					candidate = r;
				}
			}
		}
		return candidate;
	}

	/**
	 * 出生地：label 定位 + label 缺失时按民族行兜底。
	 *
	 * <p>OCR 偶尔漏识别"出生地"标签（如 household_register5），
	 * 此时用民族 label 的 y 范围作锚点，在左列（x &lt; 700）找
	 * 省/市/县结尾的地名文本作为出生地。
	 */
	private static LabeledMatch parseBirthPlace(List<PPOcrV6Result> results) {
		// 1) 标签定位
		LabeledMatch m = matchValueWithBoxWithSpaces(results, "出生地");
		if (m.hasValue()) {
			return m;
		}
		// 2) 兜底：label 缺失时，按民族 label 的 y 范围在左列找省/市/县
		PPOcrV6Result ethnicityLabel = findEthnicityLabelBox(results);
		if (ethnicityLabel != null) {
			int yMin = LabelMatcher.minY(ethnicityLabel);
			int yMax = LabelMatcher.maxY(ethnicityLabel);
			PPOcrV6Result best = null;
			int bestScore = Integer.MAX_VALUE;
			for (PPOcrV6Result r : results) {
				String text = r.text();
				if (text == null || text.isEmpty()) continue;
				if (isLabelFragment(text)) continue;
				int x0 = LabelMatcher.minX(r);
				if (x0 > 700) continue; // 左列
				int rYMin = LabelMatcher.minY(r);
				int rYMax = LabelMatcher.maxY(r);
				// y 与民族 label 行重叠
				if (rYMax < yMin || rYMin > yMax) continue;
				// 必须是省/市/县结尾的地名
				if (!text.matches(".+[省市县]$")) continue;
				int score = Math.abs((rYMin + rYMax) / 2 - (yMin + yMax) / 2);
				if (score < bestScore) {
					bestScore = score;
					best = r;
				}
			}
			if (best != null) {
				log.debug("户口本解析：出生地 label 缺失，按民族行兜底命中 \"{}\"", best.text());
				return LabeledMatch.of(best.text(), best);
			}
		}
		return LabeledMatch.textOnly(null);
	}

	// ==================================================================
	// 出生地
	// ==================================================================

	/**
	 * 民族：定位"民族"标签 → 跨框合并（"汉" + "族" 切碎）→ 民族正则兜底。
	 */
	private static LabeledMatch parseEthnicity(List<PPOcrV6Result> results) {
		// 1) 找"民族"标签框（多种变体）
		PPOcrV6Result labelBox = findEthnicityLabelBox(results);
		if (labelBox != null) {
			// 取右侧 y 重叠的 1~2 个候选值框（排除"族" label fragment）
			LabeledMatch m = matchValueToRightOfLabel(results, labelBox, "民族", "族", "民族");
			if (m.hasValue()) {
				// 清洗：值若以"族"开头（合并框"族汉族"），剥掉前导"族"
				String cleaned = m.value();
				if (cleaned.startsWith("族") && cleaned.length() > 1) {
					cleaned = cleaned.substring(1);
				}
				// 跨框合并：值"汉"或"族"时找另一半（跳过 labelBox 自身）
				LabeledMatch merged = mergeEthnicitySiblings(results, LabeledMatch.of(cleaned, m.matches()), labelBox);
				if (NATION_PATTERN.matcher(merged.value()).matches()) {
					return merged;
				}
				return merged;
			}
		}
		// 2) 兜底：扫所有框找"X族"或单字"汉"
		for (PPOcrV6Result r : results) {
			String text = r.text();
			if (text == null || text.isEmpty()) continue;
			String stripped = text;
			if (stripped.startsWith("族") && stripped.length() > 1) {
				stripped = stripped.substring(1);
			}
			if (NATION_PATTERN.matcher(stripped).matches()) {
				return LabeledMatch.of(stripped, r);
			}
		}
		return LabeledMatch.textOnly(null);
	}

	// ==================================================================
	// 民族
	// ==================================================================

	/**
	 * 定位"民族"标签框。
	 *
	 * <p>OCR 切碎场景 1（左右）："民"（左） + "族"（中） + "汉族"（右）。
	 * <p>OCR 切碎场景 2（上下）："民"（上） + "族"（下） + "汉"（右）。
	 *
	 * <p>策略：扫描所有"民"/"族"fragment，按 y 降序 + x 升序选最底且最左的 fragment
	 * 作为 label 锚点（这样 y 更接近右值，匹配更稳）。
	 */
	private static PPOcrV6Result findEthnicityLabelBox(List<PPOcrV6Result> results) {
		// 1) 完整"民族"框
		PPOcrV6Result exact = LabelMatcher.findLabelBox(results, "民族");
		if (exact != null && exact.text().equals("民族")) {
			return exact;
		}
		// 2) "民" + "族" fragment：选 maxY + minX（最底+最左）的 fragment
		PPOcrV6Result best = null;
		int bestY = Integer.MIN_VALUE;
		int bestX = Integer.MAX_VALUE;
		for (PPOcrV6Result r : results) {
			String text = r.text();
			if (text == null) continue;
			if (text.equals("民") || text.equals("族")
				|| text.equals("民 族") || text.equals("民族")) {
				int y = LabelMatcher.maxY(r);
				int x = LabelMatcher.minX(r);
				if (y > bestY || (y == bestY && x < bestX)) {
					bestY = y;
					bestX = x;
					best = r;
				}
			}
		}
		return best;
	}

	/**
	 * 民族合并：值"汉"或"族"时，找相邻 y 重叠的"另一半"（左右都可）。
	 *
	 * <p>顺序固定为"X族"，"族" 永远后置。
	 */
	private static LabeledMatch mergeEthnicitySiblings(List<PPOcrV6Result> results, LabeledMatch m, PPOcrV6Result labelBox) {
		if (!m.hasValue() || m.matches().isEmpty()) return m;
		String value = m.value();
		if (value.contains("族") && value.length() >= 2) {
			return m; // 已经是完整"X族"
		}
		PPOcrV6Result main = m.matches().get(0);
		int mainMinX = LabelMatcher.minX(main);
		int mainMaxX = LabelMatcher.maxX(main);
		int mainCY = (LabelMatcher.minY(main) + LabelMatcher.maxY(main)) / 2;
		PPOcrV6Result sibling = null;
		String siblingText = null;
		int bestDx = Integer.MAX_VALUE;
		for (PPOcrV6Result r : results) {
			if (r == main) continue;
			String text = r.text();
			if (text == null || text.isEmpty()) continue;
			if (text.equals(main.text())) continue;
			int x0 = LabelMatcher.minX(r);
			int rCY = (LabelMatcher.minY(r) + LabelMatcher.maxY(r)) / 2;
			if (Math.abs(rCY - mainCY) > 25) continue;
			// 1~2 字（"族"/"汉" 等）
			if (text.length() > 2) continue;
			// 候选 sibling 必须在 main 横向相邻（300px 内，可左可右）
			int dx;
			if (x0 > mainMaxX) {
				dx = x0 - mainMaxX;
			} else if (LabelMatcher.maxX(r) < mainMinX) {
				dx = mainMinX - LabelMatcher.maxX(r);
			} else {
				continue; // 重叠
			}
			if (dx > 300) continue;
			if (dx < bestDx) {
				bestDx = dx;
				sibling = r;
				siblingText = text;
			}
		}
		if (sibling == null) return m;
		// 拼接：民族顺序固定为"X族"（如"汉族"），"族" 后置
		String merged;
		List<PPOcrV6Result> mergedBoxes;
		if (siblingText.equals("族")) {
			merged = value + "族";
			mergedBoxes = new ArrayList<>(CollUtil.listOf(main, sibling));
		} else if (value.equals("族")) {
			merged = siblingText + "族";
			mergedBoxes = new ArrayList<>(CollUtil.listOf(sibling, main));
		} else {
			// 其他情况按位置拼接
			if (LabelMatcher.minX(sibling) < mainMinX) {
				merged = siblingText + value;
				mergedBoxes = new ArrayList<>(CollUtil.listOf(sibling, main));
			} else {
				merged = value + siblingText;
				mergedBoxes = new ArrayList<>(CollUtil.listOf(main, sibling));
			}
		}
		return LabeledMatch.of(merged, mergedBoxes);
	}

	/**
	 * 出生日期：label 定位 + 跨框合并（"1961" + "10月21"）。
	 */
	private static LabeledMatch parseBirthDate(List<PPOcrV6Result> results) {
		// 1) 标签优先
		for (String label : CollUtil.listOf("出生日期", "出生", "出 生 日 期", "出生年月")) {
			LabeledMatch m = matchValueWithBoxWithSpaces(results, label);
			if (m.hasValue()) {
				// 完整日期正则
				String date = extractDate(m.value());
				if (date != null) {
					return LabeledMatch.of(date, m.matches());
				}
				// 跨框合并：值框为"1961"（或"年份"），右侧 y 重叠追加"10月21"等
				LabeledMatch merged = mergeDateSiblings(results, m);
				date = extractDate(merged.value());
				if (date != null) {
					return LabeledMatch.of(date, merged.matches());
				}
				// 宽松正则
				Matcher loose = REG_DATE_LOOSE_PATTERN.matcher(merged.value());
				if (loose.find()) {
					String hit = loose.group();
					if (!hit.endsWith("日")) hit = hit + "日";
					return LabeledMatch.of(hit, merged.matches());
				}
				log.debug("户口本解析：出生日期 label \"{}\" 命中 \"{}\" 未含日期格式", label, m.value());
			}
		}
		// 2) 兜底：扫所有框找日期（宽松正则）
		LabeledMatch fb = LabelMatcher.matchSubstringWithBox(results, text -> {
			String stripped = text.replaceAll("\\s+", "");
			if (ID_NUMBER_18_PATTERN.matcher(stripped).matches()) return null;
			Matcher m = REG_DATE_LOOSE_PATTERN.matcher(text);
			if (m.find()) {
				String hit = m.group();
				return hit.endsWith("日") ? hit : hit + "日";
			}
			m = DATE_PATTERN.matcher(text);
			return m.find() ? m.group() : null;
		});
		if (fb.hasValue()) {
			log.debug("户口本解析：出生日期 正则兜底命中 \"{}\"", fb.value());
			return fb;
		}
		log.warn("户口本解析：未匹配到出生日期");
		return LabeledMatch.textOnly(null);
	}

	// ==================================================================
	// 出生日期
	// ==================================================================

	/**
	 * 日期合并：值框"1961" + 右侧相邻"10月21" → "1961年10月21日"。
	 */
	private static LabeledMatch mergeDateSiblings(List<PPOcrV6Result> results, LabeledMatch m) {
		if (!m.hasValue() || m.matches().isEmpty()) return m;
		PPOcrV6Result main = m.matches().get(0);
		String value = m.value();
		// 已经含"年/月"且 match() 有 1 个框 → 不用合并
		if (value.contains("年") && value.contains("月")) {
			if (value.contains("日")) return m;
		}
		int mainMaxX = LabelMatcher.maxX(main);
		int mainCY = (LabelMatcher.minY(main) + LabelMatcher.maxY(main)) / 2;
		int mainH = LabelMatcher.maxY(main) - LabelMatcher.minY(main);
		List<PPOcrV6Result> boxes = new ArrayList<>(m.matches());
		while (true) {
			PPOcrV6Result next = null;
			int nextScore = Integer.MAX_VALUE;
			for (PPOcrV6Result r : results) {
				if (boxes.contains(r)) continue;
				String text = r.text();
				if (text == null || text.isEmpty()) continue;
				int x0 = LabelMatcher.minX(r);
				int rCY = (LabelMatcher.minY(r) + LabelMatcher.maxY(r)) / 2;
				if (Math.abs(rCY - mainCY) > mainH) continue;
				int dx = x0 - mainMaxX;
				// 容忍 dx 略为负值（最多 30px 横向重叠）：
				// OCR 切日期时可能把"2019 / 年 / 11 / 月 / 11"交错切碎，
				// 后续日期片会落在当前主框的 x 范围左侧（如"月"在"11"之后）。
				if (dx < -30 || dx > 100) continue;
				if (text.matches("[A-Za-z\\s]+")) continue;
				int dy = Math.abs(rCY - mainCY);
				int score = dx * 10 + dy;
				if (score < nextScore) {
					nextScore = score;
					next = r;
				}
			}
			if (next == null) break;
			String nextText = next.text();
			if (nextText.length() > 6) break;
			// 拼接：若主值是纯 4 位数字（年份），需在拼接时插入"年"分隔
			String joined;
			if (value.matches("^\\d{4}$") && nextText.matches("^\\d{1,2}.*")) {
				joined = value + "年" + nextText;
			} else {
				joined = value + nextText;
			}
			joined = joined.replaceAll("\\s+", "");
			// 拼接后如果包含完整日期就停
			if (DATE_PATTERN.matcher(joined).find()) {
				boxes.add(next);
				value = joined;
				break;
			}
			// 否则再合一轮
			boxes.add(next);
			value = joined;
			mainMaxX = LabelMatcher.maxX(next);
		}
		String finalVal = value.replaceAll("\\s+", "");
		// 自动补"日"：仅当日期含"日"前的数字（"yyyy年MM月dd"）但缺"日"字时补"日"
		// 部分日期 "yyyy年MM月"（无日）则不补，保持原样（如 register3 的"2003年11月"）
		// 用 REG_DATE_PARTIAL_PATTERN（不要求"日"前的数字）区分"完整日期无日" vs "部分日期"
		if (DATE_PATTERN.matcher(finalVal).find()) {
			if (!finalVal.endsWith("日") && !REG_DATE_PARTIAL_PATTERN.matcher(finalVal).matches()) {
				finalVal = finalVal + "日";
			}
			return LabeledMatch.of(finalVal, boxes);
		}
		// 部分日期 "yyyy 年 MM 月"（无日），保持原样不补"日"
		if (REG_DATE_PARTIAL_PATTERN.matcher(finalVal).matches()) {
			return LabeledMatch.of(finalVal, boxes);
		}
		return LabeledMatch.of(finalVal, boxes);
	}

	/**
	 * 公民身份号码：label 定位 + 18 位正则 find() 兜底。
	 */
	private static LabeledMatch parseIdNumber(List<PPOcrV6Result> results) {
		for (String label : ID_NUMBER_LABEL_KEYWORDS) {
			LabeledMatch m = LabelMatcher.matchValueFromPrefixWithBox(results, label);
			if (m.hasValue()) {
				String cleaned = cleanIdNumber(m.value());
				if (cleaned != null) {
					return LabeledMatch.of(cleaned, m.matches());
				}
			}
		}
		// 兜底：18 位正则 find() 扫所有框
		LabeledMatch fb = LabelMatcher.matchSubstringWithBox(results, text -> {
			Matcher m = ID_NUMBER_18_PATTERN.matcher(text);
			return m.find() ? m.group() : null;
		});
		if (fb.hasValue()) {
			log.debug("户口本解析：身份证号 正则兜底命中 \"{}\"", fb.value());
			return fb;
		}
		log.warn("户口本解析：未匹配到公民身份号码");
		return LabeledMatch.textOnly(null);
	}

	// ==================================================================
	// 公民身份号码
	// ==================================================================

	private static String cleanIdNumber(String raw) {
		if (raw == null) return null;
		String stripped = raw.replaceAll("\\s+", "");
		if (ID_NUMBER_18_PATTERN.matcher(stripped).matches()) {
			return stripped;
		}
		return null;
	}

	/**
	 * 身高：label 定位 + 正则清洗（兼容"170厘米"/"160"）。
	 */
	private static LabeledMatch parseHeight(List<PPOcrV6Result> results) {
		LabeledMatch m = matchValueWithBoxWithSpaces(results, "身高");
		if (!m.hasValue()) {
			return LabeledMatch.textOnly(null);
		}
		String raw = m.value();
		// OCR 可能识别成"170 厘米 血型"合并框，需切到"血型"前
		int cut = raw.length();
		for (String next : CollUtil.listOf("血型", "型", "血")) {
			int j = raw.indexOf(next);
			if (j >= 0 && j < cut) {
				cut = j;
			}
		}
		String heightOnly = raw.substring(0, cut).trim();
		Matcher regex = HEIGHT_PATTERN.matcher(heightOnly);
		if (regex.find()) {
			StringBuilder sb = new StringBuilder(regex.group(1));
			String unit = regex.group(2);
			if (unit != null && !unit.isEmpty()) {
				sb.append(unit);
			}
			return LabeledMatch.of(sb.toString(), m.matches());
		}
		return LabeledMatch.of(heightOnly, m.matches());
	}

	/**
	 * 服务处所：label 定位 + 合并框"服务处所无"剥前缀 + 拒绝右列其他字段 label fragment。
	 *
	 * <p>不做"X市/X省/X县"兜底扫描——它会误把"四川省""北京市"等 birthPlace/nativePlace
	 * 当作 workplace。5 张样本里"服务处所" label 都存在，label 匹配 + isLabelFragment 过滤已足够。
	 */
	private static LabeledMatch parseWorkplace(List<PPOcrV6Result> results) {
		// 1) 标签定位（含合并框"服务处所XXX"剥前缀）
		LabeledMatch m = LabelMatcher.matchValueFromPrefixWithBox(results, "服务处所");
		if (m.hasValue()) {
			String cleaned = m.value().trim();
			// 拒绝右列其他字段 label / label fragment（如"职""职业""婚姻状况"）
			if (!isLabelFragment(cleaned)) {
				return LabeledMatch.of(cleaned, m.matches());
			}
			log.debug("户口本解析：服务处所 位置匹配 \"{}\" 是 label fragment，拒绝", cleaned);
		}
		log.warn("户口本解析：未匹配到服务处所");
		return LabeledMatch.textOnly(null);
	}

	// ==================================================================
	// 服务处所（工作单位）
	// ==================================================================

	/**
	 * 何时由何地迁来本市(县)：label 框定位 + 右侧值匹配 + 兜底。
	 */
	private static LabeledMatch parseMoveToCityDate(List<PPOcrV6Result> results) {
		// 1) 精准定位「何时由何地迁来本市(县)」label 框（同排他策略：取最靠右下的 fragment）
		PPOcrV6Result labelBox = findMoveToCityLabelBox(results);
		if (labelBox != null) {
			LabeledMatch m = matchValueToRightOfLabel(results, labelBox, "何时由何地迁来本市(县)",
				"迁来本", "迁来本市", "迁来本市(县)", "何时由何地迁来本市", "迁来本市（县）");
			if (m.hasValue()) {
				String cleaned = trimAll(m.value());
				return LabeledMatch.of(cleaned, m.matches());
			}
		}
		// 2) 兜底：合并 label+value 框（如"迁来本市（县）2009年09月29日"）
		for (PPOcrV6Result r : results) {
			String text = r.text();
			if (text == null || text.isEmpty()) continue;
			for (String frag : new String[]{"迁来本市（县）", "迁来本市(县)", "迁来本市", "迁来本"}) {
				if (text.startsWith(frag) && text.length() > frag.length()) {
					String remaining = text.substring(frag.length()).trim();
					String date = extractDate(remaining);
					if (date == null) {
						Matcher loose = REG_DATE_LOOSE_PATTERN.matcher(remaining);
						if (loose.find()) {
							date = loose.group();
							if (!date.endsWith("日")) date = date + "日";
						}
					}
					if (date != null) {
						log.debug("户口本解析：何时由何地迁来本市(县) 合并框兜底命中 \"{}\"", date);
						return LabeledMatch.of(date, r);
					}
				}
			}
		}
		// 3) 兜底：扫所有框找"由...迁来"模式（排除 label 框自身）
		PPOcrV6Result best = findBoxByPatternExcludingLabels(results,
			Pattern.compile("由.{0,15}迁来"));
		if (best != null) {
			String cleaned = trimAll(best.text());
			log.debug("户口本解析：何时由何地迁来本市(县) 值模式兜底命中 \"{}\"", cleaned);
			return LabeledMatch.of(cleaned, best);
		}
		log.warn("户口本解析：未匹配到何时由何地迁来本市(县)");
		return LabeledMatch.textOnly(null);
	}

	// ==================================================================
	// 何时由何地迁来本市(县)
	// ==================================================================

	/**
	 * 定位「何时由何地迁来本市(县)」label 框。
	 *
	 * <p>OCR 切碎场景 1（左右）："何时由何地"（左） + "迁来本市（县）"（中下）
	 * <p>OCR 切碎场景 2（上下）："何时由何地"（上） + "迁来本市(县)"（下）
	 *
	 * <p>策略：取最底 + 最左的 fragment 作为 label 锚点（与民族处理一致）。
	 */
	private static PPOcrV6Result findMoveToCityLabelBox(List<PPOcrV6Result> results) {
		// 1) 完整 label 框
		for (String kw : new String[]{
			"何时由何地迁来本市(县)", "何时由何地迁来本市（县）",
			"何时由何地迁来本市( 县)", "何时由何地迁来本市"}) {
			for (PPOcrV6Result r : results) {
				if (kw.equals(r.text())) {
					return r;
				}
			}
		}
		// 2) fragment：取"最底 + 最左"的 fragment
		PPOcrV6Result best = null;
		int bestY = Integer.MIN_VALUE;
		int bestX = Integer.MAX_VALUE;
		String[] fragments = {
			"何时由何地", "迁来本", "迁来本市", "迁来本市(县)", "迁来本市（县）",
			"何时由何地迁来本", "何时由何地迁来本市"};
		for (PPOcrV6Result r : results) {
			String text = r.text();
			if (text == null) continue;
			for (String kw : fragments) {
				if (text.equals(kw)) {
					int y = LabelMatcher.maxY(r);
					int x = LabelMatcher.minX(r);
					if (y > bestY || (y == bestY && x < bestX)) {
						bestY = y;
						bestX = x;
						best = r;
					}
					break;
				}
			}
		}
		return best;
	}

	/**
	 * 何时由何地迁往本址：label 框定位 + 因/迁来模式兜底。
	 */
	private static LabeledMatch parseMoveToAddress(List<PPOcrV6Result> results) {
		LabeledMatch m = matchByKeywords(results, MOVE_TO_ADDRESS_LABEL_KEYWORDS);
		if (m.hasValue()) {
			String cleaned = trimAll(m.value());
			// 拒绝其他字段 label / 单字 label fragment（如"记"来自"登记日期"）。
			// 用 equals 严格匹配，避免误杀含 label 子串的合法值
			// （如"1994年07月27日因出生迁来"含"出生"label 子串）。
			if (!isLabelFragment(cleaned, false)) {
				return LabeledMatch.of(cleaned, m.matches());
			}
			log.debug("户口本解析：何时由何地迁往本址 位置匹配 \"{}\" 是 label fragment，拒绝", cleaned);
		}
		// 兜底：扫所有框找"因...迁来"模式（排除 label 框）
		PPOcrV6Result best = findBoxByPatternExcludingLabels(results,
			Pattern.compile("因.{0,8}迁来"));
		if (best != null) {
			String cleaned = trimAll(best.text());
			log.debug("户口本解析：何时由何地迁往本址 值模式兜底命中 \"{}\"", cleaned);
			return LabeledMatch.of(cleaned, best);
		}
		log.warn("户口本解析：未匹配到何时由何地迁往本址");
		return LabeledMatch.textOnly(null);
	}

	// ==================================================================
	// 何时由何地迁往本址
	// ==================================================================

	/**
	 * 登记日期：fragment 关键字定位 + 跨框合并（"2019年11月11" + "日"）。
	 */
	private static LabeledMatch parseRegistrationDate(List<PPOcrV6Result> results) {
		// 1) 标签定位
		for (String label : REG_DATE_LABEL_KEYWORDS) {
			LabeledMatch m = matchValueWithBoxWithSpaces(results, label);
			if (m.hasValue()) {
				String date = extractDate(m.value());
				if (date != null) {
					return LabeledMatch.of(date, m.matches());
				}
				// 跨框合并日期
				LabeledMatch merged = mergeDateSiblings(results, m);
				String mergedDate = extractDate(merged.value());
				if (mergedDate != null) {
					return LabeledMatch.of(mergedDate, merged.matches());
				}
				// 部分日期（"yyyy年MM月" 无"日"）也接受，如 register3 的"2003年11月"
				Matcher partial = REG_DATE_PARTIAL_PATTERN.matcher(merged.value());
				if (partial.matches()) {
					return LabeledMatch.of(partial.group(), merged.matches());
				}
				// 宽松正则
				Matcher loose = REG_DATE_LOOSE_PATTERN.matcher(merged.value());
				if (loose.find()) {
					String hit = loose.group();
					if (!hit.endsWith("日")) hit = hit + "日";
					return LabeledMatch.of(hit, merged.matches());
				}
			}
		}
		// 2) 兜底：扫底部 y 最大 + 含日期格式的框（完整 + 部分）
		//    收集所有候选（完整 + 部分），按 y 最大（最底部）选。
		//    排除"日期后还有非空字"的非纯日期框（如"2003年11月3日昌平区"）。
		PPOcrV6Result bestFull = null;
		String bestFullDate = null;
		int bestFullY = Integer.MIN_VALUE;
		PPOcrV6Result bestPartial = null;
		String bestPartialDate = null;
		int bestPartialY = Integer.MIN_VALUE;
		for (PPOcrV6Result r : results) {
			String text = r.text();
			if (text == null || text.isEmpty()) continue;
			// 完整日期
			Matcher m = REG_DATE_LOOSE_PATTERN.matcher(text);
			if (m.find()) {
				String hit = m.group();
				int endIdx = m.end();
				String suffix = endIdx < text.length() ? text.substring(endIdx).replaceAll("\\s+", "") : "";
				if (suffix.isEmpty()) {
					int y = (LabelMatcher.minY(r) + LabelMatcher.maxY(r)) / 2;
					if (y > bestFullY) {
						bestFullY = y;
						bestFull = r;
						bestFullDate = hit;
					}
				}
			}
			// 部分日期
			m = REG_DATE_PARTIAL_PATTERN.matcher(text);
			if (m.find()) {
				String hit = m.group();
				int endIdx = m.end();
				String suffix = endIdx < text.length() ? text.substring(endIdx).replaceAll("[\\s日]+", "") : "";
				if (suffix.isEmpty()) {
					int y = (LabelMatcher.minY(r) + LabelMatcher.maxY(r)) / 2;
					if (y > bestPartialY) {
						bestPartialY = y;
						bestPartial = r;
						bestPartialDate = hit;
					}
				}
			}
		}
		// 取 y 最大的候选（完整 vs 部分，哪个更靠下选哪个）
		PPOcrV6Result best;
		String bestDate;
		if (bestFull != null && (bestPartial == null || bestFullY >= bestPartialY)) {
			best = bestFull;
			bestDate = bestFullDate;
			if (!bestDate.endsWith("日")) bestDate = bestDate + "日";
		} else if (bestPartial != null) {
			best = bestPartial;
			bestDate = bestPartialDate;
			if (!bestDate.contains("月") && !bestDate.matches(".*[-./].*")) {
				bestDate = bestDate + "月";
			}
		} else {
			log.warn("户口本解析：未匹配到登记日期");
			return LabeledMatch.textOnly(null);
		}
		log.debug("户口本解析：登记日期 底部正则兜底命中 \"{}\"", bestDate);
		return LabeledMatch.of(bestDate, best);
	}

	// ==================================================================
	// 登记日期
	// ==================================================================

	/**
	 * 在 label 的每两个字符之间插入一个空格（用于兼容 OCR 切碎成"姓 名"的形式）。
	 */
	private static String spaceBetweenChars(String s) {
		if (s == null || s.isEmpty()) return s;
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < s.length(); i++) {
			sb.append(s.charAt(i));
			if (i < s.length() - 1) sb.append(' ');
		}
		return sb.toString();
	}

	// ==================================================================
	// 扩展版 findLabelBox / matchValueWithBox（兼容"姓 名" 中间空格）
	// ==================================================================

	/**
	 * 扩展版 findLabelBox：先按原 label 找，找不到再用"每字之间带空格"的变体找。
	 */
	private static PPOcrV6Result findLabelBoxWithSpaces(List<PPOcrV6Result> results, String label) {
		PPOcrV6Result best = LabelMatcher.findLabelBox(results, label);
		if (best != null) return best;
		String spaced = spaceBetweenChars(label);
		if (!spaced.equals(label)) {
			return LabelMatcher.findLabelBox(results, spaced);
		}
		return null;
	}

	/**
	 * 扩展版 matchValueWithBox：先用原 label 定位，找不到再用"带空格"变体。
	 *
	 * <p>当 OCR 把 label 写成"登记日期："（带冒号）等"label + 纯标点"形式时，
	 * 仍然将其作为 label 框处理，继续向右查找 value 框。
	 */
	private static LabeledMatch matchValueWithBoxWithSpaces(List<PPOcrV6Result> results, String label) {
		PPOcrV6Result labelBox = findLabelBoxWithSpaces(results, label);
		if (labelBox == null) {
			return LabeledMatch.textOnly(null);
		}
		String labelText = labelBox.text();
		if (labelText.startsWith(label) && labelText.length() > label.length()) {
			String afterLabel = labelText.substring(label.length());
			// 容忍"label + 纯标点"形式（如"登记日期："），label 后只有标点 / 空白时仍当作 label 框
			if (!afterLabel.matches("[：:，,\\s]+")) {
				return LabeledMatch.textOnly(null);
			}
		}
		int labelCenterX = (LabelMatcher.minX(labelBox) + LabelMatcher.maxX(labelBox)) / 2;
		int labelMinY = LabelMatcher.minY(labelBox);
		int labelMaxY = LabelMatcher.maxY(labelBox);
		int labelCenterY = (labelMinY + labelMaxY) / 2;
		int labelMaxX = LabelMatcher.maxX(labelBox);
		PPOcrV6Result best = null;
		int bestScore = Integer.MAX_VALUE;
		for (PPOcrV6Result r : results) {
			if (r == labelBox) continue;
			String text = r.text();
			if (text == null || text.isEmpty()) continue;
			if (text.matches("[A-Za-z\\s]+")) continue;
			if (!text.equals(label) && text.length() < label.length() && label.contains(text)) continue;
			// 排除右列其他字段 label / label fragment（如"婚姻状况""兵役状况""职业""职"）
			if (isLabelFragment(text)) continue;
			int x0 = LabelMatcher.minX(r);
			int rCenterX = (x0 + LabelMatcher.maxX(r)) / 2;
			if (rCenterX <= labelCenterX) continue;
			if (LabelMatcher.maxY(r) < labelMinY || LabelMatcher.minY(r) > labelMaxY) continue;
			int dy = Math.abs((LabelMatcher.minY(r) + LabelMatcher.maxY(r)) / 2 - labelCenterY);
			int dx = x0 - labelMaxX;
			int score = dx * 10 + dy;
			if (score < bestScore) {
				bestScore = score;
				best = r;
			}
		}
		if (best == null) {
			return LabeledMatch.textOnly(null);
		}
		return LabeledMatch.of(best.text(), best);
	}

	/**
	 * 找到 label 框后，按"右侧 y 重叠 + x 最近"取 value 框。
	 *
	 * <p>变体（skipTexts）：可指定要排除的文本（label 碎片不应被当作 value）。
	 *
	 * <p>变体（strictY）：是否使用严格 y 中心匹配（无 ±10 容差）。
	 * true 时候选框 y 中心必须落在 label y 范围内，用于 label 框是 fragment
	 * （如下片 fragment）的场景，避免把上方/下方行的值误匹配进来。
	 */
	private static LabeledMatch matchValueToRightOfLabel(List<PPOcrV6Result> results, PPOcrV6Result labelBox, String selfLabel, String... skipTexts) {
		return matchValueToRightOfLabel(results, labelBox, selfLabel, false, skipTexts);
	}

	/**
	 * {@link #matchValueToRightOfLabel(List, PPOcrV6Result, String, String...)} 的 strictY 重载。
	 *
	 * @param strictY true=严格 y 中心匹配（无 ±10 容差）；false=±10 容差（默认）
	 */
	private static LabeledMatch matchValueToRightOfLabel(List<PPOcrV6Result> results, PPOcrV6Result labelBox, String selfLabel, boolean strictY, String... skipTexts) {
		String labelText = labelBox.text();
		if (labelText.startsWith(selfLabel.replaceAll("\\s+", "")) && labelText.length() > selfLabel.length()) {
			return LabeledMatch.textOnly(null);
		}
		int labelMinY = LabelMatcher.minY(labelBox);
		int labelMaxY = LabelMatcher.maxY(labelBox);
		int labelCenterY = (labelMinY + labelMaxY) / 2;
		int labelMaxX = LabelMatcher.maxX(labelBox);
		PPOcrV6Result best = null;
		int bestScore = Integer.MAX_VALUE;
		for (PPOcrV6Result r : results) {
			if (r == labelBox) continue;
			String text = r.text();
			if (text == null || text.isEmpty()) continue;
			if (text.matches("[A-Za-z\\s]+")) continue;
			if (skipTexts != null) {
				boolean skip = false;
				for (String t : skipTexts) {
					if (text.equals(t)) {
						skip = true;
						break;
					}
				}
				if (skip) continue;
			}
			// 排除"label fragment"（如"浙""所"等单字标签）做 value
			if (isLabelFragment(text)) continue;
			int x0 = LabelMatcher.minX(r);
			// x0 > labelMaxX - 10：候选框必须在 label 右侧（允许 10px 重叠容差，
			// OCR 框边界常有 1~7px 的重叠）
			if (x0 <= labelMaxX - 10) continue;
			if (LabelMatcher.maxY(r) < labelMinY || LabelMatcher.minY(r) > labelMaxY) continue;
			int rCenterY = (LabelMatcher.minY(r) + LabelMatcher.maxY(r)) / 2;
			// y 中心必须在 label y 范围内。
			// strictY=true：无容差，用于 label 是 fragment 时排除上方/下方行的值；
			// strictY=false：±10px 容差，吸收 OCR 框边界 1~7px 抖动。
			if (strictY) {
				if (rCenterY < labelMinY || rCenterY > labelMaxY) continue;
			} else {
				if (rCenterY < labelMinY - 10 || rCenterY > labelMaxY + 10) continue;
			}
			int dy = Math.abs(rCenterY - labelCenterY);
			int dx = x0 - labelMaxX;
			int score = dx * 10 + dy;
			if (score < bestScore) {
				bestScore = score;
				best = r;
			}
		}
		if (best == null) {
			return LabeledMatch.textOnly(null);
		}
		return LabeledMatch.of(best.text(), best);
	}

	private static LabeledMatch matchByKeywords(List<PPOcrV6Result> results, List<String> keywords) {
		return LabelMatcher.matchValueByLabelKeywordWithBox(results, keywords);
	}

	// ==================================================================
	// 公共工具
	// ==================================================================

	private static LabeledMatch cleanRelationship(LabeledMatch m) {
		String cleaned = trimAll(m.value());
		return LabeledMatch.of(cleaned, m.matches());
	}

	private static boolean isRelationshipWord(String text) {
		if (text == null || text.isEmpty()) return false;
		return RELATIONSHIP_KEYWORDS.contains(text);
	}

	/**
	 * 扫描所有框找含正则的框，但要排除典型的 label fragment / label 关键字。
	 * 用于兜底正则，避免把「何时由何地」「迁来本市(县)」等 label 框当 value 选上。
	 */
	private static PPOcrV6Result findBoxByPatternExcludingLabels(List<PPOcrV6Result> results, Pattern pattern) {
		for (PPOcrV6Result r : results) {
			String text = r.text();
			if (text == null || text.isEmpty()) continue;
			// 排除明显的 label / 字段关键字
			if (isLabelFragment(text)) continue;
			if (pattern.matcher(text).find()) {
				return r;
			}
		}
		return null;
	}

	/**
	 * 判定是否为户口本字段名 / label fragment（出现这些文本的框不是 value）。
	 *
	 * <p>默认使用 {@code contains} 策略：多字 label 只要被文本包含即视为 label fragment，
	 * 用于排除合并 label+value 框（如"迁来本市（县）2009年09月29日"含"迁来本市（县）"）。
	 *
	 * <p>对部分字段（如"何时由何地迁往本址"的值"1994年07月27日因出生迁来"含"出生"label 子串），
	 * 调用 {@link #isLabelFragment(String, boolean)} 传 {@code false} 改用严格 equals 策略，
	 * 避免把含 label 子串的合法 value 误判为 label fragment。
	 */
	private static boolean isLabelFragment(String text) {
		return isLabelFragment(text, true);
	}

	/**
	 * 判定是否为户口本字段名 / label fragment，可选是否对多字 label 启用 {@code contains} 子串匹配。
	 *
	 * @param text             待判定的文本
	 * @param useContainsMulti 多字 label 是否启用 contains 子串匹配；false 时仅完全等于才视为 label
	 * @return true 表示该文本是 label fragment，不应作为 value
	 */
	private static boolean isLabelFragment(String text, boolean useContainsMulti) {
		if (text == null || text.isEmpty()) return false;
		// 单字中文 label fragment（"无"是合法 value，不在此列）
		if (text.length() == 1) {
			return SINGLE_CHAR_LABEL_FRAGMENTS.contains(text);
		}
		// 标签或字段关键字（节选）
		if (LABEL_FRAGMENTS.contains(text)) {
			return true;
		}
		// contains 仅对多字 label 启用，避免单字 label（如"别""业"）误伤
		// 含该字的合法 value（如"别女""初中毕业"）
		if (useContainsMulti) {
			for (String label : LABEL_FRAGMENTS) {
				if (label.length() >= 2 && text.contains(label)) {
					return true;
				}
			}
		}
		return false;
	}

	private static String extractDate(String text) {
		if (text == null) return null;
		Matcher m = DATE_PATTERN.matcher(text);
		return m.find() ? m.group() : null;
	}

	private static String trimAll(String text) {
		if (text == null) return null;
		return text.replaceAll("[\\s\u3000]+", "");
	}

	@Override
	public HouseholdRegisterResult parseResults(List<PPOcrV6Result> results) {
		HouseholdRegisterResult r = new HouseholdRegisterResult();
		r.setRawResults(new ArrayList<>(results));

		// 顶部：户号
		apply(r, "householdNo", parseHouseholdNo(results));

		// 第一行：姓名 + 与户主关系
		apply(r, "name", parseName(results));
		apply(r, "relationship", parseRelationship(results));

		// 性别
		apply(r, "gender", parseGender(results));

		// 第三行：出生地 + 民族
		apply(r, "birthPlace", parseBirthPlace(results));
		apply(r, "ethnicity", parseEthnicity(results));

		// 第四行：籍贯 + 出生日期
		apply(r, "nativePlace", matchValueWithBoxWithSpaces(results, "籍贯"));
		apply(r, "birthDate", parseBirthDate(results));

		// 第六行：公民身份号码 + 身高
		apply(r, "idNumber", parseIdNumber(results));
		apply(r, "height", parseHeight(results));

		// 文化程度
		apply(r, "education", matchValueWithBoxWithSpaces(results, "文化程度"));

		// 服务处所
		apply(r, "workplace", parseWorkplace(results));

		// 何时由何地迁来本市(县)
		apply(r, "moveToCityDate", parseMoveToCityDate(results));

		// 何时由何地迁往本址
		apply(r, "moveToAddress", parseMoveToAddress(results));

		// 登记日期
		apply(r, "registrationDate", parseRegistrationDate(results));

		return r;
	}
}
