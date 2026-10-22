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

package net.dreamlu.mica.ai.ppocr.structured.parser.pdd;

import lombok.experimental.Accessors;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Engine;
import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Result;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.BaseStructuredParser;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.LabelMatcher;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.LabelMatcher.LabeledMatch;
import net.dreamlu.mica.ai.ppocr.utils.CollUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 拼多多福袋 OCR 结构化解析器。
 *
 * <p>目标：从「百亿补贴 抽福袋」分享图中提取 8 位数字福袋码（邀请码）。
 *
 * <p>典型版面：顶部推广语 → 步骤说明（"1 打开拼多多 APP / 2 搜索以下数字邀请码"）→
 * 中央大字号数字 → 背景重复水印"百亿补贴 福袋专享"。
 *
 * <p>策略概览：
 * <ol>
 *   <li><b>类型判定</b>：福袋有两种形态 —— 福袋券（现金 / 折扣券）与水果券，
 *       版式一致，按标题 / 口号 / 背景水印文案区分，见 {@link PddLuckyBagType}。</li>
 *   <li><b>标签定位</b>：在「数字邀请码 / 邀请码 / 搜索邀请码」标签下方 / 右侧
 *       找纯数字框（容忍 8 位左右，支持 "2 搜索以下数字邀请码 92463725"
 *       合并框剥前缀）。</li>
 *   <li><b>形态兜底</b>：标签没命中时，从所有 OCR 框中按"纯数字 6~12 位 +
 *       框面积最大 + y 偏下半"打分，优先取最像福袋码的候选。</li>
 *   <li><b>水印 / 步骤号过滤</b>：剔除含中文 / 字母的框（"百亿补贴 福袋专享"
 *       等水印）以及 1~2 位纯数字框（步骤序号 "1" "2"）。</li>
 * </ol>
 *
 * <p>输出结果会填充 {@code PddLuckyBagResult#getRawResults()} 与
 * {@code PddLuckyBagResult#getFieldBoxes()}（key: luckyBagCode、type），便于页面高亮。
 */
@Slf4j
public class PddLuckyBagParser extends BaseStructuredParser<PddLuckyBagResult> {

	/**
	 * 福袋码：6~12 位纯数字。拼多多官方福袋码实测 8~9 位，区间放宽到 6~12
	 * 以兼容历史变体 / OCR 漏字（"9246372" 漏 1 位）。
	 */
	private static final Pattern LUCKY_BAG_PATTERN = Pattern.compile("\\d{6,12}");

	/**
	 * 标签候选：按优先级排序。
	 */
	private static final List<String> LABEL_CANDIDATES = CollUtil.listOf(
		"数字邀请码",
		"搜索以下数字邀请码",
		"搜索以下邀请码",
		"邀请码",
		"搜索邀请码"
	);

	/**
	 * 水果券类型特征文案 → 权重。
	 *
	 * <p>权重越大越可信：标题（水果分享券）> 口号（必得水果券 / 抽水果券）> 水印（水果专享）。
	 */
	private static final Map<String, Integer> FRUIT_KEYWORDS = CollUtil.mapOfEntries(
		CollUtil.entry("水果分享券", 10),
		CollUtil.entry("必得水果", 8),
		CollUtil.entry("抽水果", 8),
		CollUtil.entry("水果券", 6),
		CollUtil.entry("水果专享", 4),
		CollUtil.entry("水果", 2)
	);

	/**
	 * 福袋券类型特征文案 → 权重。
	 *
	 * <p>权重越大越可信：标题（抽福袋）> 口号（必得现金或券 / 抽现金）> 水印（福袋专享）。
	 */
	private static final Map<String, Integer> LUCKY_BAG_KEYWORDS = CollUtil.mapOfEntries(
		CollUtil.entry("抽福袋", 10),
		CollUtil.entry("必得现金", 8),
		CollUtil.entry("抽现金", 8),
		CollUtil.entry("福袋券", 6),
		CollUtil.entry("福袋专享", 4),
		CollUtil.entry("福袋", 2)
	);

	/**
	 * 顶部区域占比：标题 + 口号所在的高度比例（按 OCR 内容高度计算）。
	 *
	 * <p>顶部区域文字大、识别准确，是判类型的主依据；底部重复水印只作兜底。
	 */
	private static final double TOP_REGION_RATIO = 0.4d;

	/**
	 * 构造拼多多福袋解析器，绑定推理引擎。
	 *
	 * @param engine PP-OCRv6 推理引擎；可为 null（仅在仅调用 {@link #parseResults(List)} 时）
	 */
	public PddLuckyBagParser(PPOcrV6Engine engine) {
		super(engine);
	}

	/**
	 * 标签定位：在已知 label 候选中找含 label 的合并框，剥出 label 后
	 * 提取其中的纯数字段；找不到合并框时回退到 label 右侧 / 下方纯数字框。
	 *
	 * <p>合并框匹配支持 label 出现在文本任意位置（不要求以 label 开头），
	 * 以覆盖「2 搜索以下数字邀请码 92463725」这类带步骤序号的合并框。
	 * 多个 label 同时命中时优先取最长的 label（语义更具体）。
	 */
	private static LabeledMatch matchByLabel(List<PPOcrV6Result> results) {
		// a) 合并框：扫所有框，命中含 label 的文本，从 label 末尾开始切纯数字
		PPOcrV6Result bestBox = null;
		String bestCode = null;
		int bestLabelLen = -1;
		String bestLabel = null;
		for (PPOcrV6Result r : results) {
			String text = r.text();
			if (text == null || text.isEmpty()) continue;
			for (String label : LABEL_CANDIDATES) {
				int idx = text.indexOf(label);
				if (idx < 0) continue;
				int after = idx + label.length();
				if (after >= text.length()) continue;
				String rest = text.substring(after);
				String code = extractPureDigits(rest);
				if (code == null) continue;
				// 优先取最长 label 命中（语义最具体）
				if (label.length() > bestLabelLen) {
					bestLabelLen = label.length();
					bestLabel = label;
					bestBox = r;
					bestCode = code;
				}
			}
		}
		if (bestBox != null) {
			log.debug("拼多多福袋解析：标签 \"{}\" 合并框切值 \"{}\"", bestLabel, bestCode);
			return LabeledMatch.of(bestCode, bestBox);
		}
		// b) 独立标签框：label 右侧 / 下方纯数字框
		for (String label : LABEL_CANDIDATES) {
			LabeledMatch m = LabelMatcher.matchValueWithBox(results, label);
			if (m.hasValue() && !m.matches().isEmpty()) {
				PPOcrV6Result valBox = m.matches().get(0);
				String code = extractPureDigits(valBox.text());
				if (code != null) {
					log.debug("拼多多福袋解析：标签 \"{}\" 右侧命中 \"{}\"", label, code);
					return LabeledMatch.of(code, valBox);
				}
			}
		}
		return LabeledMatch.textOnly(null);
	}

	/**
	 * 形态兜底：从所有 OCR 框中按"纯数字 6~12 位 + 面积最大 + y 偏下半"打分。
	 *
	 * <p>排除项：
	 * <ul>
	 *   <li>含中文 / 字母的框（水印"百亿补贴福袋专享"、步骤"打开拼多多APP"等）；</li>
	 *   <li>长度 1~5 位的纯数字（步骤序号 "1" "2"、折扣 "5"）；</li>
	 *   <li>长度 > 12 位的纯数字（合并框碎片）。</li>
	 * </ul>
	 */
	private static LabeledMatch matchByShape(List<PPOcrV6Result> results) {
		// 全图 y 中位线：福袋码在图片中下方
		int imgMaxY = 0;
		for (PPOcrV6Result r : results) {
			imgMaxY = Math.max(imgMaxY, LabelMatcher.maxY(r));
		}
		int lowerHalfY = imgMaxY / 2;

		PPOcrV6Result best = null;
		long bestScore = Long.MIN_VALUE;
		String bestCode = null;
		for (PPOcrV6Result r : results) {
			String text = r.text();
			if (text == null) continue;
			String code = extractPureDigits(text);
			if (code == null) continue;
			if (code.length() < 6 || code.length() > 12) continue;
			// 排除含非数字字符的原文（避免"日"或"¥5"等被误识别为数字）
			if (!text.matches("[\\d\\s]+")) continue;

			// 综合打分：面积（宽 * 高）为主要权重；y 偏下半（越靠下分越高）做微调。
			int w = LabelMatcher.maxX(r) - LabelMatcher.minX(r);
			int h = LabelMatcher.maxY(r) - LabelMatcher.minY(r);
			long area = (long) w * h;
			int yMid = (LabelMatcher.minY(r) + LabelMatcher.maxY(r)) / 2;
			long score = area * 10L + Math.max(0, yMid - lowerHalfY);
			if (score > bestScore) {
				bestScore = score;
				best = r;
				bestCode = code;
			}
		}
		if (best == null) {
			return LabeledMatch.textOnly(null);
		}
		log.debug("拼多多福袋解析：形态兜底命中 \"{}\" (面积 score={})", bestCode, bestScore);
		return LabeledMatch.of(bestCode, best);
	}

	/**
	 * 从文本中提取最长纯数字段（≥6 位）。
	 *
	 * @param text 原始文本
	 * @return 最长纯数字段；无 ≥6 位数字段时返回 null
	 */
	private static String extractPureDigits(String text) {
		if (text == null || text.isEmpty()) {
			return null;
		}
		Matcher m = LUCKY_BAG_PATTERN.matcher(text);
		String best = null;
		while (m.find()) {
			String g = m.group();
			if (best == null || g.length() > best.length()) {
				best = g;
			}
		}
		return best;
	}

	/**
	 * 类型判定：区分「福袋券（现金 / 折扣券）」与「水果券」。
	 *
	 * <p>两类分享图版式一致，只能靠文案区分：
	 * <ul>
	 *   <li>福袋券 —— 标题「抽福袋」、口号「必得现金或券」、水印「百亿补贴 福袋专享」；</li>
	 *   <li>水果券 —— 标题「水果分享券」、口号「必得水果券」、水印「百亿补贴 水果专享」。</li>
	 * </ul>
	 *
	 * <p>判定规则：
	 * <ol>
	 *   <li>按关键字权重给每类打分（同一框只取权重最高的命中，避免单个框多关键字重复计分）；</li>
	 *   <li>顶部区域（标题 + 口号）有且只有一类命中时，直接判定为该类；
	 *       顶部区域两类都没命中时，退化为全图总分比较（水印兜底）；</li>
	 *   <li>两类都命中且分数无法分出高下时，判为 {@link PddLuckyBagType#UNKNOWN}。</li>
	 * </ol>
	 *
	 * @param results OCR 识别结果列表
	 * @return 类型判定结果（含决定性 OCR 框，用于 fieldBoxes 高亮）
	 */
	private static TypeMatch detectType(List<PPOcrV6Result> results) {
		// 以 OCR 内容高度代替图片高度（图片底部常无文字框）
		int contentMaxY = 0;
		for (PPOcrV6Result r : results) {
			contentMaxY = Math.max(contentMaxY, LabelMatcher.maxY(r));
		}
		int topLimit = (int) (contentMaxY * TOP_REGION_RATIO);

		TypeScore fruit = scoreType(results, FRUIT_KEYWORDS, topLimit);
		TypeScore lucky = scoreType(results, LUCKY_BAG_KEYWORDS, topLimit);

		if (fruit.topScore() > 0 && lucky.topScore() == 0) {
			return new TypeMatch(PddLuckyBagType.FRUIT, fruit.box());
		}
		if (lucky.topScore() > 0 && fruit.topScore() == 0) {
			return new TypeMatch(PddLuckyBagType.LUCKY_BAG, lucky.box());
		}
		if (fruit.score() > lucky.score()) {
			return new TypeMatch(PddLuckyBagType.FRUIT, fruit.box());
		}
		if (lucky.score() > fruit.score()) {
			return new TypeMatch(PddLuckyBagType.LUCKY_BAG, lucky.box());
		}
		log.warn("拼多多福袋解析：未能判定福袋类型（水果分 {} / 福袋分 {}）", fruit.score(), lucky.score());
		return new TypeMatch(PddLuckyBagType.UNKNOWN, null);
	}

	/**
	 * 对单个类型做关键字打分。
	 *
	 * @param results  OCR 识别结果列表
	 * @param keywords 特征文案 → 权重
	 * @param topLimit 顶部区域的下边界（y）
	 * @return 打分结果（总分 / 顶部区域分 / 最强命中框）
	 */
	private static TypeScore scoreType(List<PPOcrV6Result> results,
									   Map<String, Integer> keywords,
									   int topLimit) {
		int score = 0;
		int topScore = 0;
		int bestWeight = 0;
		int bestTopWeight = 0;
		PPOcrV6Result bestBox = null;
		PPOcrV6Result bestTopBox = null;
		for (PPOcrV6Result r : results) {
			String text = r.text();
			if (text == null || text.isEmpty()) continue;
			int weight = keywordWeight(text, keywords);
			if (weight <= 0) continue;
			score += weight;
			boolean top = (LabelMatcher.minY(r) + LabelMatcher.maxY(r)) / 2 <= topLimit;
			if (top) {
				topScore += weight;
				if (weight > bestTopWeight) {
					bestTopWeight = weight;
					bestTopBox = r;
				}
			}
			if (weight > bestWeight) {
				bestWeight = weight;
				bestBox = r;
			}
		}
		// 优先用顶部区域（标题 / 口号）命中的框作为决定性框
		return new TypeScore(score, topScore, bestTopBox != null ? bestTopBox : bestBox);
	}

	/**
	 * 取文本命中的最高关键字权重。
	 *
	 * @param text     OCR 文本
	 * @param keywords 关键字 → 权重
	 * @return 最高权重；未命中返回 0
	 */
	private static int keywordWeight(String text, Map<String, Integer> keywords) {
		int best = 0;
		for (Map.Entry<String, Integer> entry : keywords.entrySet()) {
			if (text.contains(entry.getKey()) && entry.getValue() > best) {
				best = entry.getValue();
			}
		}
		return best;
	}

	/**
	 * 类型判定结果：类型 + 决定性 OCR 框。
	 */
	@Value
	@Accessors(fluent = true)
	private static class TypeMatch {
		/**
		 * 判定出的类型（无法判定时为 {@link PddLuckyBagType#UNKNOWN}）。
		 */
		PddLuckyBagType type;
		/**
		 * 决定性 OCR 框；无法判定时为 null。
		 */
		PPOcrV6Result box;
	}

	/**
	 * 单个类型的打分结果。
	 */
	@Value
	@Accessors(fluent = true)
	private static class TypeScore {
		/**
		 * 全图总分。
		 */
		int score;
		/**
		 * 顶部区域（标题 + 口号）得分。
		 */
		int topScore;
		/**
		 * 最强命中的 OCR 框（优先取顶部区域命中的框）。
		 */
		PPOcrV6Result box;
	}

	@Override
	public PddLuckyBagResult parseResults(List<PPOcrV6Result> results) {
		PddLuckyBagResult r = new PddLuckyBagResult();
		r.setRawResults(new ArrayList<>(results));

		// 1) 类型判定：福袋券（现金 / 券）/ 水果券
		TypeMatch typeMatch = detectType(results);
		r.setType(typeMatch.type());
		if (typeMatch.box() != null) {
			LabelMatcher.applyFieldBox(r, "type",
				LabeledMatch.of(typeMatch.type().name(), typeMatch.box()));
		}
		log.debug("拼多多福袋解析：类型 {} （决定性文案 \"{}\"）",
			typeMatch.type(),
			typeMatch.box() == null ? "" : typeMatch.box().text());

		// 2) 标签定位：数字邀请码 / 邀请码 / 搜索邀请码
		LabeledMatch match = matchByLabel(results);
		// 3) 形态兜底：纯数字 + 面积最大 + y 偏下半
		if (!match.hasValue()) {
			match = matchByShape(results);
		}
		if (match.hasValue()) {
			r.setLuckyBagCode(match.value());
			LabelMatcher.applyFieldBox(r, "luckyBagCode", match);
		} else {
			log.warn("拼多多福袋解析：未匹配到福袋码");
		}
		return r;
	}
}
