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

package net.dreamlu.mica.ai.ppocr.structured.parser.generic;

import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Result;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.LabelMatcher;

import java.util.List;
import java.util.Set;

/**
 * 通用层的值定位：标签 → 值。
 *
 * <p><b>与存量 {@code LabelMatcher} 的分工</b>：{@code LabelMatcher} 是 10 个强类型
 * 解析器共用的公共骨架，行为<b>一行都不动</b>；本类是通用层<b>内部</b>专用的值定位，
 * 采用更严的三层判定，避免同排多标签时抢错值。
 *
 * <p><b>三层判定</b>（候选值必须同时满足）：
 * <ol>
 *   <li><b>垂直重叠率 ≥ 0.3</b> —— 值框与标签框在 y 方向的重叠占较矮者高度的比例。
 *       比"必须完全包含"宽松，能覆盖旋转卡片与轻微错位；</li>
 *   <li><b>水平间距 ≤ 标签高度 × 8</b> —— 远处的同排文本不会被顺手当成值；</li>
 *   <li><b>值归属最近的标签</b> —— 若另一个标签离该候选值更近（横亘在中间，
 *       或与候选值框水平相交），则该值不属于当前标签。</li>
 * </ol>
 *
 * <p>第 3 条是关键：驾照版面里「性别」的真值「女」没被检出时，若不做归属判定，
 * 就会越过「国籍」标签抓到「Nationaliy中国」，静默产出错误字段 ——
 * 宁可判定为缺失，也不能给错值。
 *
 * <p>本类为包级可见的内部实现，不对外暴露。
 */
final class ValueLocator {

	/**
	 * 垂直重叠率下限。
	 */
	static final double DEFAULT_MIN_OVERLAP = 0.3;

	/**
	 * 水平间距上限，相对标签高度的倍数。
	 */
	static final double DEFAULT_MAX_GAP_RATIO = 8.0;

	/**
	 * 允许值框左边界越过标签右边界的容差，相对标签高度的倍数。
	 *
	 * <p>OCR 检测框边界并不精确，相邻框常有小幅重叠；容差过紧会漏掉这类值。
	 */
	static final double OVERLAP_TOLERANCE_RATIO = 0.5;

	/**
	 * 工具类，禁止实例化。
	 */
	private ValueLocator() {
		throw new AssertionError("No ValueLocator instances for you!");
	}

	/**
	 * 在标签框右侧定位字段值。
	 *
	 * @param results   全部 OCR 结果
	 * @param labelHit  标签命中信息
	 * @param labelBoxes 全局标签框集合（用于"值归属最近标签"判定）
	 * @return 定位到的值；未命中返回 null
	 */
	static LocatedValue locate(List<PPOcrV6Result> results,
							   LabelMatcher.LabelHit labelHit,
							   Set<PPOcrV6Result> labelBoxes) {
		if (results == null || results.isEmpty() || labelHit == null || labelHit.box() == null) {
			return null;
		}
		PPOcrV6Result label = labelHit.box();

		// 情形一：标签与值合并在同一框（"合同编号：HT-2024-001"）——直接剥出值。
		String merged = stripLabelPrefix(label.text(), labelHit.matchedLabel());
		if (merged != null) {
			return new LocatedValue(merged, label);
		}

		// 情形二：值是标签右侧的独立框。
		int labelRight = maxX(label);
		int labelTop = minY(label);
		int labelBottom = maxY(label);
		int labelHeight = Math.max(1, labelBottom - labelTop);
		double tolerance = labelHeight * OVERLAP_TOLERANCE_RATIO;
		double maxGap = labelHeight * DEFAULT_MAX_GAP_RATIO;

		PPOcrV6Result best = null;
		double bestScore = Double.NEGATIVE_INFINITY;
		for (PPOcrV6Result cand : results) {
			if (cand == label || cand == null || cand.box() == null) {
				continue;
			}
			if (labelBoxes != null && labelBoxes.contains(cand)) {
				// 标签框本身不当值用（否则"性别"标签可能被当成"国籍"的值）。
				continue;
			}
			String text = cand.text();
			if (text == null) {
				continue;
			}
			String trimmed = text.trim();
			if (trimmed.isEmpty()) {
				continue;
			}
			if (trimmed.matches("[A-Za-z\\s]+")) {
				// 纯拉丁字母的框多为噪声，与存量 LabelMatcher 口径保持一致；
				// 真正需要英文值时用 schema 的正则兜底路径。
				continue;
			}
			int candLeft = minX(cand);
			if (candLeft < labelRight - tolerance) {
				continue;
			}
			double gap = Math.max(0, candLeft - labelRight);
			if (gap > maxGap) {
				continue;
			}
			double overlap = verticalOverlap(labelTop, labelBottom, minY(cand), maxY(cand));
			if (overlap < DEFAULT_MIN_OVERLAP) {
				continue;
			}
			if (!isNearestLabel(label, cand, labelBoxes, DEFAULT_MIN_OVERLAP, labelHeight)) {
				continue;
			}
			// 重叠越高越好，间距越小越好；间距的权重远低于重叠。
			double score = overlap - gap / (maxGap * 100);
			if (score > bestScore) {
				bestScore = score;
				best = cand;
			}
		}
		if (best == null) {
			return null;
		}
		String value = stripLabelPrefix(best.text(), labelHit.matchedLabel());
		return new LocatedValue(value == null ? best.text() : value, best);
	}

	/**
	 * 判断 {@code self} 是否为候选值最近的左侧标签。
	 *
	 * <p>只比较与候选值垂直重叠、且位于 {@code self} 右侧的标签：
	 * <ul>
	 *   <li>该标签<b>横亘</b>在 {@code self} 与候选值之间 —— 候选值不属于 {@code self}；</li>
	 *   <li>该标签与候选值框<b>水平相交</b> —— 候选值不属于 {@code self}。</li>
	 * </ul>
	 *
	 * @param self        当前标签框
	 * @param cand        候选值框
	 * @param labelBoxes  全局标签框集合
	 * @param minOverlap  垂直重叠率下限
	 * @param labelHeight 标签高度
	 * @return true 表示候选值归属当前标签
	 */
	private static boolean isNearestLabel(PPOcrV6Result self, PPOcrV6Result cand,
										  Set<PPOcrV6Result> labelBoxes,
										  double minOverlap, int labelHeight) {
		if (labelBoxes == null || labelBoxes.isEmpty()) {
			return true;
		}
		int selfRight = maxX(self);
		int candLeft = minX(cand);
		int candRight = maxX(cand);
		int candTop = minY(cand);
		int candBottom = maxY(cand);
		double tolerance = labelHeight * OVERLAP_TOLERANCE_RATIO;

		for (PPOcrV6Result other : labelBoxes) {
			if (other == null || other == self || other == cand || other.box() == null) {
				continue;
			}
			int otherLeft = minX(other);
			int otherRight = maxX(other);
			if (otherRight <= selfRight) {
				// 只看位于当前标签右侧的标签。
				continue;
			}
			if (verticalOverlap(candTop, candBottom, minY(other), maxY(other)) < minOverlap) {
				continue;
			}
			if (otherRight <= candLeft + tolerance) {
				// 情形一：另一个标签横亘在当前标签与候选值之间。
				return false;
			}
			if (otherLeft < candRight && otherRight > candLeft) {
				// 情形二：另一个标签与候选值框水平相交 —— 候选值属于它。
				return false;
			}
		}
		return true;
	}

	/**
	 * 剥掉文本开头的标签前缀（含其后紧跟的分隔标点）。
	 *
	 * @param text      原始文本
	 * @param labelText 标签文本
	 * @return 剥掉前缀后的值；不是合并框形态时返回 null
	 */
	private static String stripLabelPrefix(String text, String labelText) {
		if (text == null || labelText == null || labelText.isEmpty()) {
			return null;
		}
		if (!text.startsWith(labelText) || text.length() <= labelText.length()) {
			return null;
		}
		String stripped = text.substring(labelText.length());
		int start = 0;
		while (start < stripped.length() && isLabelPunct(stripped.charAt(start))) {
			start++;
		}
		stripped = stripped.substring(start).trim();
		return stripped.isEmpty() ? null : stripped;
	}

	/**
	 * 判定字符是否属于"标签-值"分隔标点。
	 *
	 * @param c 字符
	 * @return true 表示是分隔标点
	 */
	private static boolean isLabelPunct(char c) {
		return c == ':' || c == '：' || c == '、' || c == ' '
			|| c == ',' || c == '，' || c == ';' || c == '；';
	}

	/**
	 * 两个 y 区间的重叠长度占较矮者高度的比例。
	 *
	 * @param aTop    区间 A 顶部
	 * @param aBottom 区间 A 底部
	 * @param bTop    区间 B 顶部
	 * @param bBottom 区间 B 底部
	 * @return 重叠率，[0, 1]
	 */
	private static double verticalOverlap(int aTop, int aBottom, int bTop, int bBottom) {
		int lo = Math.max(aTop, bTop);
		int hi = Math.min(aBottom, bBottom);
		int inter = Math.max(0, hi - lo);
		int shorter = Math.max(1, Math.min(aBottom - aTop, bBottom - bTop));
		return (double) inter / shorter;
	}

	/**
	 * 取框的最小 x。
	 *
	 * @param r OCR 结果
	 * @return 最小 x
	 */
	private static int minX(PPOcrV6Result r) {
		int v = Integer.MAX_VALUE;
		for (int[] p : r.box()) {
			v = Math.min(v, p[0]);
		}
		return v;
	}

	/**
	 * 取框的最大 x。
	 *
	 * @param r OCR 结果
	 * @return 最大 x
	 */
	private static int maxX(PPOcrV6Result r) {
		int v = Integer.MIN_VALUE;
		for (int[] p : r.box()) {
			v = Math.max(v, p[0]);
		}
		return v;
	}

	/**
	 * 取框的最小 y。
	 *
	 * @param r OCR 结果
	 * @return 最小 y
	 */
	private static int minY(PPOcrV6Result r) {
		int v = Integer.MAX_VALUE;
		for (int[] p : r.box()) {
			v = Math.min(v, p[1]);
		}
		return v;
	}

	/**
	 * 取框的最大 y。
	 *
	 * @param r OCR 结果
	 * @return 最大 y
	 */
	private static int maxY(PPOcrV6Result r) {
		int v = Integer.MIN_VALUE;
		for (int[] p : r.box()) {
			v = Math.max(v, p[1]);
		}
		return v;
	}

	/**
	 * 定位到的值：文本 + 来源框。
	 */
	static final class LocatedValue {

		/**
		 * 字段值文本。
		 */
		private final String value;

		/**
		 * 来源框（值框或合并框）。
		 */
		private final PPOcrV6Result box;

		/**
		 * 构造定位结果。
		 *
		 * @param value 字段值文本
		 * @param box   来源框
		 */
		LocatedValue(String value, PPOcrV6Result box) {
			this.value = value;
			this.box = box;
		}

		/**
		 * 字段值文本。
		 *
		 * @return 字段值文本
		 */
		String value() {
			return value;
		}

		/**
		 * 来源框。
		 *
		 * @return 来源框
		 */
		PPOcrV6Result box() {
			return box;
		}
	}
}
