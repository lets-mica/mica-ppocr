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

package net.dreamlu.mica.ai.ppocr.structured.parser.business;

import lombok.extern.slf4j.Slf4j;
import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Engine;
import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Result;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.BaseStructuredParser;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.DateRepair;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.FieldConfidence;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.FieldSource;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.LabelMatcher;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.LabelMatcher.LabeledMatch;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.ParsedField;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.ValidationResult;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.Validations;
import net.dreamlu.mica.ai.ppocr.utils.CollUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 营业执照 OCR 结构化解析器。
 *
 * <p>采用"标签定位 + 位置匹配"策略：营业执照常见两种版式——
 * <ul>
 *   <li><b>横版</b>（常见于营业执照正本/副本）：左右双列布局。
 *       <ul>
 *         <li>左列：名称 / 类型 / 法定代表人 / 经营范围</li>
 *         <li>右列：注册资本 / 成立日期 / 营业期限 / 住址</li>
 *         <li>顶部独立区：统一社会信用代码 + 证照编号</li>
 *       </ul>
 *   </li>
 *   <li><b>竖版</b>（常见于个体户/小微）：单列布局，标签 + 值上下相邻。</li>
 * </ul>
 *
 * <p><b>OCR 噪声处理</b>（基于 5 张真实执照样本总结）：
 * <ul>
 *   <li><b>信用代码</b>：可能合并到"统一社会信用代码"标签框内、或完全缺失（用"注册号"兜底）、
 *       或被截断（17 位 → 正则允许 ≥17 位）；</li>
 *   <li><b>名称 / 类型</b>：常被合并识别成"名类"/"称XXX有限公司" / "型XXX"——
 *       通过 {@link LabelMatcher#findCleanLabelBox} 拒绝被其他字段关键字污染的 fragment；</li>
 *   <li><b>住址</b>：常见别名"营业场所"；fragment 拆成"住"+"所广州市"——按"住所"独立框优先；</li>
 *   <li><b>法定代表人</b>：别名"负责人"；</li>
 *   <li><b>经营范围</b>：常跨多行（标签 + 多行值）；通过
 *       {@link LabelMatcher#collectMultiLineRight} 拼接。</li>
 * </ul>
 *
 * <p>输出结果会填充 {@code BusinessLicenseResult#getRawResults()}（完整 OCR 结果）
 * 与 {@code BusinessLicenseResult#getFieldBoxes()}（字段名 → box 坐标列表），
 * 方便调用方在页面上复原并高亮对应字段。
 */
@Slf4j
public class BusinessLicenseParser extends BaseStructuredParser<BusinessLicenseResult> {

	private static final String LABEL_CREDIT_CODE = "统一社会信用代码";

	// ========================================================================
	// 字段标签常量：避免散落的字符串字面量，便于复用与单点修改
	// ========================================================================
	private static final String LABEL_REGISTER_NO = "注册号";
	private static final String LABEL_NAME = "名称";
	private static final String LABEL_TYPE = "类型";
	private static final String LABEL_LEGAL_PERSON = "法定代表人";
	private static final String LABEL_LEGAL_ALIAS = "负责人";
	private static final String LABEL_CAPITAL = "注册资本";
	private static final String LABEL_ESTABLISH_DATE = "成立日期";
	private static final String LABEL_OPERATING_PERIOD = "营业期限";
	private static final String LABEL_ADDRESS = "住所";
	private static final String LABEL_ADDRESS_ALIAS = "营业场所";
	private static final String LABEL_SCOPE = "经营范围";
	/**
	 * 统一社会信用代码：18 位大写字母 + 数字（GB 32100-2015）。
	 * OCR 截断场景放宽到 12-18 位。
	 */
	private static final Pattern CREDIT_CODE_PATTERN = Pattern.compile("^[0-9A-Z]{12,18}$");

	// ========================================================================
	// 正则常量
	// ========================================================================
	/**
	 * 注册号（旧版执照编号，15 位数字）：用于"统一社会信用代码"缺失时兜底。
	 */
	private static final Pattern REGISTER_NO_PATTERN = Pattern.compile("^\\d{15}$");
	/**
	 * 证件编号前缀（如 "编号："/"编号:"），用于 OCR 把 "编号"+代码 合并识别时的剥值。
	 */
	private static final Pattern NO_PREFIX_PATTERN = Pattern.compile("^\\s*编号[:：]\\s*");
	/**
	 * 日期格式：yyyy年MM月dd日 / yyyy-MM-dd / yyyy/MM/dd / yyyy.MM.dd。
	 */
	private static final Pattern DATE_PATTERN = Pattern.compile(
		"\\d{4}[-./年]\\d{1,2}[-./月]\\d{1,2}日?");
	/**
	 * 有效日期至关键字：长期 / 永久。
	 */
	private static final Pattern PERIOD_KEYWORD = Pattern.compile("(长期|永久)");
	/**
	 * 经营范围黑名单关键词（用于排除 OCR 噪声如"经营范围"标签框本身）。
	 */
	private static final Pattern SCOPE_KEYWORD = Pattern.compile(
		"(经营|销售|生产|服务|开发|咨询|技术|管理|加工|贸易|运输|建筑|工程|施工|安装|维修|设计|租赁|代理|推广|展览|演出|培训|信息|科技)");
	/**
	 * 公司类型合法关键字（起首匹配），用于全文兜底。
	 */
	private static final Pattern TYPE_KEYWORD = Pattern.compile(
		"^(有限责任公司|股份有限公司|个体工商户|个人独资企业|合伙企业|全民所有制|集体所有制|"
			+ "国有独资公司|一人有限责任公司|分公司|外商投资|中外合作|中外合资)(.*)$");
	/**
	 * 字段标签全集：用于 {@link LabelMatcher#findCleanLabelBox} 时拒绝 fragment 噪声。
	 * 当 fragment 文本含其中任一标签视为污染。
	 * <p>"名 称" / "类 型" 等带空格的变体是 OCR 常见错字，一并覆盖。
	 */
	private static final Set<String> ALL_LABELS = CollUtil.setOf(
		"名称", "名 称", "类型", "类 型",
		"法定代表人", "负责人",
		"注册资本",
		"成立日期", "营业期限",
		"住所", "营业场所",
		"经营范围",
		"统一社会信用代码", "注册号");

	// ========================================================================
	// 集合常量
	// ========================================================================
	/**
	 * 字段 fragment 单字集合：用于找独立 fragment 标签（"名"/"称"/"类"/"型"/"住"/"所"）。
	 */
	private static final Set<String> SINGLE_CHAR_FRAGMENTS = CollUtil.setOf(
		"名", "称", "类", "型", "住", "所");
	/**
	 * 法定代表人（含"负责人"）字段标签候选，按优先级列出。
	 */
	private static final List<String> LEGAL_PERSON_LABELS = CollUtil.listOf(
		LABEL_LEGAL_PERSON, LABEL_LEGAL_ALIAS);
	/**
	 * 住址字段标签候选（含"营业场所"别名）。
	 */
	private static final List<String> ADDRESS_LABELS = CollUtil.listOf(
		LABEL_ADDRESS, LABEL_ADDRESS_ALIAS);
	/**
	 * 其它字段 label 关键词：用于 legalPerson 等值框校验，
	 * 避免"营业期限" / "成立日期" 这种跨栏 label 被误选成值。
	 */
	private static final Set<String> OTHER_FIELD_LABELS = CollUtil.setOf(
		"营业期限", "成立日期", "注册资本", "经营范围", "统一社会信用代码",
		"注册号", "法定代表人", "负责人", "登记机关", "证照编号", "编号", "营业执照");
	/**
	 * 经营范围专属装配：需要跳过的其它字段 fragment（单字 + 合并前缀）。
	 */
	private static final Set<String> SCOPE_SKIP_FRAGMENTS = CollUtil.setOf(
		"住", "所", "名", "称", "名类", "型", "类", "法定代表人", "负责人");
	/**
	 * 信用代码最小长度（用于"编号:"合并框剥前缀后的初筛）。
	 */
	private static final int CREDIT_CODE_MIN_LEN = 12;

	// ========================================================================
	// 调参常量：所有魔术数字集中在此，便于按样本调优
	// ========================================================================
	/**
	 * 法定代表人姓名最大长度（含合并框剥值场景）。
	 */
	private static final int LEGAL_PERSON_MAX_LEN = 15;
	/**
	 * 法定代表人姓名 fragment 剥值场景的最大长度（更严格）。
	 */
	private static final int LEGAL_PERSON_FRAG_MAX_LEN = 10;
	/**
	 * 公司类型文本最大长度（防止误把经营范围当成类型）。
	 */
	private static final int TYPE_MAX_LEN = 60;
	/**
	 * 住址文本最大长度（防止误把经营范围当成住址）。
	 */
	private static final int ADDRESS_MAX_LEN = 80;
	/**
	 * 经营范围兜底最低 OCR 置信度（低于此视为噪声框）。
	 */
	private static final double MIN_SCOPE_FALLBACK_SCORE = 0.5;
	/**
	 * 经营范围标签长度。
	 */
	private static final int SCOPE_LABEL_LEN = 4;
	/**
	 * 经营范围合并框剥值最小长度。
	 */
	private static final int SCOPE_STRIPPED_MIN_LEN = 2;
	/**
	 * 经营范围拼接结果最小长度（少于则视为命中失败）。
	 */
	private static final int SCOPE_ASSEMBLED_MIN_LEN = 4;
	/**
	 * 经营范围装配：候选收集时 label 下方允许的最大行数。
	 */
	private static final int SCOPE_BELOW_MAX_LINES = 6;
	/**
	 * 经营范围装配：行间间距阈值（行高倍数）。
	 */
	private static final double SCOPE_LINE_GAP_FACTOR = 1.5;
	/**
	 * fragment 剥前缀长度容差：剥后长度不超过 标签 + 该值。
	 */
	private static final int SCOPE_FRAG_TAIL_TOLERANCE = 20;
	/**
	 * 法定代表人 fragment 剥前缀标签长度。
	 */
	private static final int LEGAL_LABEL_LEN = 5;
	/**
	 * 法定代表人合并框最小长度（含标签）。
	 */
	private static final int LEGAL_MERGED_MIN_LEN = LEGAL_LABEL_LEN + 1;
	/**
	 * 营业期限标签长度。
	 */
	private static final int PERIOD_LABEL_LEN = 4;

	/**
	 * 构造营业执照解析器，绑定推理引擎。
	 *
	 * @param engine PP-OCRv6 推理引擎；可为 null（仅在仅调用 {@link #parseResults(List)} 时）
	 */
	public BusinessLicenseParser(PPOcrV6Engine engine) {
		super(engine);
	}

	// ========================================================================
	// 入口
	// ========================================================================

	/**
	 * 信用代码提取。策略：标签匹配 → "编号:XXX" 合并框 → 注册号兜底。
	 */
	private static LabeledMatch parseCreditCode(List<PPOcrV6Result> results) {
		// 1) 独立标签 / 合并框
		LabeledMatch match = LabelMatcher.matchValueFromPrefixWithBox(results, LABEL_CREDIT_CODE);
		// 标签位置匹配必须过格式校验（避免"营业执照"等大标题被误判为值）
		if (match.hasValue() && !CREDIT_CODE_PATTERN.matcher(match.value()).matches()) {
			match = LabeledMatch.textOnly(null);
		}
		// 2) "编号:XXX" / "编号：XXX" 合并框兜底优先于正则（更精准）
		if (!match.hasValue()) {
			LabeledMatch noMatch = matchCreditCodeFromNoPrefix(results);
			if (noMatch.hasValue()) {
				log.debug("营业执照解析：信用代码按 \"编号:\" 合并框兜底 \"{}\"", noMatch.value());
				match = noMatch;
			}
		}
		// 3) 全文正则兜底
		if (!match.hasValue()) {
			match = LabelMatcher.labelOrFallbackWithBox(
				LabelMatcher.matchValueFromPrefixWithBox(results, LABEL_CREDIT_CODE),
				results, CREDIT_CODE_PATTERN, LABEL_CREDIT_CODE, false);
		}
		// 4) 注册号兜底（旧版执照）
		if (!match.hasValue()) {
			LabeledMatch registerMatch = LabelMatcher.labelOrFallbackWithBox(
				LabelMatcher.matchValueFromPrefixWithBox(results, LABEL_REGISTER_NO),
				results, REGISTER_NO_PATTERN, LABEL_REGISTER_NO, false);
			if (registerMatch.hasValue()) {
				log.debug("营业执照解析：信用代码缺失，按注册号兜底 \"{}\"", registerMatch.value());
				match = registerMatch;
			}
		}
		return match;
	}

	// ========================================================================
	// 主流程
	// ========================================================================

	/**
	 * 信用代码按 "编号:XXX" / "编号：XXX" 合并框兜底。
	 *
	 * <p>某些执照版式把"编号"标签 + 信用代码识别到一个框，
	 * 截前缀后剩下的代码字符串按 {@link #CREDIT_CODE_PATTERN} 校验。
	 */
	private static LabeledMatch matchCreditCodeFromNoPrefix(List<PPOcrV6Result> results) {
		for (PPOcrV6Result r : results) {
			String text = r.text();
			String stripped = NO_PREFIX_PATTERN.matcher(text).replaceFirst("").trim();
			if (stripped.equals(text) || stripped.length() < CREDIT_CODE_MIN_LEN) {
				continue;
			}
			if (CREDIT_CODE_PATTERN.matcher(stripped).matches()) {
				return LabeledMatch.of(stripped, r);
			}
		}
		return LabeledMatch.textOnly(null);
	}

	// ========================================================================
	// 社会信用代码
	// ========================================================================

	/**
	 * 单位名称提取。处理：
	 * <ul>
	 *   <li>独立"名称"标签 + 右侧 y 重叠值；</li>
	 *   <li>合并框"名称XXX有限公司" → 剥前缀；</li>
	 *   <li>合并框"称XXX有限公司"（横版常见）→ 用单字"称" fragment 标签 → 右侧值；</li>
	 *   <li>合并框"名类"（横版）→ fragment 拒绝。</li>
	 * </ul>
	 */
	private static LabeledMatch parseName(List<PPOcrV6Result> results) {
		// 1) 独立"名称"标签
		PPOcrV6Result labelBox = LabelMatcher.findCleanLabelBox(results, LABEL_NAME, ALL_LABELS);
		if (labelBox != null) {
			String text = labelBox.text();
			if (LABEL_NAME.equals(text)) {
				LabeledMatch m = LabelMatcher.matchValueWithBox(results, LABEL_NAME);
				if (m.hasValue()) {
					return m;
				}
			} else if (text.startsWith(LABEL_NAME) && text.length() > LABEL_NAME.length()) {
				String stripped = text.substring(LABEL_NAME.length()).trim();
				if (stripped.length() >= 2) {
					log.debug("营业执照解析：\"{}\" 从合并框 \"{}\" 剥出值 \"{}\"", LABEL_NAME, text, stripped);
					return LabeledMatch.of(stripped, labelBox);
				}
			}
		}
		// 2) 合并框"称XXX有限公司"：用"称"作 fragment 标签，右侧 y 重叠取首值
		PPOcrV6Result chFrag = LabelMatcher.findCleanLabelBox(results, "称", ALL_LABELS);
		if (chFrag != null && "称".equals(chFrag.text())) {
			LabeledMatch m = matchRightByLabelBox(chFrag, results, null);
			if (m.hasValue()) {
				log.debug("营业执照解析：\"{}\" 按 fragment \"称\" 取值 \"{}\"", LABEL_NAME, m.value());
				return m;
			}
		}
		// 3) 兜底：合并框"名类" + 同行 "称XXX" → 剥前缀"称"
		for (PPOcrV6Result r : results) {
			String text = r.text();
			if (text.startsWith("称") && text.length() > 1) {
				String stripped = text.substring(1).trim();
				if (stripped.length() >= 2) {
					log.debug("营业执照解析：\"{}\" 从 \"{}\" 剥前缀 \"称\" → \"{}\"", LABEL_NAME, text, stripped);
					return LabeledMatch.of(stripped, r);
				}
			}
		}
		return LabeledMatch.textOnly(null);
	}

	/**
	 * 类型提取。处理：
	 * <ul>
	 *   <li>独立"类型"标签；</li>
	 *   <li>合并框"类型XXX" / "型XXX" → 剥前缀；</li>
	 *   <li>全文关键词兜底：找以典型公司类型关键词开头的最长框。</li>
	 * </ul>
	 */
	private static LabeledMatch parseType(List<PPOcrV6Result> results) {
		// 1) 独立"类型"标签
		PPOcrV6Result labelBox = LabelMatcher.findCleanLabelBox(results, LABEL_TYPE, ALL_LABELS);
		if (labelBox != null) {
			String text = labelBox.text();
			if (LABEL_TYPE.equals(text)) {
				LabeledMatch m = LabelMatcher.matchValueWithBox(results, LABEL_TYPE);
				if (m.hasValue()) {
					return m;
				}
			} else if (text.startsWith(LABEL_TYPE) && text.length() > LABEL_TYPE.length()) {
				String stripped = text.substring(LABEL_TYPE.length()).trim();
				if (stripped.length() >= 2) {
					log.debug("营业执照解析：\"{}\" 从合并框 \"{}\" 剥出值 \"{}\"", LABEL_TYPE, text, stripped);
					return LabeledMatch.of(stripped, labelBox);
				}
			}
		}
		// 2) 合并框"型XXX"：用"型"作 fragment 标签
		PPOcrV6Result typeFrag = LabelMatcher.findCleanLabelBox(results, "型", ALL_LABELS);
		if (typeFrag != null && "型".equals(typeFrag.text())) {
			LabeledMatch m = matchRightByLabelBox(typeFrag, results, null);
			if (m.hasValue()) {
				log.debug("营业执照解析：\"{}\" 按 fragment \"型\" 取值 \"{}\"", LABEL_TYPE, m.value());
				return m;
			}
		}
		// 3) 兜底：合并框"型XXX" → 剥前缀
		for (PPOcrV6Result r : results) {
			String text = r.text();
			if (text.startsWith("型") && text.length() > 1 && text.length() < TYPE_MAX_LEN) {
				String stripped = text.substring(1).trim();
				if (isLikelyTypeText(stripped)) {
					log.debug("营业执照解析：\"{}\" 从 \"{}\" 剥前缀 \"型\" → \"{}\"", LABEL_TYPE, text, stripped);
					return LabeledMatch.of(stripped, r);
				}
			}
		}
		// 4) 全文关键词兜底
		return findTypeByKeyword(results);
	}

	// ========================================================================
	// 单位名称
	// ========================================================================

	/**
	 * 校验剥前缀后的文本是否像公司类型（含"有限"/"责任"/"公司"/"个体"/"个人"关键字）。
	 */
	private static boolean isLikelyTypeText(String text) {
		if (text.length() < 2 || text.length() >= TYPE_MAX_LEN) {
			return false;
		}
		return text.contains("有限")
			|| text.contains("责任")
			|| text.contains("公司")
			|| text.contains("个体")
			|| text.contains("个人");
	}

	// ========================================================================
	// 类型
	// ========================================================================

	/**
	 * 全文关键词兜底：取以典型公司类型关键字开头的最长框。
	 */
	private static LabeledMatch findTypeByKeyword(List<PPOcrV6Result> results) {
		PPOcrV6Result best = null;
		int bestLen = -1;
		for (PPOcrV6Result r : results) {
			String text = r.text();
			java.util.regex.Matcher mm = TYPE_KEYWORD.matcher(text);
			if (!mm.find()) {
				continue;
			}
			String hit = mm.group(1) + mm.group(2);
			if (hit.length() >= 3 && hit.length() > bestLen) {
				bestLen = hit.length();
				best = r;
			}
		}
		if (best != null) {
			log.debug("营业执照解析：\"{}\" 全文关键词兜底命中 \"{}\"", LABEL_TYPE, best.text());
			return LabeledMatch.of(best.text(), best);
		}
		return LabeledMatch.textOnly(null);
	}

	/**
	 * 法定代表人提取（含"负责人"别名）。
	 *
	 * <p>OCR 经常把"法定代表人"标签框和值合并（"法定代表人方平"），
	 * 或值漏识别（label 独立 + 值缺失）。别名"负责人"用于个体户执照。
	 */
	private static LabeledMatch parseLegalPerson(List<PPOcrV6Result> results) {
		for (String label : LEGAL_PERSON_LABELS) {
			PPOcrV6Result labelBox = LabelMatcher.findCleanLabelBox(results, label, ALL_LABELS);
			if (labelBox == null) {
				continue;
			}
			String text = labelBox.text();
			if (label.equals(text)) {
				LabeledMatch m = LabelMatcher.matchValueWithBox(results, label);
				if (m.hasValue()
					&& !containsOtherLabel(m.value())
					&& m.value().length() <= LEGAL_PERSON_MAX_LEN) {
					return m;
				}
			} else if (text.startsWith(label) && text.length() > label.length()) {
				String stripped = text.substring(label.length()).trim();
				if (isLikelyLegalPersonText(stripped)) {
					log.debug("营业执照解析：\"{}\" 从合并框 \"{}\" 剥出值 \"{}\"", label, text, stripped);
					return LabeledMatch.of(stripped, labelBox);
				}
			}
		}
		// 兜底：扫所有框找含"法定代表人"前缀的合并框
		for (PPOcrV6Result r : results) {
			String text = r.text();
			if (text.startsWith(LABEL_LEGAL_PERSON) && text.length() > LEGAL_MERGED_MIN_LEN) {
				String stripped = text.substring(LEGAL_LABEL_LEN).trim();
				if (isLikelyLegalPersonText(stripped)) {
					return LabeledMatch.of(stripped, r);
				}
			}
		}
		return LabeledMatch.textOnly(null);
	}

	/**
	 * 校验剥前缀后的文本是否像姓名：长度在 [1, FRAG_MAX_LEN] 且不含其它字段关键字。
	 */
	private static boolean isLikelyLegalPersonText(String text) {
		return !text.isEmpty()
			&& text.length() <= LEGAL_PERSON_FRAG_MAX_LEN
			&& !containsOtherLabel(text);
	}

	// ========================================================================
	// 法定代表人
	// ========================================================================

	/**
	 * 判定文本是否含其它字段的关键字（用于防止跨栏 label 误选）。
	 */
	private static boolean containsOtherLabel(String text) {
		if (text == null) {
			return false;
		}
		for (String lbl : OTHER_FIELD_LABELS) {
			if (text.contains(lbl)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 有效日期至提取。处理合并框（"营业期限2019年01月01日至长期"）+ 独立标签。
	 */
	private static LabeledMatch parseOperatingPeriod(List<PPOcrV6Result> results) {
		// 1) 合并框剥值
		for (PPOcrV6Result r : results) {
			String text = r.text();
			if (text.startsWith(LABEL_OPERATING_PERIOD) && text.length() > PERIOD_LABEL_LEN) {
				String stripped = text.substring(PERIOD_LABEL_LEN).trim();
				if (PERIOD_KEYWORD.matcher(stripped).find() || DATE_PATTERN.matcher(stripped).find()) {
					return LabeledMatch.of(stripped, r);
				}
			}
		}
		// 2) 独立标签右侧值
		LabeledMatch m = LabelMatcher.matchValueWithBox(results, LABEL_OPERATING_PERIOD);
		if (m.hasValue()) {
			return m;
		}
		// 3) 关键字兜底（"长期"/"永久"）
		String fallback = LabelMatcher.matchPattern(results, PERIOD_KEYWORD, false);
		if (fallback != null) {
			log.debug("营业执照解析：有效日期至关键字兜底命中 \"{}\"", fallback);
			return LabeledMatch.textOnly(fallback);
		}
		return LabeledMatch.textOnly(null);
	}

	/**
	 * 住址提取（含"营业场所"别名）。处理：
	 * <ul>
	 *   <li>合并框"住所XXX" / "营业场所XXX" → 剥前缀；</li>
	 *   <li>独立标签 + 跨行值 → 拼接多行；</li>
	 *   <li>fragment 合并框（"所XXX"） → 剥前缀得 XXX。</li>
	 * </ul>
	 */
	private static String parseAddress(List<PPOcrV6Result> results) {
		for (String label : ADDRESS_LABELS) {
			// 1) 合并框剥值
			LabeledMatch merged = stripMergedLabel(results, label);
			if (merged.hasValue()) {
				log.debug("营业执照解析：\"{}\" 从合并框 \"{}\" 剥出值 \"{}\"", label, merged.matches().get(0).text(), merged.value());
				return merged.value();
			}
			// 2) 独立标签
			PPOcrV6Result labelBox = LabelMatcher.findCleanLabelBox(results, label, ALL_LABELS);
			if (labelBox != null && label.equals(labelBox.text())) {
				// 单行
				LabeledMatch m = LabelMatcher.matchValueWithBox(results, label);
				if (m.hasValue()) {
					return m.value();
				}
				// 多行
				String multi = LabelMatcher.collectMultiLineRight(labelBox, results, SINGLE_CHAR_FRAGMENTS);
				if (multi != null) {
					return multi;
				}
			}
		}
		// 3) fragment 合并框兜底："所XXX" → 剥前缀得地址
		LabeledMatch fragMerged = stripFragmentPrefix(results, "所");
		if (fragMerged.hasValue()) {
			log.debug("营业执照解析：\"{}\" 从 fragment 合并框 \"{}\" 剥出值 \"{}\"",
				LABEL_ADDRESS, fragMerged.matches().get(0).text(), fragMerged.value());
			return fragMerged.value();
		}
		// 4) fragment 兜底："所"单字 → 右侧 y 重叠
		PPOcrV6Result suoFrag = LabelMatcher.findCleanLabelBox(results, "所", ALL_LABELS);
		if (suoFrag != null && "所".equals(suoFrag.text())) {
			LabeledMatch m = matchRightByLabelBox(suoFrag, results, SINGLE_CHAR_FRAGMENTS);
			if (m.hasValue()) {
				log.debug("营业执照解析：\"{}\" 按 fragment \"所\" 取值 \"{}\"", LABEL_ADDRESS, m.value());
				return m.value();
			}
		}
		return null;
	}

	// ========================================================================
	// 有效日期至
	// ========================================================================

	/**
	 * 扫所有框，匹配"label + 值"合并框，剥前缀后返回（附带原框便于日志）。
	 *
	 * @return 匹配则返回 {@code LabeledMatch}，否则 {@link LabeledMatch#textOnly} 空匹配
	 */
	private static LabeledMatch stripMergedLabel(List<PPOcrV6Result> results, String label) {
		for (PPOcrV6Result r : results) {
			String text = r.text();
			if (text.startsWith(label) && text.length() > label.length()) {
				String stripped = text.substring(label.length()).trim();
				if (stripped.length() >= 2) {
					return LabeledMatch.of(stripped, r);
				}
			}
		}
		return LabeledMatch.textOnly(null);
	}

	// ========================================================================
	// 住址
	// ========================================================================

	/**
	 * 扫所有框，匹配"prefix + 值"fragment 合并框，剥前缀后返回（附带原框便于日志）。
	 *
	 * @return 匹配则返回 {@code LabeledMatch}，否则 {@link LabeledMatch#textOnly} 空匹配
	 */
	private static LabeledMatch stripFragmentPrefix(List<PPOcrV6Result> results, String prefix) {
		for (PPOcrV6Result r : results) {
			String text = r.text();
			if (text.length() > prefix.length() && text.startsWith(prefix)) {
				String stripped = text.substring(prefix.length()).trim();
				if (stripped.length() >= 2 && stripped.length() < ADDRESS_MAX_LEN) {
					return LabeledMatch.of(stripped, r);
				}
			}
		}
		return LabeledMatch.textOnly(null);
	}

	/**
	 * 经营范围提取。处理：
	 * <ul>
	 *   <li>合并框"经营范围XXX" → 剥前缀；</li>
	 *   <li>独立标签 + 多行值 → 拼接多行；</li>
	 *   <li>兜底：找底部最大中文文本框（含经营范围关键词、且不像印章噪声）。</li>
	 * </ul>
	 */
	private static String parseBusinessScope(List<PPOcrV6Result> results) {
		// 1) 合并框剥值
		LabeledMatch merged = stripMergedScope(results);
		if (merged.hasValue()) {
			log.debug("营业执照解析：\"{}\" 从合并框 \"{}\" 剥出值 \"{}\"",
				LABEL_SCOPE, merged.matches().get(0).text(), merged.value());
			return merged.value();
		}
		// 2) 独立标签 → 行内水平拼接 + 多行 y 升序拼接
		PPOcrV6Result labelBox = LabelMatcher.findCleanLabelBox(results, LABEL_SCOPE, ALL_LABELS);
		if (labelBox != null && LABEL_SCOPE.equals(labelBox.text())) {
			String assembled = collectBusinessScopeByLabelBox(labelBox, results);
			if (assembled != null && assembled.length() >= SCOPE_ASSEMBLED_MIN_LEN) {
				return assembled;
			}
		}
		// 3) 兜底：底部最大中文文本框
		PPOcrV6Result best = findScopeByFallback(results);
		if (best != null) {
			log.debug("营业执照解析：经营范围按底部关键词兜底命中 \"{}\"", best.text());
			return best.text();
		}
		log.warn("营业执照解析：未匹配到经营范围");
		return null;
	}

	/**
	 * 合并框"经营范围XXX"剥前缀（附带原框便于日志）。
	 *
	 * @return 匹配则返回 {@code LabeledMatch}，否则 {@link LabeledMatch#textOnly} 空匹配
	 */
	private static LabeledMatch stripMergedScope(List<PPOcrV6Result> results) {
		for (PPOcrV6Result r : results) {
			String text = r.text();
			if (text.startsWith(LABEL_SCOPE) && text.length() > SCOPE_LABEL_LEN) {
				String stripped = text.substring(SCOPE_LABEL_LEN).trim();
				if (stripped.length() >= SCOPE_STRIPPED_MIN_LEN && SCOPE_KEYWORD.matcher(stripped).find()) {
					return LabeledMatch.of(stripped, r);
				}
			}
		}
		return LabeledMatch.textOnly(null);
	}

	// ========================================================================
	// 经营范围
	// ========================================================================

	/**
	 * 兜底：取满足"经营范围特征 + 评分足够 + 面积最大"的 OCR 框。
	 */
	private static PPOcrV6Result findScopeByFallback(List<PPOcrV6Result> results) {
		PPOcrV6Result best = null;
		long bestArea = 0;
		for (PPOcrV6Result r : results) {
			if (!isLikelyScopeFallbackBox(r)) {
				continue;
			}
			int w = LabelMatcher.maxX(r) - LabelMatcher.minX(r);
			int h = LabelMatcher.maxY(r) - LabelMatcher.minY(r);
			long area = (long) w * h;
			if (area > bestArea) {
				bestArea = area;
				best = r;
			}
		}
		return best;
	}

	/**
	 * 经营范围兜底候选框判定：
	 * <ul>
	 *   <li>长度 ≥ 4；</li>
	 *   <li>含经营范围关键字；</li>
	 *   <li>不含"期限"/"成立"/"住址"等其它字段关键字；</li>
	 *   <li>不像登记机关（不以"局"结尾、不含"工商行政"/"监督管理"）；</li>
	 *   <li>OCR 评分足够（排除印章噪声）。</li>
	 * </ul>
	 */
	private static boolean isLikelyScopeFallbackBox(PPOcrV6Result r) {
		String text = r.text();
		if (text.length() < SCOPE_ASSEMBLED_MIN_LEN) {
			return false;
		}
		if (!SCOPE_KEYWORD.matcher(text).find()) {
			return false;
		}
		if (text.contains("期限") || text.contains("成立") || text.contains("住址")) {
			return false;
		}
		if (text.endsWith("局") || text.contains("工商行政") || text.contains("监督管理")) {
			return false;
		}
		return r.score() >= MIN_SCOPE_FALLBACK_SCORE;
	}

	/**
	 * 经营范围专属装配：从 label 框右侧收集 OCR 段，行内水平拼接、多行 y 升序拼接。
	 *
	 * <p>关键点：OCR 经常把一行很长、超宽的文本切成多个相邻框（[18] "体育（...开展经营" 和
	 * [19] "活动。）" 是同行的水平续段），此时不应分别取值，而应拼接为完整一行。
	 *
	 * <p>允许范围：r.centerX &gt; label.centerX，
	 * 且 r 与 label 的 y 区间有重叠，或 r 在 label 下方最大 6 行的范围内。
	 */
	private static String collectBusinessScopeByLabelBox(PPOcrV6Result labelBox,
														 List<PPOcrV6Result> results) {
		int labelCenterX = (LabelMatcher.minX(labelBox) + LabelMatcher.maxX(labelBox)) / 2;
		int labelMinY = LabelMatcher.minY(labelBox);
		int labelMaxY = LabelMatcher.maxY(labelBox);
		int oneLine = Math.max(1, labelMaxY - labelMinY);

		// 1. 收集候选：右侧 + y 与 label 重叠或紧邻下方最多 SCOPE_BELOW_MAX_LINES 行
		List<PPOcrV6Result> candidates = new ArrayList<>();
		for (PPOcrV6Result r : results) {
			if (r == labelBox) {
				continue;
			}
			String text = r.text();
			if (text.isEmpty() || isScopeSkipFragment(text)) {
				continue;
			}
			int x0 = LabelMatcher.minX(r);
			int rCenterX = (x0 + LabelMatcher.maxX(r)) / 2;
			if (rCenterX <= labelCenterX) {
				continue;
			}
			int rMinY = LabelMatcher.minY(r);
			int rMaxY = LabelMatcher.maxY(r);
			// 放宽：y 与 label 有重叠 OR 完全在 label 下方 SCOPE_BELOW_MAX_LINES 行内
			int overlap = Math.min(rMaxY, labelMaxY) - Math.max(rMinY, labelMinY);
			int gap = rMinY - labelMaxY;
			boolean yOverlap = overlap > 0;
			boolean below = gap > 0 && gap < oneLine * SCOPE_BELOW_MAX_LINES;
			if (!yOverlap && !below) {
				continue;
			}
			candidates.add(r);
		}
		if (candidates.isEmpty()) {
			return null;
		}
		// 2. 按 y 升序 + x 升序排序
		candidates.sort(Comparator.comparingInt(LabelMatcher::minY).thenComparingInt(LabelMatcher::minX));

		// 3. 行内水平拼接 + 行间空格
		//    关键截断：遇到 y 间距 > SCOPE_LINE_GAP_FACTOR 倍行高时即视为到达下一个字段，停止。
		StringBuilder sb = new StringBuilder();
		int prevCenterY = Integer.MIN_VALUE;
		double lineThreshold = oneLine * SCOPE_LINE_GAP_FACTOR;
		for (PPOcrV6Result r : candidates) {
			int rCenterY = (LabelMatcher.minY(r) + LabelMatcher.maxY(r)) / 2;
			int rHeight = Math.max(1, LabelMatcher.maxY(r) - LabelMatcher.minY(r));
			if (isLineBreak(prevCenterY, rCenterY, lineThreshold)) {
				break;
			}
			boolean sameLine = isSameLine(prevCenterY, rCenterY, rHeight, oneLine);
			if (sb.length() == 0) {
				sb.append(r.text());
			} else if (sameLine) {
				// 同行紧邻：直接拼接（同视觉行的水平续段）
				sb.append(r.text());
			} else {
				// 换行：用空格分隔
				if (sb.charAt(sb.length() - 1) != ' ') {
					sb.append(' ');
				}
				sb.append(r.text());
			}
			prevCenterY = rCenterY;
		}
		String result = sb.toString().trim();
		return result.isEmpty() ? null : result;
	}

	/**
	 * 判定是否需要跳过（其它字段的独立 fragment / "名类..."/"型XXX"/"所XXX" 等合并 fragment）。
	 */
	private static boolean isScopeSkipFragment(String text) {
		if (SCOPE_SKIP_FRAGMENTS.contains(text)) {
			return true;
		}
		for (String frag : SCOPE_SKIP_FRAGMENTS) {
			if (text.startsWith(frag)
				&& (text.equals(frag) || text.length() - frag.length() <= SCOPE_FRAG_TAIL_TOLERANCE)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 候选段是否与上一段距离过远（视为到达下一个字段，需要截断）。
	 */
	private static boolean isLineBreak(int prevCenterY, int rCenterY, double lineThreshold) {
		return prevCenterY != Integer.MIN_VALUE && rCenterY - prevCenterY > lineThreshold;
	}

	/**
	 * 候选段是否与上一段处于同一视觉行（用两者行高 + 标签行高的最大值作容差，吸收 OCR 行高抖动）。
	 */
	private static boolean isSameLine(int prevCenterY, int rCenterY, int rHeight, int oneLine) {
		if (prevCenterY == Integer.MIN_VALUE) {
			return false;
		}
		return Math.abs(rCenterY - prevCenterY) < Math.max(rHeight, oneLine);
	}

	/**
	 * 给定 label 框（任意 fragment/独立标签），找右侧 y 重叠的首个值框。
	 *
	 * <p>与 LabelMatcher.matchValueByCenterWithBox 相同语义，但允许传入任意 fragment 框。
	 */
	private static LabeledMatch matchRightByLabelBox(PPOcrV6Result labelBox,
													 List<PPOcrV6Result> results,
													 Set<String> skipTexts) {
		if (labelBox == null) {
			return LabeledMatch.textOnly(null);
		}
		int labelCenterX = (LabelMatcher.minX(labelBox) + LabelMatcher.maxX(labelBox)) / 2;
		int labelMinY = LabelMatcher.minY(labelBox);
		int labelMaxY = LabelMatcher.maxY(labelBox);
		PPOcrV6Result best = null;
		int bestX = Integer.MAX_VALUE;
		for (PPOcrV6Result r : results) {
			if (r == labelBox) {
				continue;
			}
			String text = r.text();
			if (text.isEmpty()) {
				continue;
			}
			if (skipTexts != null && skipTexts.contains(text)) {
				continue;
			}
			// 纯字母 / 空白不当作中文值
			if (text.matches("[A-Za-z\\s]+")) {
				continue;
			}
			int x0 = LabelMatcher.minX(r);
			int rCenterX = (x0 + LabelMatcher.maxX(r)) / 2;
			if (rCenterX <= labelCenterX) {
				continue;
			}
			if (LabelMatcher.maxY(r) < labelMinY || LabelMatcher.minY(r) > labelMaxY) {
				continue;
			}
			if (x0 < bestX) {
				bestX = x0;
				best = r;
			}
		}
		return best == null ? LabeledMatch.textOnly(null) : LabeledMatch.of(best.text(), best);
	}

	@Override
	public BusinessLicenseResult parseResults(List<PPOcrV6Result> results) {
		return doParse(results);
	}

	// ========================================================================
	// 通用辅助
	// ========================================================================

	private BusinessLicenseResult doParse(List<PPOcrV6Result> results) {
		BusinessLicenseResult license = new BusinessLicenseResult();
		license.setRawResults(new ArrayList<>(results));

		// 1. 社会信用代码：标签 → "编号:XXX" 合并框 → 注册号兜底
		LabeledMatch creditMatch = parseCreditCode(results);
		license.setCreditCode(creditMatch.value());
		LabelMatcher.applyFieldBox(license, "creditCode", creditMatch);

		// 2. 单位名称
		LabeledMatch nameMatch = parseName(results);
		license.setName(nameMatch.value());
		LabelMatcher.applyFieldBox(license, "name", nameMatch);

		// 3. 类型
		LabeledMatch typeMatch = parseType(results);
		license.setType(typeMatch.value());
		LabelMatcher.applyFieldBox(license, "type", typeMatch);

		// 4. 法定代表人（含"负责人"别名）
		LabeledMatch legalMatch = parseLegalPerson(results);
		license.setLegalPerson(legalMatch.value());
		LabelMatcher.applyFieldBox(license, "legalPerson", legalMatch);

		// 5. 注册资本
		LabeledMatch capitalMatch = LabelMatcher.matchValueFromPrefixWithBox(results, LABEL_CAPITAL);
		license.setRegisteredCapital(capitalMatch.value());
		LabelMatcher.applyFieldBox(license, "registeredCapital", capitalMatch);

		// 6. 成立日期（优先合并框剥值，独立框兜底）
		LabeledMatch establishMatch = LabelMatcher.matchValueFromPrefixWithBox(results, LABEL_ESTABLISH_DATE);
		if (!establishMatch.hasValue()) {
			establishMatch = LabelMatcher.matchValueWithBox(results, LABEL_ESTABLISH_DATE);
		}
		license.setEstablishDate(repairEstablishDate(establishMatch.value()));
		LabelMatcher.applyFieldBox(license, "establishDate", establishMatch);

		// 7. 有效日期至
		LabeledMatch periodMatch = parseOperatingPeriod(results);
		license.setOperatingPeriod(periodMatch.value());
		LabelMatcher.applyFieldBox(license, "operatingPeriod", periodMatch);

		// 8. 住址（含"营业场所"别名）
		license.setAddress(parseAddress(results));

		// 9. 经营范围（跨多行）
		license.setBusinessScope(parseBusinessScope(results));

		// —— 正确性层：GB 32100 校验位 + 字段级置信度 ——
		// 统一社会信用代码校验不过 = 至少认错了一个字符，置信度直接归零并标记需复核。
		ValidationResult creditValidation = Validations.validateCreditCode(creditMatch.value());
		if (creditValidation.mismatched()) {
			log.warn("营业执照解析：{}", creditValidation.message());
		}
		license.putFieldConfidence(FieldConfidence.of("creditCode",
			LabelMatcher.toParsedField(creditMatch, FieldSource.LABEL), creditValidation));
		license.putFieldConfidence(FieldConfidence.of("name",
			LabelMatcher.toParsedField(nameMatch, FieldSource.LABEL), null));
		license.putFieldConfidence(FieldConfidence.of("type",
			LabelMatcher.toParsedField(typeMatch, FieldSource.LABEL), null));
		license.putFieldConfidence(FieldConfidence.of("legalPerson",
			LabelMatcher.toParsedField(legalMatch, FieldSource.LABEL), null));
		license.putFieldConfidence(FieldConfidence.of("registeredCapital",
			LabelMatcher.toParsedField(capitalMatch, FieldSource.LABEL), null));
		license.putFieldConfidence(FieldConfidence.of("establishDate",
			LabelMatcher.toParsedField(establishMatch, FieldSource.LABEL), null));
		license.putFieldConfidence(FieldConfidence.of("operatingPeriod",
			LabelMatcher.toParsedField(periodMatch, FieldSource.LABEL), null));
		license.putFieldConfidence(FieldConfidence.of("address",
			ParsedField.label(license.getAddress(), null), null));
		license.putFieldConfidence(FieldConfidence.of("businessScope",
			ParsedField.label(license.getBusinessScope(), null), null));

		return license;
	}

	/**
	 * 成立日期修复：分隔符被 OCR 弄乱时（如 "2020-0518"）按纯数字重排还原。
	 *
	 * <p>格式本来就正常时原样返回，不改变既有输出。
	 *
	 * @param rawDate 原始成立日期文本
	 * @return 修复后的日期；无法修复时返回原值
	 */
	private static String repairEstablishDate(String rawDate) {
		if (rawDate == null || rawDate.isEmpty()) {
			return rawDate;
		}
		if (DATE_PATTERN.matcher(rawDate).matches()) {
			return rawDate;
		}
		String repaired = DateRepair.repairDate(rawDate);
		if (repaired != null) {
			log.debug("营业执照解析：成立日期由 \"{}\" 修复为 \"{}\"", rawDate, repaired);
			return repaired;
		}
		return rawDate;
	}
}
