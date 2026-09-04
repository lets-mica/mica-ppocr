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

package net.dreamlu.mica.ai.ppocr.structured.parser.invoice;

import lombok.extern.slf4j.Slf4j;
import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Result;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.LabelMatcher;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 发票明细表公共解析器（新版电子发票 / 老版增值税发票共用）。
 *
 * <p>核心思路是"行优先"的结构化解析，替代逐列独立拼接多行字符串：
 * <ol>
 *   <li><b>表头定列</b>：每个字段找表头框（完整等于 → 合并框 → fragment 拼接 → 单字后缀），
 *       得到各列的 x 区间；</li>
 *   <li><b>y 行聚类</b>：表头下方候选框按 y 中心排序，间距超阈值拆行；
 *       相邻行 y 区间重叠时合并（OCR 常把 label/value 或"合计"拆成上下错位两框）；</li>
 *   <li><b>x 列分配</b>：行内每个框按 x 中心落入最近列区间，同列多框按 x 升序拼接；</li>
 *   <li><b>行级过滤</b>：汇总区/出行人区（含"合"+"计"fragment）命中即终止，
 *       购销方信息区命中即跳行，剔除全空行；</li>
 *   <li><b>续行合并</b>：缺数值列（金额/税额）的行并入上一行对应列——明细行的"锚"
 *       是数值列，缺锚说明该行是跨行延续（典型：商品名称换行），而非新明细。</li>
 * </ol>
 *
 * <p>列对齐由"同一行聚类 + 列内取值"保证，不存在逐列行数不一致导致的错位。
 * 输出 {@link List}<{@link InvoiceItem}> 结构化明细行。
 */
@Slf4j
final class InvoiceTableParser {

	/**
	 * 表列定义。
	 */
	static final class ColumnSpec {
		/**
		 * 结果 key（对应 {@link InvoiceItem} 字段名）
		 */
		final String key;
		/**
		 * 表头候选标签（按优先级，如老版"货物或应税劳务"/"货物或应税服务"）
		 */
		final String[] labels;
		/**
		 * 数值校验 pattern；null 表示文字列（走 {@link #isValidGoodsText}）
		 */
		final Pattern pattern;
		/**
		 * 列宽容差（px）；文字列放宽以容纳"项目名称"列值位于表头左侧
		 */
		final int xTolerance;

		ColumnSpec(String key, String[] labels, Pattern pattern, int xTolerance) {
			this.key = key;
			this.labels = labels;
			this.pattern = pattern;
			this.xTolerance = xTolerance;
		}
	}

	/**
	 * 表解析结果：结构化明细行。
	 */
	static final class TableResult {
		private final List<InvoiceItem> items;

		TableResult(List<InvoiceItem> items) {
			this.items = items;
		}

		/**
		 * 结构化明细行（空表返回空列表）。
		 *
		 * @return 明细行列表
		 */
		List<InvoiceItem> items() {
			return items;
		}
	}

	/**
	 * 明细表区域字段标签（文字列值框排除，防跨字段串扰）。
	 */
	private static final String[] TABLE_FIELD_LABELS = {
		"项目名称", "单价", "数量", "金额", "税率/征收率", "税率", "税额",
		"货物或应税劳务", "货物或应税服务", "规格型号", "单位",
		"价税合计", "开票人", "复核", "收款人",
		"购买方信息", "销售方信息", "銷售方信息",
	};

	/**
	 * 终止区域标签：行内任一框命中即停止后续行处理。
	 *
	 * <p>这些区域总出现在明细表之后（价税合计、数电票出行人子表），
	 * 命中说明明细区已结束，后续行（购/销方信息、印章等）不应再收集。
	 */
	private static final String[] BREAK_AREA_LABELS = {
		"价税合计", "（小写）", "(小写)", "（大写）", "(大写)",
		"出行人", "有效身份证件号", "出行日期", "出发地", "到达地", "交通工具类型",
	};

	/**
	 * 购销方信息区标签：行内任一框命中即跳过该行（不终止）。
	 *
	 * <p>购方信息可能位于明细表上方（老版竖版发票），只能跳行不能终止；
	 * "开户行及账号" 等常与值合并成一体框（"开户行及账号：招商银行…"）。
	 */
	private static final String[] PARTY_AREA_LABELS = {
		"称：", "名称：", "纳税人识别号", "地址、电话", "开户行及账号", "统一社会信用代码",
	};

	/**
	 * 明细最大行数（防误收合计行以下区域）。
	 */
	private static final int MAX_ROWS = 12;

	private InvoiceTableParser() {
	}

	/**
	 * 解析明细表。
	 *
	 * @param results OCR 结果
	 * @param columns 表列定义
	 * @return 解析结果；未找到任何表头时返回空结果
	 */
	static TableResult parse(List<PPOcrV6Result> results, List<ColumnSpec> columns) {
		// 1) 表头定列
		Map<ColumnSpec, PPOcrV6Result> headers = new LinkedHashMap<>();
		Set<Character> headerChars = new HashSet<>();
		for (ColumnSpec col : columns) {
			PPOcrV6Result header = findColumnHeader(results, col.labels);
			if (header != null) headers.put(col, header);
			for (String label : col.labels) {
				for (char c : label.toCharArray()) {
					headerChars.add(c);
				}
			}
		}
		if (headers.isEmpty()) {
			log.debug("发票解析：明细表表头全部缺失");
			return empty(columns);
		}

		int headerMinY = Integer.MAX_VALUE;
		int headerMaxY = Integer.MIN_VALUE;
		for (PPOcrV6Result h : headers.values()) {
			headerMinY = Math.min(headerMinY, LabelMatcher.minY(h));
			headerMaxY = Math.max(headerMaxY, LabelMatcher.maxY(h));
		}
		int oneLine = Math.max(1, headerMaxY - headerMinY);
		int rowBreakThreshold = Math.min(oneLine * 55 / 100, 12);
		int yLower = headerMaxY + oneLine * MAX_ROWS;

		// 2) 收集候选：y 在表头下方（允许略高半行）、x 中心落入任一列区间
		List<PPOcrV6Result> candidates = new ArrayList<>();
		for (PPOcrV6Result box : results) {
			if (headers.containsValue(box)) continue;
			String text = box.text().trim();
			if (text.isEmpty()) continue;
			// 表头残缺单字/双字框（如 "金"/"额"/"税"/"税率"）排除：
			// 字符全部来自表头 label（如 "金额"+"税率"+"税额"），fragment 合成框不含原框
			if (text.length() <= 2 && isAllHeaderChars(text, headerChars)) continue;
			// 排除表头区候选：与表头同 y 范围（含向上半行容差）的非表头 fragment
			// （如通行费发票的"车牌号"/"类型"等未被识别的表头 fragment），否则会被
			// 当作数据行收集，形成含 label 文本的伪行。
			// 老版竖版发票的"货物或应税劳务"表头 y_max 较大，与数据行 y 中心重叠
			// (普通发票数据 y_center - headerMaxY 范围约 -0.4 * oneLine ~ +0.3 * oneLine)，
			// 通行费发票的表头 fragment 相对偏移 ≈ -0.5 * oneLine (更深)，故以
			// oneLine/2 为界。
			int yCenter = (LabelMatcher.minY(box) + LabelMatcher.maxY(box)) / 2;
			if (yCenter <= headerMaxY - oneLine / 2) continue;
			int centerX = (LabelMatcher.minX(box) + LabelMatcher.maxX(box)) / 2;
			if (!inAnyColumn(centerX, headers)) continue;
			int y0 = LabelMatcher.minY(box);
			if (y0 < headerMinY - oneLine / 2) continue;
			if (y0 > yLower) continue;
			// 竖排防伪文字/发票专用章等超高框（高度 > 3 倍行高）非表格单元格
			if (LabelMatcher.maxY(box) - LabelMatcher.minY(box) > oneLine * 3) continue;
			candidates.add(box);
		}
		if (candidates.isEmpty()) return empty(columns);

		// 3) y 行聚类：y 中心间距超阈值拆行；相邻行 y 区间重叠则合并
		//    （OCR 常把 label/value 或"合"+"计"拆成上下错位两框，靠重叠归并回同一行）
		candidates.sort(Comparator.comparingInt(LabelMatcher::minY));
		List<List<PPOcrV6Result>> rows = new ArrayList<>();
		List<PPOcrV6Result> current = null;
		int prevY = Integer.MIN_VALUE;
		for (PPOcrV6Result box : candidates) {
			int y = (LabelMatcher.minY(box) + LabelMatcher.maxY(box)) / 2;
			if (current == null || y - prevY > rowBreakThreshold) {
				current = new ArrayList<>();
				rows.add(current);
			}
			current.add(box);
			prevY = y;
		}
		rows = mergeOverlappingRows(rows);

		// 4) 行内列分配 + 行级过滤
		List<InvoiceItem> items = new ArrayList<>();
		for (List<PPOcrV6Result> row : rows) {
			// 汇总区/出行人区：明细表已结束，终止收集（含 "合"+"计" fragment）
			if (isAfterTableRow(row)) break;
			// 购销方信息区：跳过该行（购方信息可能在明细表上方，不能终止）
			if (isPartyInfoRow(row)) continue;
			InvoiceItem item = new InvoiceItem();
			boolean anyCell = false;
			for (Map.Entry<ColumnSpec, PPOcrV6Result> e : headers.entrySet()) {
				ColumnSpec col = e.getKey();
				List<PPOcrV6Result> cells = assignColumnCells(row, col, headers);
				if (cells.isEmpty()) continue;
				StringBuilder sb = new StringBuilder();
				for (PPOcrV6Result cell : cells) {
					String extracted = extractCell(cell, col);
					if (extracted == null) continue;
					sb.append(extracted);
				}
			if (sb.length() == 0) continue;
			setField(item, col.key, sb.toString());
			anyCell = true;
		}
		if (!anyCell) continue;
		// 明细行的"锚"是金额/税额（发票明细必带数值列）；缺数值列说明该行是
		// 上一行的跨行延续（典型：商品名称换行，如 "*生产生活服务*开发服务"
		// 下一行 "费用"），各列值并入上一行对应列，而非开新明细。
		if (item.getAmount() != null || item.getTaxAmount() != null || items.isEmpty()) {
			items.add(item);
		} else {
			mergeContinuation(items.get(items.size() - 1), item);
		}
	}
	return new TableResult(items);
}

/**
 * 跨行延续合并：上一行与续行的同名字段合并。
 *
 * <p>商品名称跨行直接拼接（名称断行语义即连续文本，如
 * "开发服务" + "费用" → "开发服务费用"）；其余字段仅当上一行该列为空
 * 时填入（规格型号/单价/数量等跨行版式罕见，避免误拼接）。
 */
private static void mergeContinuation(InvoiceItem prev, InvoiceItem cont) {
	if (cont.getGoodsName() != null) {
		prev.setGoodsName(prev.getGoodsName() == null
			? cont.getGoodsName() : prev.getGoodsName() + cont.getGoodsName());
	}
	if (prev.getUnitPrice() == null) prev.setUnitPrice(cont.getUnitPrice());
	if (prev.getQuantity() == null) prev.setQuantity(cont.getQuantity());
	if (prev.getAmount() == null) prev.setAmount(cont.getAmount());
	if (prev.getTaxRate() == null) prev.setTaxRate(cont.getTaxRate());
	if (prev.getTaxAmount() == null) prev.setTaxAmount(cont.getTaxAmount());
}

	// ========================================================================
	// 表头查找链路：完整等于 → 合并框 → fragment 拼接 → 单字后缀降级
	// ========================================================================

	private static PPOcrV6Result findColumnHeader(List<PPOcrV6Result> results, String[] labels) {
		for (String label : labels) {
			PPOcrV6Result header = findColumnHeader(results, label);
			if (header != null) return header;
		}
		return null;
	}

	private static PPOcrV6Result findColumnHeader(List<PPOcrV6Result> results, String label) {
		String normalized = label.replaceAll("\\s+", "");
		// 1) 完整等于
		for (PPOcrV6Result r : results) {
			if (r.text().replaceAll("\\s+", "").equals(normalized)) return r;
		}
		// 2) 合并框（text 以 normalized 开头，如 "货物或应税劳务、服务名称"）
		for (PPOcrV6Result r : results) {
			String text = r.text().replaceAll("\\s+", "");
			if (text.startsWith(normalized) && text.length() > normalized.length()) return r;
		}
		// 3) fragment 按 x 拼接（如 "金"+"额"）
		PPOcrV6Result joined = findFragmentHeaderBox(results, normalized);
		if (joined != null) return joined;
		// 4) 单字前缀/后缀降级（fragment 拼接失败时的最后兜底，如 "额" → "金额"，"金" → "金额"）
		PPOcrV6Result best = null;
		int bestOverlap = 0;
		for (PPOcrV6Result r : results) {
			String text = r.text().replaceAll("\\s+", "");
			if (text.isEmpty() || text.length() > normalized.length()) continue;
			String suffix = normalized.substring(normalized.length() - text.length());
			String prefix = normalized.substring(0, text.length());
			if ((suffix.equals(text) || prefix.equals(text)) && text.length() > bestOverlap) {
				best = r;
				bestOverlap = text.length();
			}
		}
		if (best != null) {
			log.debug("发票解析：表列 \"{}\" 采用残缺标签 \"{}\" 作为表头", label, best.text().trim());
		}
		return best;
	}

	/**
	 * 残缺表头识别：表头标签被 OCR 切成单字 fragment（如 "金"+"额"、"税"+"额"）时，
	 * 收集同行内由 label 字符组成的 fragment 框，按 x 升序做子序列匹配还原表头，
	 * 返回合成框（x 跨首尾 fragment，y 为包围盒）。
	 *
	 * <p>子序列匹配保证："金额" 列只取第一个"金"后紧跟的"额"，"税额" 列跳过
	 * "金额" 列的"额"再匹配"税"+"额"，两列互不串位。
	 *
	 * <p>通过紧凑性校验（拼接宽度 ≤ label 字符数 × 2 倍行高）防止跨列/跨行误拼。
	 */
	private static PPOcrV6Result findFragmentHeaderBox(List<PPOcrV6Result> results, String label) {
		if (label.length() < 2) return null;
		List<PPOcrV6Result> candidates = new ArrayList<>();
		for (PPOcrV6Result r : results) {
			String text = r.text().replaceAll("\\s+", "");
			if (text.isEmpty() || text.length() > label.length()) continue;
			boolean allLabelChars = true;
			for (int i = 0; i < text.length(); i++) {
				if (label.indexOf(text.charAt(i)) < 0) {
					allLabelChars = false;
					break;
				}
			}
			if (allLabelChars) candidates.add(r);
		}
		if (candidates.size() < label.length()) return null;
		candidates.sort(Comparator.comparingInt(LabelMatcher::minX));
		List<PPOcrV6Result> matched = new ArrayList<>();
		int p = 0;
		int anchorMinY = Integer.MAX_VALUE;
		int anchorMaxY = Integer.MIN_VALUE;
		int anchorCenterY = 0;
		for (PPOcrV6Result r : candidates) {
			if (p == label.length()) break;
			String text = r.text().replaceAll("\\s+", "");
			if (!text.equals(String.valueOf(label.charAt(p)))) continue;
			if (matched.isEmpty()) {
				anchorMinY = LabelMatcher.minY(r);
				anchorMaxY = LabelMatcher.maxY(r);
				anchorCenterY = (anchorMinY + anchorMaxY) / 2;
			} else {
				// 后续 fragment 必须与首个 fragment 同行（y 中心差 ≤ 行高）
				int centerY = (LabelMatcher.minY(r) + LabelMatcher.maxY(r)) / 2;
				int oneLine = Math.max(1, anchorMaxY - anchorMinY);
				if (Math.abs(centerY - anchorCenterY) > oneLine) continue;
			}
			matched.add(r);
			p++;
		}
		if (matched.size() < label.length()) return null;
		int minX = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int minY = Integer.MAX_VALUE;
		int maxY = Integer.MIN_VALUE;
		float score = Float.MAX_VALUE;
		for (PPOcrV6Result r : matched) {
			minX = Math.min(minX, LabelMatcher.minX(r));
			maxX = Math.max(maxX, LabelMatcher.maxX(r));
			minY = Math.min(minY, LabelMatcher.minY(r));
			maxY = Math.max(maxY, LabelMatcher.maxY(r));
			score = Math.min(score, r.score());
		}
		// 紧凑性校验：拼接宽度不能超过 label 字符数 × 2 倍行高（防止跨列误拼）
		int oneLine = Math.max(1, maxY - minY);
		if (maxX - minX > label.length() * oneLine * 2) return null;
		int[][] box = {{minX, minY}, {maxX, minY}, {maxX, maxY}, {minX, maxY}};
		return new PPOcrV6Result(label, score, box);
	}

	// ========================================================================
	// 行聚类 / 列分配
	// ========================================================================

	private static boolean inAnyColumn(int centerX, Map<ColumnSpec, PPOcrV6Result> headers) {
		for (Map.Entry<ColumnSpec, PPOcrV6Result> e : headers.entrySet()) {
			ColumnSpec col = e.getKey();
			PPOcrV6Result h = e.getValue();
			int minX = LabelMatcher.minX(h) - col.xTolerance;
			int maxX = LabelMatcher.maxX(h) + col.xTolerance;
			if (centerX >= minX && centerX <= maxX) return true;
		}
		return false;
	}

	/**
	 * 行内列分配：box x 中心落在列区间内，且是该框最佳匹配列。
	 *
	 * <p>最佳匹配判定（pattern-aware）：
	 * <ol>
	 *   <li>box 文本匹配本列 pattern 但不匹配其它列 → 归本列（即使几何上不是最近）；
	 *   <li>box 文本匹配其它列 pattern 但不匹配本列 → 不归本列；
	 *   <li>两边都匹配或都不匹配 → 按几何距离选最近列。
	 * </ol>
	 *
	 * <p>解决相邻数值列 tolerance 重叠区（如金额"123.01"中心落入金额列右缘 +
	 * 税率列左缘的重叠区）被几何最近逻辑误归到税率列、但因不匹配税率 pattern
	 * 被丢弃导致金额丢失的问题。
	 *
	 * @param row     当前行候选框
	 * @param col     目标列
	 * @param headers 全部表头（用于最佳列判定）
	 * @return 分配到的框（按 x 升序），无则空列表
	 */
	private static List<PPOcrV6Result> assignColumnCells(List<PPOcrV6Result> row,
														 ColumnSpec col,
														 Map<ColumnSpec, PPOcrV6Result> headers) {
		PPOcrV6Result header = headers.get(col);
		int colMinX = LabelMatcher.minX(header) - col.xTolerance;
		int colMaxX = LabelMatcher.maxX(header) + col.xTolerance;
		int colCenter = (LabelMatcher.minX(header) + LabelMatcher.maxX(header)) / 2;
		List<PPOcrV6Result> cells = new ArrayList<>();
		for (PPOcrV6Result box : row) {
			int centerX = (LabelMatcher.minX(box) + LabelMatcher.maxX(box)) / 2;
			if (centerX < colMinX || centerX > colMaxX) continue;
			if (!isNearestColumn(centerX, col, headers, colCenter, box.text())) continue;
			cells.add(box);
		}
		cells.sort(Comparator.comparingInt(LabelMatcher::minX));
		return cells;
	}

	/**
	 * 最佳列判定（pattern-aware）。
	 *
	 * @param centerX   box x 中心
	 * @param col       当前列
	 * @param headers   全部表头
	 * @param colCenter 当前列中心 x
	 * @param text      box 文本（用于 pattern 匹配）
	 * @return true = 归当前列
	 */
	private static boolean isNearestColumn(int centerX, ColumnSpec col,
										   Map<ColumnSpec, PPOcrV6Result> headers,
										   int colCenter, String text) {
		boolean colMatches = col.pattern != null && col.pattern.matcher(text).find();
		int bestDist = Math.abs(centerX - colCenter);
		for (Map.Entry<ColumnSpec, PPOcrV6Result> e : headers.entrySet()) {
			if (e.getKey() == col) continue;
			ColumnSpec other = e.getKey();
			PPOcrV6Result h = e.getValue();
			int otherCenter = (LabelMatcher.minX(h) + LabelMatcher.maxX(h)) / 2;
			int dist = Math.abs(centerX - otherCenter);
			boolean otherMatches = other.pattern != null && other.pattern.matcher(text).find();
			// 本列匹配但其它列不匹配 → 归本列（即使其它列几何更近）
			if (colMatches && !otherMatches) continue;
			// 其它列匹配但本列不匹配 → 不归本列
			if (!colMatches && otherMatches) return false;
			// 两边都匹配或都不匹配 → 按几何距离
			if (dist < bestDist) return false;
		}
		return true;
	}

	/**
	 * 单元格取值：pattern 列提取首个匹配（trim）；文字列走噪声过滤。
	 *
	 * @param box 单元格 OCR 框
	 * @param col 列定义
	 * @return 提取值；无效为 null
	 */
	private static String extractCell(PPOcrV6Result box, ColumnSpec col) {
		String text = box.text().trim();
		if (col.pattern == null) {
			return isValidGoodsText(text) ? text : null;
		}
		Matcher m = col.pattern.matcher(text);
		if (m.find()) {
			String group = m.group();
			return group == null ? null : group.trim();
		}
		return null;
	}

	/**
	 * 相邻行 y 区间重叠（或极近）合并。
	 *
	 * <p>OCR 常把同一视觉行拆成上下错位的多个框（如购销方 label/value 分离、
	 * 合计标签"合"+"计"与金额值、明细行数值框与文字框错位），y 中心差可能
	 * 超过行聚类阈值甚至 y 区间仅差 1-2px；这些框分属不同列（x 不重叠），
	 * 据此归并回同一行。
	 *
	 * <p>仅当两行存在 x 重叠的框对（同列冲突，如相邻明细行的同列值框）时不合并，
	 * 防止两行明细被误并成一行。
	 *
	 * @param rows 按 y 聚类的行列表
	 * @return 重叠合并后的行列表
	 */
	private static List<List<PPOcrV6Result>> mergeOverlappingRows(List<List<PPOcrV6Result>> rows) {
		if (rows.size() <= 1) return rows;
		rows.sort(Comparator.comparingInt(r ->
			r.stream().mapToInt(LabelMatcher::minY).min().orElse(0)));
		List<List<PPOcrV6Result>> merged = new ArrayList<>();
		for (List<PPOcrV6Result> row : rows) {
			if (merged.isEmpty()) {
				merged.add(row);
				continue;
			}
			List<PPOcrV6Result> last = merged.get(merged.size() - 1);
			int lastMinY = last.stream().mapToInt(LabelMatcher::minY).min().orElse(0);
			int lastMaxY = last.stream().mapToInt(LabelMatcher::maxY).max().orElse(0);
			int curMinY = row.stream().mapToInt(LabelMatcher::minY).min().orElse(0);
			int curMaxY = row.stream().mapToInt(LabelMatcher::maxY).max().orElse(0);
			// y 重叠或间隙 ≤ 3px（OCR 框高差异导致的 1-2px 错位）且无同列冲突 → 合并
			int gap = Math.max(Math.max(curMinY - lastMaxY, lastMinY - curMaxY), 0);
			boolean yClose = lastMaxY > curMinY && curMaxY > lastMinY || gap <= 3;
			if (yClose && !hasOverlappingX(last, row)) {
				last.addAll(row);
			} else {
				merged.add(row);
			}
		}
		return merged;
	}

	/**
	 * 两行是否存在 x 区间重叠的框对（同列冲突）。
	 *
	 * <p>同行错位框分属不同列，x 不重叠；相邻明细行同列值框 x 必然重叠。
	 */
	private static boolean hasOverlappingX(List<PPOcrV6Result> a, List<PPOcrV6Result> b) {
		for (PPOcrV6Result x : a) {
			int x1 = LabelMatcher.minX(x);
			int x2 = LabelMatcher.maxX(x);
			for (PPOcrV6Result y : b) {
				int y1 = LabelMatcher.minX(y);
				int y2 = LabelMatcher.maxX(y);
				if (Math.min(x2, y2) > Math.max(x1, y1)) return true;
			}
		}
		return false;
	}

	/**
	 * 文本字符是否全部来自表头 label（用于排除残缺表头单字框）。
	 */
	private static boolean isAllHeaderChars(String text, Set<Character> headerChars) {
		for (int i = 0; i < text.length(); i++) {
			if (!headerChars.contains(text.charAt(i))) return false;
		}
		return true;
	}

	/**
	 * 明细区结束行判定：命中即终止后续行收集。
	 *
	 * <p>三类信号：
	 * <ol>
	 *   <li>行内任一框含 {@link #BREAK_AREA_LABELS} 标签（价税合计、出行人子表头等）；</li>
	 *   <li>行内 x 拼接文本含"合计/总计"（防 "合"+"计" fragment 拆分漏判）；</li>
	 *   <li>行内任一框以 ¥/￥ 开头（合计行金额/税额必带货币符，明细行不带；
	 *       免疫 "合" 被 OCR 误识为 "1o" 等标签残缺场景）。</li>
	 * </ol>
	 */
	private static boolean isAfterTableRow(List<PPOcrV6Result> row) {
		for (PPOcrV6Result box : row) {
			String text = box.text();
			for (String label : BREAK_AREA_LABELS) {
				if (text.contains(label)) return true;
			}
			// 合计金额/税额框（发票格式约定：合计行金额带 ¥/￥ 前缀，明细行不带）
			if (text.startsWith("￥") || text.startsWith("¥")) return true;
		}
		// fragment 拼接：按 x 升序拼出 "合计"/"总计"（列分配时各列内部重新排序，不受影响）
		row.sort(Comparator.comparingInt(LabelMatcher::minX));
		StringBuilder sb = new StringBuilder();
		for (PPOcrV6Result box : row) {
			sb.append(box.text());
		}
		String joined = sb.toString();
		return joined.contains("合计") || joined.contains("总计");
	}

	/**
	 * 购销方信息行判定：行内任一框含 {@link #PARTY_AREA_LABELS} 标签即跳过。
	 */
	private static boolean isPartyInfoRow(List<PPOcrV6Result> row) {
		for (PPOcrV6Result box : row) {
			String text = box.text();
			for (String label : PARTY_AREA_LABELS) {
				if (text.contains(label)) return true;
			}
		}
		return false;
	}

	private static void setField(InvoiceItem item, String key, String value) {
		switch (key) {
			case "goodsName":
				item.setGoodsName(value);
				break;
			case "unitPrice":
				item.setUnitPrice(value);
				break;
			case "quantity":
				item.setQuantity(value);
				break;
			case "amount":
				item.setAmount(value);
				break;
			case "taxRate":
				item.setTaxRate(value);
				break;
			case "taxAmount":
				item.setTaxAmount(value);
				break;
			default:
				break;
		}
	}

	/**
	 * 文字列（项目名称/货物名称列）噪声过滤：纯数字、单字、纯标点或其它标签不取值。
	 */
	private static boolean isValidGoodsText(String text) {
		String t = text.trim();
		if (t.isEmpty() || t.length() <= 1) return false;
		for (String label : TABLE_FIELD_LABELS) {
			if (t.equals(label) || t.startsWith(label)) return false;
		}
		if (t.matches("\\d+(\\.\\d+)?")) return false;
		if (t.matches("[\\p{Punct}\\s：、,，。.（）()【】\\[\\]\"\"''\\-—/\\\\]+")) return false;
		return true;
	}

	private static TableResult empty(List<ColumnSpec> columns) {
		return new TableResult(new ArrayList<>());
	}
}
