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

package net.dreamlu.mica.ai.ppocr.structured.parser.core;

import lombok.experimental.Accessors;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Result;
import net.dreamlu.mica.ai.ppocr.utils.CollUtil;

import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * 结构化解析公共工具：标签定位 + 位置匹配 + 正则兜底。
 *
 * <p>适用于左侧标签 + 右侧值 的证件版面（如行驶证、身份证、驾照、营业执照等）：
 * 找到标签框后，在 x 起点位于标签右边缘右侧、y 范围与标签框重叠的候选值框中，
 * 取最靠左（x 最小）的文本作为字段值。
 *
 * <p>支持 OCR 残缺标签匹配：优先取文本包含完整标签的框，没有时退而取标签包含
 * 其文本的框（如"所有人"被识别成"所"）。
 *
 * <p><b>两种返回风格：</b>
 * <ul>
 *   <li>{@code matchValue(...) -> String} —— 只返回文本，兼容老代码；</li>
 *   <li>{@code matchValueWithBox(...) -> LabeledMatch} —— 返回文本 + 匹配到的
 *       {@link PPOcrV6Result}（含 box 坐标），供解析器填充
 *       {@code BaseStructuredResult#getFieldBoxes()}，便于页面复原。</li>
 * </ul>
 *
 * <p>本工具类只做"骨架"，不绑定具体业务字段；具体解析器在
 * {@link BaseStructuredParser} 中组合本工具完成结构化输出。
 */
@Slf4j
@UtilityClass
public class LabelMatcher {

	/**
	 * 值框与标签框允许的横向重叠容差（像素），用于容忍边界 1px 相接
	 * （如"发证日期"标签与值框共用 x=2063）。
	 */
	public static final int DEFAULT_RIGHT_OVERLAP_TOLERANCE = 5;

	/**
	 * 兼容老代码：取字段文本，不返回匹配框。
	 *
	 * @param results OCR 识别结果列表
	 * @param label   字段标签（如 "号牌号码"）
	 * @return 字段值；未匹配到时返回 null
	 */
	public static String matchValue(List<PPOcrV6Result> results, String label) {
		return matchValueWithBox(results, label).value();
	}

	// ==================================================================
	// 无 box 版（兼容老代码）
	// ==================================================================

	/**
	 * 兼容老代码：取字段文本，容忍标签与值框横向重叠像素。
	 *
	 * @param results               OCR 识别结果列表
	 * @param label                 字段标签
	 * @param rightOverlapTolerance 横向重叠容差（像素）
	 * @return 字段值；未匹配到时返回 null
	 */
	public static String matchValue(List<PPOcrV6Result> results, String label, int rightOverlapTolerance) {
		return matchValueWithBox(results, label, rightOverlapTolerance).value();
	}

	/**
	 * 兼容老代码：标签和值在同一 OCR 框里时，从合并框剥出值。
	 *
	 * @param results OCR 识别结果列表
	 * @param label   字段标签
	 * @return 字段值；未匹配到时返回 null
	 */
	public static String matchValueFromPrefix(List<PPOcrV6Result> results, String label) {
		return matchValueFromPrefixWithBox(results, label).value();
	}

	/**
	 * 兼容老代码：用提取器在所有 OCR 文本上试匹配，返回首个非空结果。
	 *
	 * @param results   OCR 识别结果列表
	 * @param extractor 文本→值的提取函数
	 * @return 提取结果；无匹配时返回 null
	 */
	public static String matchSubstring(List<PPOcrV6Result> results,
										Function<String, String> extractor) {
		return matchSubstringWithBox(results, extractor).value();
	}

	/**
	 * 兼容老代码：标签匹配优先，否则按正则兜底。
	 *
	 * @param labelValue 标签位置匹配得到的值（可为 null）
	 * @param results    OCR 识别结果列表
	 * @param pattern    兜底正则
	 * @param fieldName  字段名（日志用）
	 * @param last       true=取最后一个匹配；false=取首个匹配
	 * @return 最终字段值；无匹配时返回 null
	 */
	public static String labelOrFallback(String labelValue,
										 List<PPOcrV6Result> results,
										 Pattern pattern,
										 String fieldName,
										 boolean last) {
		LabeledMatch lm = labelOrFallbackWithBox(LabeledMatch.textOnly(labelValue), results, pattern, fieldName, last);
		return lm.value();
	}

	/**
	 * 取字段值 + 匹配框，使用默认横向重叠容差 {@link #DEFAULT_RIGHT_OVERLAP_TOLERANCE}。
	 *
	 * @param results OCR 识别结果列表
	 * @param label   字段标签
	 * @return 字段值 + 值框
	 */
	public static LabeledMatch matchValueWithBox(List<PPOcrV6Result> results, String label) {
		return matchValueWithBox(results, label, DEFAULT_RIGHT_OVERLAP_TOLERANCE);
	}

	// ==================================================================
	// 带 box 版（推荐新代码使用，便于 fieldBoxes 回填）
	// ==================================================================

	/**
	 * 取字段值 + 匹配框，自定义横向重叠容差。
	 *
	 * @param results               OCR 识别结果列表
	 * @param label                 字段标签
	 * @param rightOverlapTolerance 横向重叠容差（像素）
	 * @return 字段值 + 值框
	 */
	public static LabeledMatch matchValueWithBox(List<PPOcrV6Result> results, String label, int rightOverlapTolerance) {
		return matchValueByCenterWithBox(results, label);
	}

	/**
	 * 按"标签右侧 + y 重叠 + 最左"策略取字段值 + 匹配框。
	 *
	 * @param results OCR 识别结果列表
	 * @param label   字段标签
	 * @return 字段值 + 值框；未匹配到时返回仅含 null value 的 LabeledMatch
	 */
	public static LabeledMatch matchValueByCenterWithBox(List<PPOcrV6Result> results, String label) {
		PPOcrV6Result labelBox = findLabelBox(results, label);
		if (labelBox == null) {
			log.warn("结构化解析：未找到标签 \"{}\"，该字段置 null", label);
			return LabeledMatch.textOnly(null);
		}

		String labelText = labelBox.text();

		// 合并框场景：返回 null 让 matchValueFromPrefix 兜底
		if (labelText.startsWith(label) && labelText.length() > label.length()) {
			return LabeledMatch.textOnly(null);
		}

		int labelCenterX = (minX(labelBox) + maxX(labelBox)) / 2;
		int labelMinY = minY(labelBox);
		int labelMaxY = maxY(labelBox);
		int labelCenterY = (labelMinY + labelMaxY) / 2;
		int labelMaxX = maxX(labelBox);

		PPOcrV6Result best = null;
		int bestScore = Integer.MAX_VALUE;
		for (PPOcrV6Result r : results) {
			if (r == labelBox) continue;
			String text = r.text();
			if (text.matches("[A-Za-z\\s]+")) continue;
			if (!text.equals(label) && text.length() < label.length() && label.contains(text)) continue;
			int x0 = minX(r);
			int rCenterX = (x0 + maxX(r)) / 2;
			// 容忍 2px 中心 x 偏差：旋转卡片中"值框正下方"或"值框紧贴 label 左边"时 rCenterX 可能
			// 略小于 labelCenterX（如"性别"label 与"男民族汉"值框同 x 范围、上下堆叠）。
			if (rCenterX < labelCenterX - 2) continue;
			if (maxY(r) < labelMinY || minY(r) > labelMaxY) continue;
			// 综合打分：x 距离 label 边为主权重（值框通常紧邻 label 右侧同行），
			// y 中心偏离为次要打破平局。
			// 用 |dx| 对称处理：旋转卡片中"值框紧贴 label 下方"时 dx 可能是负数，
			// 直接用 dx*10 会得到很低的负分抢走本应选中的右侧候选。
			// 之前 dy*100+dx 在 dy=0 时（即 y 中心与 label 完全相同，如"姓名"label 与"性别"label 同行）
			// 会让远处的"性别"label 框因 dx 较大但 dy=0 而胜出。
			int dy = Math.abs((minY(r) + maxY(r)) / 2 - labelCenterY);
			int dx = Math.abs(x0 - labelMaxX);
			int score = dx * 10 + dy;
			if (score < bestScore) {
				bestScore = score;
				best = r;
			}
		}
		if (best == null) {
			log.warn("结构化解析：标签 \"{}\" 未匹配到值框，该字段置 null", label);
			return LabeledMatch.textOnly(null);
		}
		return LabeledMatch.of(best.text(), best);
	}

	/**
	 * 在 {@link #matchValueByCenterWithBox} 失败时，从"以 label 开头的合并框"剥出值。
	 *
	 * @param results OCR 识别结果列表
	 * @param label   字段标签
	 * @return 字段值 + 值框；未匹配到时返回仅含 null value 的 LabeledMatch
	 */
	public static LabeledMatch matchValueFromPrefixWithBox(List<PPOcrV6Result> results, String label) {
		LabeledMatch m = matchValueByCenterWithBox(results, label);
		if (m.hasValue()) return m;
		for (PPOcrV6Result r : results) {
			String text = r.text();
			if (text.startsWith(label) && text.length() > label.length()) {
				String stripped = text.substring(label.length());
				// 合并框"发票代码：041002000112" → 冒号是标签与值的分隔符,
				// 须继续剥离前导标点,否则下游正则(^\d{8,12}$)无法命中。
				int s = 0;
				while (s < stripped.length() && isLabelPunct(stripped.charAt(s))) s++;
				stripped = stripped.substring(s);
				if (stripped.trim().isEmpty()) continue;
				log.debug("结构化解析：标签 \"{}\" 从合并框 \"{}\" 剥出值 \"{}\"", label, text, stripped);
				return LabeledMatch.of(stripped, r);
			}
		}
		return LabeledMatch.textOnly(null);
	}

	/**
	 * 判定字符是否属于"标签-值"分隔标点：合并框中标签与值之间常带的符号。
	 */
	private static boolean isLabelPunct(char c) {
		return c == ':' || c == '：' || c == '、' || c == ' '
			|| c == ',' || c == '，' || c == ';' || c == '；';
	}

	/**
	 * 用正则匹配 OCR 文本，返回首个/最后一个匹配项。
	 *
	 * @param results OCR 识别结果列表
	 * @param pattern 文本匹配正则
	 * @param last    true=取最后一个匹配；false=取首个匹配
	 * @return 字段值 + 值框；未匹配到时返回仅含 null value 的 LabeledMatch
	 */
	public static LabeledMatch matchPatternWithBox(List<PPOcrV6Result> results, Pattern pattern, boolean last) {
		PPOcrV6Result hit = null;
		for (PPOcrV6Result r : results) {
			if (pattern.matcher(r.text()).matches()) {
				hit = r;
				if (!last) break;
			}
		}
		return hit == null ? LabeledMatch.textOnly(null) : LabeledMatch.of(hit.text(), hit);
	}

	/**
	 * 兼容老代码：用正则在 OCR 文本上匹配，返回首个/最后一个命中文本。
	 *
	 * @param results OCR 识别结果列表
	 * @param pattern 文本匹配正则
	 * @param last    true=取最后一个匹配；false=取首个匹配
	 * @return 命中文本；无匹配时返回 null
	 */
	public static String matchPattern(List<PPOcrV6Result> results, Pattern pattern, boolean last) {
		return matchPatternWithBox(results, pattern, last).value();
	}

	/**
	 * 用 Predicate 在 OCR 文本上筛选，返回首个/最后一个命中文本。
	 *
	 * @param results   OCR 识别结果列表
	 * @param predicate 文本命中判断
	 * @param last      true=取最后一个命中；false=取首个命中
	 * @return 命中文本；无匹配时返回 null
	 */
	public static String matchPattern(List<PPOcrV6Result> results, Predicate<String> predicate, boolean last) {
		String hit = null;
		for (PPOcrV6Result r : results) {
			String text = r.text();
			if (predicate.test(text)) {
				hit = text;
				if (!last) break;
			}
		}
		return hit;
	}

	/**
	 * 用提取器在所有 OCR 文本上试匹配，返回首个非空提取结果。
	 *
	 * @param results   OCR 识别结果列表
	 * @param extractor 文本→值的提取函数
	 * @return 提取结果 + 命中的值框；无匹配时返回仅含 null value 的 LabeledMatch
	 */
	public static LabeledMatch matchSubstringWithBox(List<PPOcrV6Result> results,
													 Function<String, String> extractor) {
		for (PPOcrV6Result r : results) {
			String hit = extractor.apply(r.text());
			if (hit != null) {
				return LabeledMatch.of(hit, r);
			}
		}
		return LabeledMatch.textOnly(null);
	}

	/**
	 * 标签位置匹配优先，若 value 缺失或不匹配正则则按正则兜底。
	 *
	 * @param labelMatch 标签位置匹配结果（可能 value 为 null）
	 * @param results    OCR 识别结果列表（兜底时遍历）
	 * @param pattern    兜底正则
	 * @param fieldName  字段名（日志用）
	 * @param last       true=兜底时取最后一个匹配；false=取首个匹配
	 * @return 最终 LabeledMatch
	 */
	public static LabeledMatch labelOrFallbackWithBox(LabeledMatch labelMatch,
													  List<PPOcrV6Result> results,
													  Pattern pattern,
													  String fieldName,
													  boolean last) {
		if (labelMatch.hasValue()) {
			if (pattern.matcher(labelMatch.value()).matches()) {
				return labelMatch;
			}
			log.warn("结构化解析：{} 位置匹配 \"{}\" 格式异常，改走正则兜底", fieldName, labelMatch.value());
		}
		LabeledMatch fallback = matchPatternWithBox(results, pattern, last);
		if (fallback.hasValue()) {
			log.debug("结构化解析：{} 正则兜底命中 \"{}\"", fieldName, fallback.value());
		}
		return fallback;
	}

	/**
	 * 辅助：把 {@link LabeledMatch} 回填到结构化结果的 fieldBoxes 中。
	 *
	 * @param result    结构化结果对象
	 * @param fieldName 字段名（如 "plateNo"）
	 * @param match     字段匹配结果（含值框列表）
	 */
	public static void applyFieldBox(BaseStructuredResult result, String fieldName, LabeledMatch match) {
		if (result == null || fieldName == null || match == null || match.matches().isEmpty()) {
			return;
		}
		List<int[][]> boxes = new ArrayList<>(match.matches().size());
		for (PPOcrV6Result r : match.matches()) {
			if (r != null && r.box() != null) {
				boxes.add(r.box());
			}
		}
		if (!boxes.isEmpty()) {
			result.getFieldBoxes().put(fieldName, boxes);
		}
	}

	/**
	 * 在 OCR 结果中定位字段标签框，兼容 OCR 残缺场景。
	 *
	 * <p>匹配优先级：完整等于 &gt; 以 label 开头（最长）&gt; label 包含文本（最长）；
	 * 前两者未命中时回退到第三种并打印 DEBUG 日志。
	 *
	 * @param results OCR 识别结果列表
	 * @param label   字段标签（如 "号牌号码"）
	 * @return 标签框对应的 OCR 结果；无匹配时返回 null
	 */
	public static PPOcrV6Result findLabelBox(List<PPOcrV6Result> results, String label) {
		PPOcrV6Result exactBest = null;
		PPOcrV6Result prefixBest = null;
		PPOcrV6Result fragmentBest = null;
		int prefixBestLen = -1;
		int fragmentBestLen = -1;
		for (PPOcrV6Result r : results) {
			String text = r.text();
			if (text.isEmpty()) continue;
			if (text.equals(label)) {
				exactBest = r;
			} else if (text.startsWith(label)) {
				if (text.length() > prefixBestLen) {
					prefixBestLen = text.length();
					prefixBest = r;
				}
			} else if (label.contains(text)) {
				if (text.length() > fragmentBestLen) {
					fragmentBestLen = text.length();
					fragmentBest = r;
				}
			}
		}
		if (exactBest != null) return exactBest;
		if (prefixBest != null) return prefixBest;
		if (fragmentBest != null) {
			log.debug("[DEBUG-FIND] label='{}' fragment hit: text='{}' (fragment len={})", label, fragmentBest.text(), fragmentBestLen);
		}
		return fragmentBest;
	}

	// ==================================================================
	// 其余公开方法（不变）
	// ==================================================================

	/**
	 * 字段标签框定位的"干净版"：在 {@link #findLabelBox} 基础上拒绝被其他已知字段关键字
	 * 污染的 fragment（如营业执照 OCR 把"名称"和"类型"合并成"名类"——返回 null 而不是
	 * 错误命中"名"/"类" fragment）。
	 *
	 * <p>判定规则：
	 * <ol>
	 *   <li>完整等于 label → 接受；</li>
	 *   <li>以 label 开头 → 接受；</li>
	 *   <li>label 包含 text 且 text 长度 = 1（单字 fragment "名"/"称"/"类"/"型"/"住"/"所"）→ 接受；</li>
	 *   <li>label 包含 text 但 text 长度 ≥ 2 → 拒绝（噪声合并框，应由调用方做合并框剥值）。</li>
	 * </ol>
	 *
	 * @param results     OCR 识别结果列表
	 * @param label       字段标签（如 "住所"）
	 * @param noiseLabels 其他已知字段标签集合（如 ["名称","类型","注册资本",...])，
	 *                    fragment 文本如果包含其中任一标签视为污染并拒绝
	 * @return 干净标签框；无匹配时返回 null
	 */
	public static PPOcrV6Result findCleanLabelBox(List<PPOcrV6Result> results,
												  String label,
												  Set<String> noiseLabels) {
		PPOcrV6Result exactBest = null;
		PPOcrV6Result prefixBest = null;
		PPOcrV6Result fragmentBest = null;
		int prefixBestLen = -1;
		int fragmentBestLen = -1;
		for (PPOcrV6Result r : results) {
			String text = r.text();
			if (text.isEmpty()) continue;
			if (text.equals(label)) {
				exactBest = r;
			} else if (text.startsWith(label)) {
				if (text.length() > prefixBestLen) {
					prefixBestLen = text.length();
					prefixBest = r;
				}
			} else if (label.contains(text)) {
				// 拒绝被其他字段标签关键字污染的 fragment
				if (noiseLabels != null) {
					boolean polluted = false;
					for (String noise : noiseLabels) {
						if (!noise.equals(label) && text.contains(noise)) {
							polluted = true;
							break;
						}
					}
					if (polluted) continue;
				}
				// fragment 长度 ≥ 2 且非单字 fragment → 拒绝（视为合并框，由调用方剥值）
				if (text.length() >= 2) continue;
				if (text.length() > fragmentBestLen) {
					fragmentBestLen = text.length();
					fragmentBest = r;
				}
			}
		}
		if (exactBest != null) return exactBest;
		if (prefixBest != null) return prefixBest;
		if (fragmentBest != null) {
			log.debug("[DEBUG-FIND-CLEAN] label='{}' fragment hit: text='{}'", label, fragmentBest.text());
		}
		return fragmentBest;
	}

	/**
	 * 取标签右侧 y 重叠的所有候选框，按 y 升序拼接成多行值。
	 *
	 * <p>适用于经营范围 / 住所 / 营业期限等跨多行字段。规则：
	 * <ul>
	 *   <li>值框中心 x &gt; 标签中心 x；</li>
	 *   <li>值框 y 与标签 y 有重叠（允许下方延伸一行）；</li>
	 *   <li>拼接前按 y 升序排序，多行用空格分隔。</li>
	 * </ul>
	 *
	 * @param labelBox  标签框
	 * @param results   OCR 结果列表
	 * @param skipTexts 需要排除的文本（防止把其他标签 fragment 拼进来）
	 * @return 多行拼接值；无候选时返回 null
	 */
	public static String collectMultiLineRight(PPOcrV6Result labelBox,
											   List<PPOcrV6Result> results,
											   Set<String> skipTexts) {
		if (labelBox == null) return null;
		int labelCenterX = (minX(labelBox) + maxX(labelBox)) / 2;
		int labelMinY = minY(labelBox);
		int labelMaxY = maxY(labelBox);
		List<PPOcrV6Result> candidates = new ArrayList<>();
		for (PPOcrV6Result r : results) {
			if (r == labelBox) continue;
			String text = r.text();
			if (text.isEmpty()) continue;
			if (skipTexts != null && skipTexts.contains(text)) continue;
			int x0 = minX(r);
			int rCenterX = (x0 + maxX(r)) / 2;
			if (rCenterX <= labelCenterX) continue;
			int rMinY = minY(r);
			int rMaxY = maxY(r);
			// y 重叠 + 下方允许延伸一行
			int oneLine = labelMaxY - labelMinY;
			if (rMaxY < labelMinY || rMinY > labelMaxY + oneLine) continue;
			candidates.add(r);
		}
		candidates.sort(Comparator.comparingInt(LabelMatcher::minY));
		StringBuilder sb = new StringBuilder();
		for (PPOcrV6Result r : candidates) {
			if (sb.length() > 0) {
				sb.append(' ');
			}
			sb.append(r.text());
		}
		String result = sb.toString().trim();
		return result.isEmpty() ? null : result;
	}

	/**
	 * 取 OCR 框四点的最小 x 坐标。
	 *
	 * @param r OCR 识别结果
	 * @return 最小 x
	 */
	public static int minX(PPOcrV6Result r) {
		int min = Integer.MAX_VALUE;
		for (int[] p : r.box()) min = Math.min(min, p[0]);
		return min;
	}

	/**
	 * 取 OCR 框四点的最大 x 坐标。
	 *
	 * @param r OCR 识别结果
	 * @return 最大 x
	 */
	public static int maxX(PPOcrV6Result r) {
		int max = Integer.MIN_VALUE;
		for (int[] p : r.box()) max = Math.max(max, p[0]);
		return max;
	}

	/**
	 * 取 OCR 框四点的最小 y 坐标。
	 *
	 * @param r OCR 识别结果
	 * @return 最小 y
	 */
	public static int minY(PPOcrV6Result r) {
		int min = Integer.MAX_VALUE;
		for (int[] p : r.box()) min = Math.min(min, p[1]);
		return min;
	}

	/**
	 * 取 OCR 框四点的最大 y 坐标。
	 *
	 * @param r OCR 识别结果
	 * @return 最大 y
	 */
	public static int maxY(PPOcrV6Result r) {
		int max = Integer.MIN_VALUE;
		for (int[] p : r.box()) max = Math.max(max, p[1]);
		return max;
	}

	/**
	 * 找所有文本含任一 keyword 的 OCR 框。
	 *
	 * <p>典型场景：标签被 OCR 切碎成 fragment（如"上车"独立成一框），
	 * 仍可用此方法在所有框中按 fragment 定位。
	 *
	 * @param results  OCR 识别结果列表
	 * @param keywords 关键字列表（任一命中即返回）
	 * @return 命中的 OCR 框列表（保持原顺序）
	 */
	public static List<PPOcrV6Result> findBoxesByKeyword(List<PPOcrV6Result> results, String... keywords) {
		if (keywords == null || keywords.length == 0) return CollUtil.listOf();
		List<PPOcrV6Result> hits = new ArrayList<>();
		for (PPOcrV6Result r : results) {
			String text = r.text();
			if (text == null || text.isEmpty()) continue;
			for (String kw : keywords) {
				if (kw == null || kw.isEmpty()) continue;
				if (text.contains(kw)) {
					hits.add(r);
					break;
				}
			}
		}
		return hits;
	}

	// ==================================================================
	// 增强方法：合并框剥值 + 关键字定位 + 几何兜底
	//   针对真实票据 OCR 输出"标签被吞、标签与值合并、值与单价值
	//   争抢同一右侧位置"等场景。
	// ==================================================================

	/**
	 * 找含任一 keyword 的 OCR 框，再按 valueExtractor 从框文本里切值。
	 * 返回首个非空提取结果。
	 *
	 * <p>典型场景：OCR 把"上车K0000&gt;21:17"识别成单框，label 找不到，
	 * 但"上车"作为 fragment 命中此框；用 {@code text -> extractTime(text)} 从
	 * 中切出"21:17"。
	 *
	 * @param results        OCR 识别结果列表
	 * @param keywords       关键字列表（任一命中即视为候选框）
	 * @param valueExtractor 从候选框文本提取字段值的函数；返回 null 表示未切出
	 * @return 字段值 + 值框；未匹配到时返回仅含 null value 的 LabeledMatch
	 */
	public static LabeledMatch matchValueByKeywordWithBox(List<PPOcrV6Result> results,
														  List<String> keywords,
														  Function<String, String> valueExtractor) {
		if (keywords == null || keywords.isEmpty()) return LabeledMatch.textOnly(null);
		for (PPOcrV6Result r : results) {
			String text = r.text();
			if (text == null || text.isEmpty()) continue;
			boolean hit = false;
			for (String kw : keywords) {
				if (kw != null && !kw.isEmpty() && text.contains(kw)) {
					hit = true;
					break;
				}
			}
			if (!hit) continue;
			String value = valueExtractor.apply(text);
			if (value != null) {
				log.debug("结构化解析：keyword {} 命中框 \"{}\" 切出值 \"{}\"", keywords, text, value);
				return LabeledMatch.of(value, r);
			}
		}
		return LabeledMatch.textOnly(null);
	}

	/**
	 * 在所有 OCR 框中扫"含指定关键字的 fragment 标签"，定位对应右侧 y 重叠的值。
	 *
	 * <p>典型场景：OCR 把"日期"label 切碎成单字"期"（如"日上下单里"+ 右侧
	 * "期：2021年03月26日"），此方法先用 {@link #findBoxesByKeyword} 定位
	 * fragment 标签框，再在右侧 y 重叠区域找值。
	 *
	 * <p>与 {@link #matchValueByCenterWithBox} 的差异：本方法显式接受 fragment
	 * 关键字列表，而非"label 包含 text"。
	 *
	 * @param results       OCR 识别结果列表
	 * @param labelKeywords 标签 fragment 关键字列表（如 ["日期", "期"]）
	 * @return 字段值 + 值框；未匹配到时返回仅含 null value 的 LabeledMatch
	 */
	public static LabeledMatch matchValueByLabelKeywordWithBox(List<PPOcrV6Result> results,
															   List<String> labelKeywords) {
		if (labelKeywords == null || labelKeywords.isEmpty()) return LabeledMatch.textOnly(null);
		// 找 fragment 标签框：取文本等于某 keyword（最严格）或以 keyword 开头
		PPOcrV6Result labelBox = null;
		for (String kw : labelKeywords) {
			if (kw == null || kw.isEmpty()) continue;
			for (PPOcrV6Result r : results) {
				String text = r.text();
				if (text == null || text.isEmpty()) continue;
				if (text.equals(kw) || text.startsWith(kw) || text.endsWith(kw)) {
					labelBox = r;
					break;
				}
			}
			if (labelBox != null) break;
		}
		if (labelBox == null) return LabeledMatch.textOnly(null);

		// 在 labelBox 右侧 y 重叠 + y 中心距离最小
		int labelCenterX = (minX(labelBox) + maxX(labelBox)) / 2;
		int labelCenterY = (minY(labelBox) + maxY(labelBox)) / 2;
		int labelMaxX = maxX(labelBox);
		PPOcrV6Result best = null;
		int bestScore = Integer.MAX_VALUE;
		for (PPOcrV6Result r : results) {
			if (r == labelBox) continue;
			String text = r.text();
			if (text == null || text.isEmpty()) continue;
			int x0 = minX(r);
			int rCenterX = (x0 + maxX(r)) / 2;
			if (rCenterX <= labelCenterX) continue;
			if (maxY(r) < minY(labelBox) || minY(r) > maxY(labelBox)) continue;
			int dy = Math.abs((minY(r) + maxY(r)) / 2 - labelCenterY);
			int dx = x0 - labelMaxX;
			int score = dx * 10 + dy;
			if (score < bestScore) {
				bestScore = score;
				best = r;
			}
		}
		if (best == null) return LabeledMatch.textOnly(null);
		return LabeledMatch.of(best.text(), best);
	}

	/**
	 * 互斥分配 label-value 配对（贪心最佳优先）。
	 *
	 * <p>使用场景：多个 label 位于同一 y 区域，各自的 value 候选在同一右侧列上。
	 * 简单按"最近 x 距离"会重复选到同一 value；此方法用贪心保证每个 value 最多
	 * 被一个 label 占用。
	 *
	 * <p>算法（O(L·V) 贪心）：
	 * <ol>
	 *   <li>对每个 label 找出所有候选 value（label 右侧 + y 重叠 + valueExtractor 非空）；</li>
	 *   <li>计算每个 (label, value) 配对的分数（dx*10+dy，越低越好）；</li>
	 *   <li>按分数升序处理配对，先确定最低分的（label, value）配对，标记 value 已占用；</li>
	 *   <li>继续处理下一对，如果 value 已被占用或 label 已确定，跳过；</li>
	 *   <li>直到所有 label 都被处理或无候选。</li>
	 * </ol>
	 *
	 * @param results        OCR 识别结果列表
	 * @param labelDefs      label 定义列表（name, primaryLabel, altKeywords[]）
	 * @param valueExtractor 从候选 value 文本提取字段值的函数（null 表示该 value 不合格）
	 * @param yOverlapDelta  y 重叠容差（像素，默认 5）
	 * @return labelName → value 的映射
	 */
	public static Map<String, String> assignExclusiveValues(
		List<PPOcrV6Result> results,
		List<LabelDef> labelDefs,
		Function<String, String> valueExtractor,
		int yOverlapDelta) {
		Map<String, String> result = new LinkedHashMap<>();
		if (labelDefs == null || labelDefs.isEmpty()) return result;

		// 1) 收集所有 label 框
		Map<String, PPOcrV6Result> labelBoxes = new HashMap<>();
		for (LabelDef def : labelDefs) {
			PPOcrV6Result box = findLabelBox(results, def.primaryLabel);
			if (box == null && def.altKeywords != null && def.altKeywords.length > 0) {
				// 尝试 altKeywords 定位
				List<PPOcrV6Result> candidates = findBoxesByKeyword(results, def.altKeywords);
				PPOcrV6Result best = null;
				int bestLen = Integer.MAX_VALUE;
				for (PPOcrV6Result c : candidates) {
					if (c.text().length() < bestLen) {
						bestLen = c.text().length();
						best = c;
					}
				}
				box = best;
			}
			if (box != null) {
				labelBoxes.put(def.name, box);
			}
		}
		// 1.5) 排除"被另一 label 占用"的 labelBox：
		//     如果 label A 的 labelBox 与 label B 的 labelBox 相同，且 A 的 primaryLabel
		//     是 B 的 primaryLabel 的真子串（如"金额" ⊂ "总金额"），则 A 优先，B 置 null
		//     （避免"总金额"借用"金额"label box 导致候选集合错乱）
		for (int i = 0; i < labelDefs.size(); i++) {
			LabelDef defA = labelDefs.get(i);
			PPOcrV6Result boxA = labelBoxes.get(defA.name);
			if (boxA == null) continue;
			for (int j = 0; j < labelDefs.size(); j++) {
				if (i == j) continue;
				LabelDef defB = labelDefs.get(j);
				PPOcrV6Result boxB = labelBoxes.get(defB.name);
				if (boxB == null) continue;
				// 如果 A 和 B 共享 labelBox，且 A 的 primaryLabel 是 B 的子串（A 比 B 短），
				// A 保留，B 失去 labelBox
				if (boxA == boxB
					&& defB.primaryLabel.contains(defA.primaryLabel)
					&& !defA.primaryLabel.equals(defB.primaryLabel)) {
					labelBoxes.put(defB.name, null);
				}
			}
		}

		// 2) 收集所有 (labelName, valueBox, value, score) 配对
		List<ScoredPair> pairs = new ArrayList<>();
		for (LabelDef def : labelDefs) {
			PPOcrV6Result labelBox = labelBoxes.get(def.name);
			if (labelBox == null) continue;
			int labelCenterX = (minX(labelBox) + maxX(labelBox)) / 2;
			int labelCenterY = (minY(labelBox) + maxY(labelBox)) / 2;
			int labelMaxX = maxX(labelBox);
			int labelMinY = minY(labelBox);
			int labelMaxY = maxY(labelBox);
			for (PPOcrV6Result r : results) {
				String text = r.text();
				if (text == null || text.isEmpty()) continue;
				// 合并框场景（如"金额 40.60"）：先尝试从合并框剥值
				//    注意：label box 自己也可能是合并框（label + value 合并），
				//    所以合并框检查要在 label box skip 之前
				if ((r == labelBox || text.startsWith(def.primaryLabel))
					&& text.length() > def.primaryLabel.length()) {
					String stripped = text.substring(def.primaryLabel.length()).trim();
					String value = valueExtractor.apply(stripped);
					if (value != null) {
						pairs.add(new ScoredPair(def.name, r, value, 0));
					}
					continue;
				}
				if (r == labelBox) continue;
				int x0 = minX(r);
				int rCenterX = (x0 + maxX(r)) / 2;
				if (rCenterX <= labelCenterX) continue;
				if (maxY(r) < labelMinY + yOverlapDelta || minY(r) > labelMaxY - yOverlapDelta) continue;
				String value = valueExtractor.apply(text);
				if (value == null) continue;
				int dy = Math.abs((minY(r) + maxY(r)) / 2 - labelCenterY);
				int dx = x0 - labelMaxX;
				int score = dx * 10 + dy;
				pairs.add(new ScoredPair(def.name, r, value, score));
			}
		}

		// 3) 贪心最佳优先：按分数升序，确定配对
		pairs.sort(Comparator.comparingInt(p -> p.score));
		Set<PPOcrV6Result> usedValues = new HashSet<>();
		Set<String> doneLabels = new HashSet<>();
		for (ScoredPair p : pairs) {
			if (doneLabels.contains(p.labelName)) continue;
			if (usedValues.contains(p.valueBox)) continue;
			result.put(p.labelName, p.value);
			usedValues.add(p.valueBox);
			doneLabels.add(p.labelName);
		}
		return result;
	}

	// ==================================================================
	// 互斥分配（label-value mutual exclusion）
	//   解决"金额行"等多 label 抢同一右侧值的问题：
	//   例如"金额"/"燃油附加费"/"总金额"在同一行右侧时，
	//   简单最近 x 距离会让 3 个 label 都选到同一值。
	// ==================================================================

	/**
	 * 字段匹配结果：字段值 + 对应 OCR 结果（含 box 坐标）。
	 *
	 * <p>一个字段可能由多个 OCR 框拼接/提取而来（例如长地址跨多行），
	 * 因此用 {@link LabeledMatch#matches} 承载多个值框（通常只有一个）。
	 */
	@lombok.Value
	@Accessors(fluent = true)
	public static class LabeledMatch {
		/**
		 * 字段值
		 */
		String value;
		/**
		 * 匹配到的 OCR 结果（含 box 坐标）
		 */
		List<PPOcrV6Result> matches;

		/**
		 * 仅文本、无匹配框（兜底场景）。
		 *
		 * @param value 字段值（可为空）
		 * @return 文本 LabeledMatch
		 */
		public static LabeledMatch textOnly(String value) {
			return new LabeledMatch(value, CollUtil.listOf());
		}

		/**
		 * 文本 + 单个值框。
		 *
		 * @param value 字段值
		 * @param match 值框对应的 OCR 结果；null 时回退为空 list
		 * @return 单值框 LabeledMatch
		 */
		public static LabeledMatch of(String value, PPOcrV6Result match) {
			return new LabeledMatch(value, match == null ? CollUtil.listOf() : CollUtil.listOf(match));
		}

		/**
		 * 文本 + 多个值框（跨行字段如长地址）。
		 *
		 * @param value   字段值
		 * @param matches 值框 OCR 结果列表；null 时回退为空 list
		 * @return 多值框 LabeledMatch
		 */
		public static LabeledMatch of(String value, List<PPOcrV6Result> matches) {
			return new LabeledMatch(value, matches == null ? CollUtil.listOf() : CollUtil.unmodifiableList(matches));
		}

		/**
		 * 判断是否存在非空字段值。
		 *
		 * @return true 表示 value 非 null 且非空字符串
		 */
		public boolean hasValue() {
			return value != null && !value.isEmpty();
		}
	}

	/**
	 * 互斥分配的 label 定义。
	 *
	 * <p>用于 {@link LabelMatcher#assignExclusiveValues} 描述一组"同 y 区域、共享右侧
	 * 值列"的字段（典型如发票"金额 / 燃油附加费 / 总金额"在同一右侧列）。每个 label 含：
	 * <ul>
	 *   <li><b>主标签</b> {@link #primaryLabel} —— 通过 {@link LabelMatcher#findLabelBox}
	 *       精确定位，匹配"完整等于 / 以 label 开头 / label 包含文本"三级回退；</li>
	 *   <li><b>备选关键字</b> {@link #altKeywords} —— 主标签被 OCR 漏识别或切碎成 fragment
	 *       时回退定位；按 {@link LabelMatcher#findBoxesByKeyword} 模糊命中，命中后取文本
	 *       最短的候选框（更接近 fragment 标签）。</li>
	 * </ul>
	 *
	 * <p>典型用法（发票金额行）：
	 * <pre>
	 * List&lt;LabelDef&gt; defs = Arrays.asList(
	 *     new LabelDef("amount",        "金额",   "金"),
	 *     new LabelDef("fuelSurcharge", "附加费", "附"),
	 *     new LabelDef("total",         "总金额", "总")
	 * );
	 * Map&lt;String, String&gt; fields = LabelMatcher.assignExclusiveValues(
	 *     results, defs, InvoiceParser::extractMoney, 5);
	 * </pre>
	 */
	@lombok.Value
	@Accessors(fluent = true)
	public static class LabelDef {
		/**
		 * 字段名（结果 Map 的 key）。不可为 null。
		 */
		String name;
		/**
		 * 主标签文本。不可为 null。
		 */
		String primaryLabel;
		/**
		 * OCR 漏识别/切碎标签时的备选关键字（{@link LabelMatcher#findBoxesByKeyword} 模糊定位）。
		 * 可为 null 或空数组。
		 */
		String[] altKeywords;

		/**
		 * 构造 label 定义。
		 *
		 * @param name         字段名（结果 Map 的 key，不可为 null）
		 * @param primaryLabel 主标签（不可为 null）
		 * @param altKeywords  OCR 漏识别标签时的备选关键字（可为 null 或空）
		 * @throws IllegalArgumentException name 或 primaryLabel 为 null
		 */
		public LabelDef(String name, String primaryLabel, String... altKeywords) {
			if (name == null || primaryLabel == null) {
				throw new IllegalArgumentException("name and primaryLabel are required");
			}
			this.name = name;
			this.primaryLabel = primaryLabel;
			this.altKeywords = altKeywords;
		}
	}

	/**
	 * 互斥分配的内部数据结构：单个 (label, value) 配对及其位置分数。
	 *
	 * <p>仅在 {@link LabelMatcher#assignExclusiveValues} 内部使用，承载：
	 * <ul>
	 *   <li>所属 label 名（对应 {@link LabelDef#name()}）；</li>
	 *   <li>候选 value 框（{@link PPOcrV6Result}）及其提取值；</li>
	 *   <li>位置分数 {@code |x0 - labelMaxX| * 10 + |rCenterY - labelCenterY|}，越低越好；
	 *       合并框场景（label 与 value 同一 OCR 框）取 0，强制最优先匹配。</li>
	 * </ul>
	 */
	@lombok.Value
	@Accessors(fluent = true)
	private static class ScoredPair {
		/**
		 * 所属 label 名（对应 {@link LabelDef#name()}）。
		 */
		String labelName;
		/**
		 * 候选 value 框（OCR 识别结果）。
		 */
		PPOcrV6Result valueBox;
		/**
		 * 从 {@link #valueBox} 文本提取出的字段值（已通过 {@code valueExtractor} 校验）。
		 */
		String value;
		/**
		 * 位置分数，越低越好；合并框场景为 0（强制最优先匹配）。
		 */
		int score;
	}
}
