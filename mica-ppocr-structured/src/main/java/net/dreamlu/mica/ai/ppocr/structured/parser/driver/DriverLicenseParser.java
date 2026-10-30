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

package net.dreamlu.mica.ai.ppocr.structured.parser.driver;

import lombok.extern.slf4j.Slf4j;
import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Engine;
import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Result;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.BaseStructuredParser;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.DateRepair;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.FieldConfidence;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.FieldSource;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.LabelMatcher;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.ParsedField;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.ValidationResult;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.Validations;
import net.dreamlu.mica.ai.ppocr.utils.CollUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 机动车驾驶证 OCR 结构化解析器。
 *
 * <p>策略概览：
 * <ul>
 *   <li><b>证号</b>：15~18 位连续数字（顶部独立框）；标签定位 + 正则兜底。</li>
 *   <li><b>姓名/性别/国籍</b>：标签定位（"姓名 Name"/"性别 Sex"/"国籍 Nationality"）；
 *       OCR 残缺标签兜底匹配中文短标签。</li>
 *   <li><b>住址</b>：按"住址 Address"标签定位；可能跨多框拼接。</li>
 *   <li><b>出生日期 / 初次领证日期</b>：标签定位 + yyyy-MM-dd 正则兜底。</li>
 *   <li><b>准驾车型</b>：标签定位 + 短大写字母+数字（"C1"/"A2"/"B2"）兜底。</li>
 *   <li><b>签发机关</b>：图片左下区域 + 中文 ≥6 字 + 不含"日期/Class"等噪声。</li>
 *   <li><b>有效期限</b>：标签定位后按"yyyy-MM-dd 至 yyyy-MM-dd"格式切分。</li>
 * </ul>
 */
@Slf4j
public class DriverLicenseParser extends BaseStructuredParser<DriverLicenseResult> {

	/**
	 * 证号：15~18 位连续数字。
	 */
	private static final Pattern LICENSE_NUMBER_PATTERN = Pattern.compile("\\d{15,18}");
	/**
	 * 日期：yyyy-MM-dd（容忍缺 0）。
	 */
	private static final Pattern DATE_PATTERN = Pattern.compile(
		"\\d{4}-\\d{1,2}-\\d{1,2}");
	/**
	 * 准驾车型：1~2 个大写字母 + 0~1 个数字（"C1"/"A2"/"B2"/"A1A2"/"C1E"）。
	 */
	private static final Pattern VEHICLE_CLASS_PATTERN = Pattern.compile("[A-Z][0-9]?[A-Z0-9]?");
	/**
	 * 有效期限：yyyy-MM-dd 至 yyyy-MM-dd。
	 */
	private static final Pattern VALID_PERIOD_PATTERN = Pattern.compile(
		"\\d{4}-\\d{1,2}-\\d{1,2}\\s*至\\s*\\d{4}-\\d{1,2}-\\d{1,2}");
	/**
	 * 性别：仅 男/女。
	 */
	private static final Pattern GENDER_PATTERN = Pattern.compile("[男女]");

	/**
	 * 签发机关：已知的非签发机关标签前缀（OCR 可能识别成单独的"姓名""性别""有效期限"等框）。
	 */
	private static final List<String> ISSUING_AUTHORITY_LABEL_PREFIXES = CollUtil.listOf(
		"姓名", "性别", "国籍", "住址", "证号",
		"出生日期", "初次领证日期", "准驾车型", "有效期限"
	);

	/**
	 * 签发机关：已知的英文标签片段。
	 */
	private static final List<String> ISSUING_AUTHORITY_LABEL_ENDS = CollUtil.listOf(
		"Name", "Sex", "Nationality", "Address",
		"Date of Birth", "Date of First Issue", "Class", "Valid Period"
	);

	/**
	 * 构造驾驶证解析器，绑定推理引擎。
	 *
	 * @param engine PP-OCRv6 推理引擎；可为 null（仅在仅调用 {@link #parseResults(List)} 时）
	 */
	public DriverLicenseParser(PPOcrV6Engine engine) {
		super(engine);
	}

	/**
	 * 证号：标签定位（"证号"）+ 数字正则兜底。
	 */
	private static ParsedField parseLicenseNumber(List<PPOcrV6Result> results) {
		LabelMatcher.LabeledMatch labelValue = LabelMatcher.matchValueWithBox(results, "证号");
		if (labelValue.hasValue() && LICENSE_NUMBER_PATTERN.matcher(labelValue.value()).matches()) {
			return LabelMatcher.toParsedField(labelValue, FieldSource.LABEL);
		}
		// 兜底：扫描所有数字串，取第一个 15~18 位的
		LabelMatcher.LabeledMatch fallback = LabelMatcher.matchSubstringWithBox(results, text -> {
			Matcher m = LICENSE_NUMBER_PATTERN.matcher(text.replace(" ", ""));
			return m.find() ? m.group() : null;
		});
		if (fallback.hasValue()) {
			log.debug("驾驶证解析：证号正则兜底命中 \"{}\"", fallback.value());
			return LabelMatcher.toParsedField(fallback, FieldSource.PATTERN);
		}
		return ParsedField.missing();
	}

	/**
	 * 姓名：标签定位（独立"姓名"框 或 "姓名 Name"合并框）。
	 */
	private static ParsedField parseName(List<PPOcrV6Result> results) {
		// "姓名 Name" 合并框走 prefix 路径命中；"Name" 单字 fragment 不会命中（label="姓名"不含"Name"）
		LabelMatcher.LabeledMatch match = LabelMatcher.matchValueWithBox(results, "姓名");
		if (match.hasValue()) {
			return LabelMatcher.toParsedField(match, FieldSource.LABEL);
		}
		return ParsedField.missing();
	}

	/**
	 * 性别：标签定位 → 值必须是"男"或"女"；否则正则兜底。
	 *
	 * <p>过滤策略：值框不能是纯数字（避免误匹配到上方的证号框）、不能是纯英文（避免匹配到
	 * 下方的 "Sex."）；值文本只保留"男"/"女"两个候选。
	 */
	private static ParsedField parseGender(List<PPOcrV6Result> results) {
		PPOcrV6Result labelBox = LabelMatcher.findLabelBox(results, "性别");
		if (labelBox != null) {
			int labelCenterX = (LabelMatcher.minX(labelBox) + LabelMatcher.maxX(labelBox)) / 2;
			int labelMinY = LabelMatcher.minY(labelBox);
			int labelMaxY = LabelMatcher.maxY(labelBox);

			PPOcrV6Result best = null;
			int bestX = Integer.MAX_VALUE;
			for (PPOcrV6Result r : results) {
				if (r == labelBox) continue;
				String text = r.text();
				// 过滤纯数字（误匹配到证号框）
				if (text.matches("\\d+")) continue;
				// 过滤纯英文（匹配到下方的 "Sex."）
				if (text.matches("[A-Za-z\\s.]+")) continue;
				// 必须包含男/女
				if (!GENDER_PATTERN.matcher(text).find()) continue;
				int rCenterX = (LabelMatcher.minX(r) + LabelMatcher.maxX(r)) / 2;
				if (rCenterX <= labelCenterX) continue;
				// y 重叠（同一行）：允许 ±1 行高容差
				int oneLine = labelMaxY - labelMinY;
				if (LabelMatcher.maxY(r) < labelMinY - oneLine
					|| LabelMatcher.minY(r) > labelMaxY + oneLine) continue;
				int x0 = LabelMatcher.minX(r);
				if (x0 < bestX) {
					bestX = x0;
					best = r;
				}
			}
			if (best != null) {
				Matcher m = GENDER_PATTERN.matcher(best.text());
				if (m.find()) {
					return ParsedField.label(m.group(), best.score());
				}
			}
		}
		// 兜底：扫描所有文本框，找第一个含"男"/"女"的
		LabelMatcher.LabeledMatch fallback = LabelMatcher.matchSubstringWithBox(results, text -> {
			Matcher m = GENDER_PATTERN.matcher(text);
			return m.find() ? m.group() : null;
		});
		if (fallback.hasValue()) {
			log.debug("驾驶证解析：性别正则兜底命中 \"{}\"", fallback.value());
			return LabelMatcher.toParsedField(fallback, FieldSource.PATTERN);
		}
		return ParsedField.missing();
	}

	/**
	 * 国籍：标签定位 → 从合并框（如 "Natonality中国"）或独立值框中剥出"中国"；
	 * 找不到时直接填"中国"（中国大陆驾照国籍固定为中国）。
	 */
	private static String parseNationality(List<PPOcrV6Result> results) {
		// 1) 先用 LabelMatcher 标准逻辑
		PPOcrV6Result labelBox = LabelMatcher.findLabelBox(results, "国籍");
		if (labelBox != null) {
			int labelCenterX = (LabelMatcher.minX(labelBox) + LabelMatcher.maxX(labelBox)) / 2;
			int labelMinY = LabelMatcher.minY(labelBox);
			int labelMaxY = LabelMatcher.maxY(labelBox);

			String best = null;
			int bestX = Integer.MAX_VALUE;
			for (PPOcrV6Result r : results) {
				if (r == labelBox) continue;
				String text = r.text();
				// 过滤纯英文标签
				if (text.matches("[A-Za-z\\s.]+")) continue;
				int rCenterX = (LabelMatcher.minX(r) + LabelMatcher.maxX(r)) / 2;
				if (rCenterX <= labelCenterX) continue;
				if (LabelMatcher.maxY(r) < labelMinY || LabelMatcher.minY(r) > labelMaxY) continue;
				int x0 = LabelMatcher.minX(r);
				if (x0 < bestX) {
					bestX = x0;
					best = text;
				}
			}
			if (best != null) {
				// 可能是 "Natonality中国" 这样的合并框，剥掉英文前缀保留中文
				String stripped = best.replaceAll("^[A-Za-z\\s.]+", "");
				if (!stripped.isEmpty()) {
					return deduplicateChina(stripped);
				}
			}
		}
		// 2) 兜底：扫描所有框，从含"中国"的文本提取
		for (PPOcrV6Result r : results) {
			String text = r.text();
			if (text.contains("中国")) {
				String stripped = text.replaceAll("^[A-Za-z\\s.]+", "");
				if (stripped.startsWith("中国")) {
					return deduplicateChina(stripped);
				}
				if (text.equals("中国")) return "中国";
			}
		}
		// 3) 大陆驾照国籍基本恒为"中国"，作为最终兜底
		log.warn("驾驶证解析：未找到国籍值，兜底填充 \"中国\"");
		return "中国";
	}

	/**
	 * 处理 OCR 把"中国"重复识别的场景（如 "Nationaity中国中国" → "中国"）。
	 */
	private static String deduplicateChina(String text) {
		if (text.equals("中国中国")) return "中国";
		// 通用：去除连续重复的"中国"片段
		return text.replaceAll("(中国)+", "中国");
	}

	/**
	 * 住址：标签定位 + 跨多框拼接。
	 *
	 * <p>容忍 OCR 把"住址"识别成近似字（如"佳址"）：在标签定位失败时，扫描所有长度=2的
	 * 文本框，与"姓名"框做位置比较（住址通常位于姓名框下方）。
	 */
	private static String parseAddress(List<PPOcrV6Result> results) {
		PPOcrV6Result labelBox = LabelMatcher.findLabelBox(results, "住址");
		// 兜底：OCR 错字场景，找"住址"的 OCR 近邻（"佳址"等）
		if (labelBox == null) {
			PPOcrV6Result nameBox = LabelMatcher.findLabelBox(results, "姓名");
			if (nameBox != null) {
				int nameBottom = LabelMatcher.maxY(nameBox);
				int nameHeight = nameBottom - LabelMatcher.minY(nameBox);
				// 找长度=2 且位于"姓名"标签下方 0~2 行、x 范围相近的疑似"住址"标签框
				PPOcrV6Result bestAlt = null;
				int bestDist = Integer.MAX_VALUE;
				for (PPOcrV6Result r : results) {
					String text = r.text();
					if (text.length() != 2) continue;
					// 跳过纯英文
					if (text.matches("[A-Za-z]+")) continue;
					// y 必须在姓名下方 0~2 行
					int rMinY = LabelMatcher.minY(r);
					if (rMinY < nameBottom || rMinY > nameBottom + 2 * nameHeight) continue;
					// x 起点要靠近"姓名"标签
					int dx = Math.abs(LabelMatcher.minX(r) - LabelMatcher.minX(nameBox));
					if (dx < bestDist && dx < 2 * nameHeight) {
						bestDist = dx;
						bestAlt = r;
					}
				}
				if (bestAlt != null) {
					log.warn("驾驶证解析：未找到 \"住址\" 标签，使用近似标签 \"{}\" 作为住址定位基准", bestAlt.text());
					labelBox = bestAlt;
				}
			}
		}
		if (labelBox == null) {
			log.warn("驾驶证解析：未找到标签 \"住址\"");
			return null;
		}
		int labelCenterX = (LabelMatcher.minX(labelBox) + LabelMatcher.maxX(labelBox)) / 2;
		int labelMinY = LabelMatcher.minY(labelBox);
		int labelMaxY = LabelMatcher.maxY(labelBox);

		List<PPOcrV6Result> candidates = new ArrayList<>();
		for (PPOcrV6Result r : results) {
			if (r == labelBox) {
				continue;
			}
			String text = r.text();
			// 跳过纯英文标签/含日期符号的框
			if (text.matches("[A-Za-z\\s]+") || text.contains("-")) {
				continue;
			}
			// 跳过混入国籍/性别/姓名等其他字段的合并框（如 "..atonality中国"）
			if (text.contains("国籍") || text.contains("Nationality")
				|| text.contains("性别") || text.contains("Sex")
				|| text.contains("姓名") || text.contains("Name")) {
				continue;
			}
			// 跳过含"中国"的框（国籍值被 OCR 识别成独立框或合并框，不属于住址）
			if (text.contains("中国")) {
				continue;
			}
			// 跳过含较多英文字符的合并框（避免国籍/姓名英文标签被误并入地址）
			long letterCount = text.chars().filter(c -> (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')).count();
			if (letterCount > 2) {
				continue;
			}
			int x0 = LabelMatcher.minX(r);
			int rCenterX = (x0 + LabelMatcher.maxX(r)) / 2;
			// 值框中心 x 必须在标签中心 x 右侧
			if (rCenterX <= labelCenterX) {
				continue;
			}
			int rMinY = LabelMatcher.minY(r);
			int rMaxY = LabelMatcher.maxY(r);
			// y 范围与标签框有重叠（允许下方扩展 1 行住址高度）
			int oneLine = (labelMaxY - labelMinY);
			if (rMaxY < labelMinY || rMinY > labelMaxY + oneLine) {
				continue;
			}
			candidates.add(r);
		}
		if (candidates.isEmpty()) {
			return null;
		}
		candidates.sort(Comparator.comparingInt(LabelMatcher::minY));
		StringBuilder sb = new StringBuilder();
		for (PPOcrV6Result r : candidates) {
			sb.append(r.text());
		}
		// 去空白
		return sb.toString().replaceAll("\\s+", "");
	}

	/**
	 * 出生日期：标签定位 + yyyy-MM-dd 正则兜底（取第一个匹配 — 出生日期通常在初次领证日期上方）。
	 */
	private static ParsedField parseBirthDate(List<PPOcrV6Result> results) {
		LabelMatcher.LabeledMatch labelValue = LabelMatcher.matchValueWithBox(results, "出生日期");
		if (labelValue.hasValue()) {
			String repaired = repairDateText(labelValue.value(), "出生日期");
			if (repaired != null) {
				return LabelMatcher.toParsedField(labelValue, FieldSource.LABEL, repaired);
			}
			return LabelMatcher.toParsedField(labelValue, FieldSource.LABEL);
		}
		// 正则兜底（取第一个匹配 — 出生日期通常在初次领证日期上方）
		LabelMatcher.LabeledMatch fallback = LabelMatcher.matchPatternWithBox(results, DATE_PATTERN, false);
		if (fallback.hasValue()) {
			return LabelMatcher.toParsedField(fallback, FieldSource.PATTERN);
		}
		return ParsedField.missing();
	}

	/**
	 * 初次领证日期：标签定位 + yyyy-MM-dd 正则兜底（取最后一个匹配 — 初次领证日期通常在出生日期下方）。
	 */
	private static ParsedField parseIssueDate(List<PPOcrV6Result> results) {
		LabelMatcher.LabeledMatch labelValue = LabelMatcher.matchValueWithBox(results, "初次领证日期");
		if (labelValue.hasValue()) {
			String repaired = repairDateText(labelValue.value(), "初次领证日期");
			if (repaired != null) {
				return LabelMatcher.toParsedField(labelValue, FieldSource.LABEL, repaired);
			}
			return LabelMatcher.toParsedField(labelValue, FieldSource.LABEL);
		}
		// 正则兜底（取最后一个匹配 — 初次领证日期通常在出生日期下方）
		LabelMatcher.LabeledMatch fallback = LabelMatcher.matchPatternWithBox(results, DATE_PATTERN, true);
		if (fallback.hasValue()) {
			return LabelMatcher.toParsedField(fallback, FieldSource.PATTERN);
		}
		return ParsedField.missing();
	}

	/**
	 * 日期文本修复：把被 OCR 弄乱分隔符的日期还原成 yyyy-MM-dd。
	 *
	 * <p>两条路径：
	 * <ol>
	 *   <li>轻微噪点（如 "2017-05-18." / "2017-05-1.2"）→ 去非数字非"-"字符后重抽；</li>
	 *   <li>分隔符被认成数字（如 "2015-0518"）→ 按纯数字重排还原（{@link DateRepair}）。</li>
	 * </ol>
	 *
	 * @param text      原始日期文本
	 * @param fieldName 字段名（日志用）
	 * @return 修复后的日期；无需修复或无法修复时返回 null（调用方沿用原值）
	 */
	private static String repairDateText(String text, String fieldName) {
		if (text == null || text.isEmpty()) {
			return null;
		}
		if (DATE_PATTERN.matcher(text).matches()) {
			return null;
		}
		// 路径 1：轻微噪点清理
		String cleaned = text.replaceAll("[^0-9\\-]", "");
		Matcher m = DATE_PATTERN.matcher(cleaned);
		if (m.find()) {
			String extracted = m.group();
			log.debug("驾驶证解析：{}从含噪文本 \"{}\" (清理后 \"{}\") 中抽取 \"{}\"",
				fieldName, text, cleaned, extracted);
			return extracted;
		}
		// 路径 2：分隔符被认成数字 → 纯数字重排
		String repaired = DateRepair.repairDate(text);
		if (repaired != null) {
			log.debug("驾驶证解析：{}由 \"{}\" 修复为 \"{}\"", fieldName, text, repaired);
		}
		return repaired;
	}

	/**
	 * 准驾车型：标签定位 + 短大写正则兜底。
	 */
	private static ParsedField parseVehicleClass(List<PPOcrV6Result> results) {
		LabelMatcher.LabeledMatch labelValue = LabelMatcher.matchValueWithBox(results, "准驾车型");
		if (labelValue.hasValue() && VEHICLE_CLASS_PATTERN.matcher(labelValue.value()).matches()) {
			return LabelMatcher.toParsedField(labelValue, FieldSource.LABEL);
		}
		LabelMatcher.LabeledMatch fallback = LabelMatcher.matchPatternWithBox(results, VEHICLE_CLASS_PATTERN, false);
		if (fallback.hasValue()) {
			return LabelMatcher.toParsedField(fallback, FieldSource.PATTERN);
		}
		return ParsedField.missing();
	}

	/**
	 * 签发机关：图片左下区域（红色印章区）+ 中文 ≥4 字 + 不含"日期/Class/驾驶"等噪声。
	 *
	 * <p>位置特征：x 起点位于图片左半边；y 范围在中下部分；不是日期/Class/出生 等标签。
	 */
	private static String parseIssuingAuthority(List<PPOcrV6Result> results) {
		// 收集图片整体几何边界（用 OCR 框推断近似图片大小）
		int imgMaxX = 0;
		int imgMaxY = 0;
		int imgMinX = Integer.MAX_VALUE;
		int imgMinY = Integer.MAX_VALUE;
		for (PPOcrV6Result r : results) {
			imgMaxX = Math.max(imgMaxX, LabelMatcher.maxX(r));
			imgMaxY = Math.max(imgMaxY, LabelMatcher.maxY(r));
			imgMinX = Math.min(imgMinX, LabelMatcher.minX(r));
			imgMinY = Math.min(imgMinY, LabelMatcher.minY(r));
		}
		int imgMidX = (imgMinX + imgMaxX) / 2;
		int imgMidY = (imgMinY + imgMaxY) / 2;

		// 已知的非签发机关标签前缀与英文片段已提取为类常量
		// {@link #ISSUING_AUTHORITY_LABEL_PREFIXES} / {@link #ISSUING_AUTHORITY_LABEL_ENDS}
		List<PPOcrV6Result> candidates = new ArrayList<>();
		for (PPOcrV6Result r : results) {
			String text = r.text();
			// 排除已知标签前缀/英文标签
			boolean isLabel = false;
			for (String prefix : ISSUING_AUTHORITY_LABEL_PREFIXES) {
				if (text.startsWith(prefix)) {
					isLabel = true;
					break;
				}
			}
			if (isLabel) {
				continue;
			}
			for (String en : ISSUING_AUTHORITY_LABEL_ENDS) {
				if (text.contains(en)) {
					isLabel = true;
					break;
				}
			}
			if (isLabel) {
				continue;
			}
			// 排除含日期符号或连字符的（日期文本）
			if (text.contains("-") || text.contains("/")) {
				continue;
			}
			// 必须含中文且长度 ≥4 字
			if (text.length() < 4 || !text.matches(".*[\\u4e00-\\u9fa5].*")) {
				continue;
			}
			// 位置：图片左半边 + 中下部
			int rCenterX = (LabelMatcher.minX(r) + LabelMatcher.maxX(r)) / 2;
			int rCenterY = (LabelMatcher.minY(r) + LabelMatcher.maxY(r)) / 2;
			if (rCenterX > imgMidX || rCenterY < imgMidY) {
				continue;
			}
			candidates.add(r);
		}
		if (candidates.isEmpty()) {
			log.warn("驾驶证解析：未匹配到签发机关");
			return null;
		}
		// 多行签发机关按 y 升序拼接（去空格）
		candidates.sort(Comparator.comparingInt(LabelMatcher::minY));
		StringBuilder sb = new StringBuilder();
		for (PPOcrV6Result r : candidates) {
			sb.append(r.text());
		}
		return sb.toString().replaceAll("\\s+", "");
	}

	/**
	 * 有效期限：标签定位 + "yyyy-MM-dd 至 yyyy-MM-dd" 解析。
	 *
	 * <p>容忍 OCR 轻微噪点（如 "2017-05-12.至2023-05-12" 中日期尾部的点号）。
	 *
	 * @return [validFrom, validTo]，解析失败返回 null
	 */
	private static String[] parseValidPeriod(List<PPOcrV6Result> results) {
		String labelValue = LabelMatcher.matchValue(results, "有效期限");
		if (labelValue == null) {
			labelValue = LabelMatcher.matchPattern(results, VALID_PERIOD_PATTERN, false);
		}
		if (labelValue == null) {
			log.warn("驾驶证解析：未匹配到有效期限");
			return null;
		}
		String[] parts = labelValue.split("\\s*至\\s*");
		// 清理日期两端的 OCR 噪点（非数字字符，如 "2017-05-12." → "2017-05-12"）
		for (int i = 0; i < parts.length; i++) {
			Matcher m = DATE_PATTERN.matcher(parts[i]);
			if (m.find()) {
				parts[i] = m.group();
			} else {
				// 分隔符被 OCR 认成数字（如 "2017-0512"）→ 纯数字重排还原
				String repaired = DateRepair.repairDate(parts[i]);
				if (repaired != null) {
					log.debug("驾驶证解析：有效期限由 \"{}\" 修复为 \"{}\"", parts[i], repaired);
					parts[i] = repaired;
				}
			}
		}
		if (parts.length == 2) {
			return new String[]{parts[0], parts[1]};
		}
		// 单段日期：两端相同
		return new String[]{parts[0], parts[0]};
	}

	@Override
	public DriverLicenseResult parseResults(List<PPOcrV6Result> results) {
		DriverLicenseResult r = new DriverLicenseResult();
		r.setRawResults(new ArrayList<>(results));

		ParsedField licenseNumber = parseLicenseNumber(results);
		ParsedField name = parseName(results);
		ParsedField gender = parseGender(results);
		// 性别回填：驾驶证"证号"就是公民身份号码，其性别位（倒数第二位）能救回被 OCR 糊掉的性别
		if (!gender.hasValue() && licenseNumber.hasValue()) {
			String backfilled = Validations.genderFromIdNumber(licenseNumber.value());
			if (backfilled != null) {
				log.debug("驾驶证解析：性别由证号回填为 \"{}\"", backfilled);
				gender = ParsedField.backfill(backfilled);
			}
		}
		String nationality = parseNationality(results);
		String address = parseAddress(results);
		ParsedField birthDate = parseBirthDate(results);
		ParsedField issueDate = parseIssueDate(results);
		ParsedField vehicleClass = parseVehicleClass(results);
		String issuingAuthority = parseIssuingAuthority(results);
		String[] period = parseValidPeriod(results);

		r.setLicenseNumber(licenseNumber.value());
		r.setName(name.value());
		r.setGender(gender.value());
		r.setNationality(nationality);
		r.setAddress(address);
		r.setBirthDate(birthDate.value());
		r.setIssueDate(issueDate.value());
		r.setVehicleClass(vehicleClass.value());
		r.setIssuingAuthority(issuingAuthority);
		if (period != null) {
			r.setValidFrom(period[0]);
			r.setValidTo(period[1]);
		}

		// —— 正确性层：证号走 GB 11643 校验位 + 字段级置信度 ——
		// 驾驶证证号即公民身份号码，校验不过 = 至少认错了一个字符。
		ValidationResult licenseValidation = Validations.validateIdCard(licenseNumber.value());
		if (licenseValidation.mismatched()) {
			log.warn("驾驶证解析：{}", licenseValidation.message());
		}
		r.putFieldConfidence(FieldConfidence.of("licenseNumber", licenseNumber, licenseValidation));
		r.putFieldConfidence(FieldConfidence.of("name", name, null));
		r.putFieldConfidence(FieldConfidence.of("gender", gender, null));
		r.putFieldConfidence(FieldConfidence.of("nationality",
			ParsedField.label(nationality, null), null));
		r.putFieldConfidence(FieldConfidence.of("address",
			ParsedField.label(address, null), null));
		r.putFieldConfidence(FieldConfidence.of("birthDate", birthDate, null));
		r.putFieldConfidence(FieldConfidence.of("issueDate", issueDate, null));
		r.putFieldConfidence(FieldConfidence.of("vehicleClass", vehicleClass, null));
		r.putFieldConfidence(FieldConfidence.of("issuingAuthority",
			ParsedField.label(issuingAuthority, null), null));
		if (period != null) {
			r.putFieldConfidence(FieldConfidence.of("validFrom",
				ParsedField.label(period[0], null), null));
			r.putFieldConfidence(FieldConfidence.of("validTo",
				ParsedField.label(period[1], null), null));
		}
		return r;
	}
}
