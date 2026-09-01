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
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * See.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.dreamlu.mica.ai.ppocr.structured.parser.invoice;

import lombok.extern.slf4j.Slf4j;
import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Result;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.LabelMatcher;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.LabelMatcher.LabeledMatch;
import net.dreamlu.mica.ai.ppocr.utils.CollUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 新版电子发票（数电票）OCR 结构化解析器。
 *
 * <p>针对全面数字化电子发票（数电票）版式。核心判别规则：
 * 数电票发票号码固定 20 位（国家税务总局公告 2024 年第 11 号），
 * 老版增值税发票不存在 20 位连续数字字段（8 位发票号码 / 10 位发票代码 /
 * 18 位税号 / ≤19 位银行账号），因此"是否存在 20 位连续数字"零误判。
 *
 * <p>判别失败（非数电票）返回 null，由 {@link InvoiceParser} 分发器回退到
 * {@link VatInvoiceParser}；命中则解析新版字段。
 *
 * <p>数电票典型版式（横版）：
 * <ul>
 *   <li><b>顶部</b>：发票号码 20 位（label+value 合并框）/ 开票日期</li>
 *   <li><b>购方区</b>（左列）：名称 / 统一社会信用代码</li>
 *   <li><b>销方区</b>（右列，同 y）：名称 / 统一社会信用代码</li>
 *   <li><b>明细表</b>：项目名称 / 单价 / 数量 / 金额 / 税率/征收率 / 税额</li>
 *   <li><b>价税合计</b>：（大写）X / （小写）¥X</li>
 *   <li><b>备注</b> / <b>底栏</b>：收款人 / 复核 / 开票人</li>
 * </ul>
 *
 * <p>纯字段解析（不持有推理引擎），无参构造即可直接使用。
 */
@Slf4j
public class ElectronicInvoiceParser {

	/**
	 * 数电票发票号码：固定 20 位连续数字（2024 年第 11 号公告）。
	 * 前后断言排除被更长数字串包裹的场景。
	 */
	private static final Pattern INVOICE_NO_20 = Pattern.compile("(?<![0-9])[0-9]{20}(?![0-9])");

	/**
	 * 开票日期：yyyy年MM月dd日 / yyyy-MM-dd / yyyy/MM/dd。
	 */
	private static final Pattern INVOICE_DATE_PATTERN = Pattern.compile(
		"\\d{4}[-./年]\\d{1,2}[-./月]\\d{1,2}日?");

	/**
	 * 大写金额关键字（3 字以上，简繁体兼容：圆 / 元 / 万 / 萬）。
	 */
	private static final Pattern UPPER_MONEY_PATTERN = Pattern.compile(
		"[零壹贰叁肆伍陆柒捌玖拾佰仟万亿圆元角分整]{3,}");

	/**
	 * 小写金额：¥/￥/? + 数字 + 小数。? 是 OCR 对 ¥ 的常见误识，允许匹配后归一化为 ¥。
	 */
	private static final Pattern LOWER_MONEY_PATTERN = Pattern.compile(
		"[¥￥?]\\s*\\d+(?:\\.\\d{1,2})?");

	/**
	 * 金额数字（允许负数冲账）：-3.33。
	 */
	private static final Pattern AMOUNT_NUM_PATTERN = Pattern.compile(
		"-?\\d+(?:\\.\\d+)?");

	/**
	 * 税率/征收率：3% / 3.5% / 3%（OCR 常在尾部混入噪声字符如 "3%6"）。
	 */
	private static final Pattern TAX_RATE_PATTERN = Pattern.compile(
		"\\d+(?:\\.\\d+)?\\s*%");

	/**
	 * 合并框剥前缀：购销方"名称："前缀。
	 */
	private static final String NAME_PREFIX = "名称：";

	/**
	 * 合并框剥前缀：购销方"统一社会信用代码/纳税人识别号："前缀。
	 */
	private static final String TAX_NO_PREFIX = "统一社会信用代码/纳税人识别号：";

	/**
	 * 字段标签关键字（防止跨字段标签框被误识别为值）。
	 */
	private static final String[] FIELD_LABELS = {
		"项目名称", "单价", "数量", "金额", "税率/征收率", "税额",
		"价税合计（大写）", "（小写）", "(小写)",
		"开票人", "复核", "收款人",
		"备", "注",
		"购买方信息", "销售方信息", "銷售方信息",
	};

	/**
	 * 构造新版电子发票解析器（纯字段解析，不依赖推理引擎）。
	 */
	public ElectronicInvoiceParser() {
	}

	/**
	 * 从 OCR 结果中解析新版电子发票字段。
	 *
	 * <p>先判别：结果中不存在 20 位连续发票号码视为非数电票，返回 null。
	 *
	 * @param results OCR 结果列表
	 * @return 结构化结果；判别失败（非数电票）时返回 null
	 */
	public InvoiceResult parseResults(List<PPOcrV6Result> results) {
		String invoiceNo = findInvoiceNo(results);
		if (invoiceNo == null) {
			return null;
		}
		return doParse(results, invoiceNo);
	}

	// ========================================================================
	// 判别
	// ========================================================================

	/**
	 * 判别：在全部文字框中查找 20 位连续数字（数电票发票号码）。
	 *
	 * @param results OCR 结果列表
	 * @return 命中的 20 位发票号码；未命中返回 null
	 */
	private static String findInvoiceNo(List<PPOcrV6Result> results) {
		for (PPOcrV6Result r : results) {
			Matcher m = INVOICE_NO_20.matcher(r.text());
			if (m.find()) {
				return m.group();
			}
		}
		return null;
	}

	// ========================================================================
	// 字段解析主流程
	// ========================================================================

	private InvoiceResult doParse(List<PPOcrV6Result> results, String invoiceNo) {
		InvoiceResult r = new InvoiceResult();
		r.setRawResults(new ArrayList<>(results));
		r.setInvoiceNo(invoiceNo);

		// 发票号码框坐标
		for (PPOcrV6Result box : results) {
			Matcher m = INVOICE_NO_20.matcher(box.text());
			if (m.find()) {
				LabelMatcher.applyFieldBox(r, "invoiceNo", LabeledMatch.of(invoiceNo, box));
				break;
			}
		}

		parseTop(r, results);
		parseParties(r, results);
		parseTable(r, results);
		parseTotal(r, results);
		parseRemark(r, results);
		parseFooter(r, results);

		return r;
	}

	// ========================================================================
	// 顶部：开票日期
	// ========================================================================

	private void parseTop(InvoiceResult r, List<PPOcrV6Result> results) {
		// 开票日期：扫所有框取首个日期正则
		for (PPOcrV6Result box : results) {
			Matcher m = INVOICE_DATE_PATTERN.matcher(box.text());
			if (m.find()) {
				r.setInvoiceDate(m.group());
				LabelMatcher.applyFieldBox(r, "invoiceDate", LabeledMatch.of(m.group(), box));
				return;
			}
		}
	}

	// ========================================================================
	// 购销双方：左右两列同 y
	// ========================================================================

	private void parseParties(InvoiceResult r, List<PPOcrV6Result> results) {
		// 名称：左列 = 购方，右列 = 销方
		List<PPOcrV6Result> nameBoxes = new ArrayList<>();
		for (PPOcrV6Result box : results) {
			String text = box.text();
			if (text.equals("名称") || text.startsWith(NAME_PREFIX)) {
				nameBoxes.add(box);
			}
		}
		if (nameBoxes.size() >= 2) {
			nameBoxes.sort(Comparator.comparingInt(LabelMatcher::minX));
			PPOcrV6Result buyerBox = nameBoxes.get(0);
			PPOcrV6Result sellerBox = nameBoxes.get(nameBoxes.size() - 1);
			String buyerName = stripPrefix(buyerBox.text(), NAME_PREFIX);
			String sellerName = stripPrefix(sellerBox.text(), NAME_PREFIX);
			r.setBuyerName(buyerName);
			LabelMatcher.applyFieldBox(r, "buyerName", LabeledMatch.of(buyerName, buyerBox));
			r.setSellerName(sellerName);
			LabelMatcher.applyFieldBox(r, "sellerName", LabeledMatch.of(sellerName, sellerBox));
		} else if (nameBoxes.size() == 1) {
			PPOcrV6Result onlyBox = nameBoxes.get(0);
			String name = stripPrefix(onlyBox.text(), NAME_PREFIX);
			r.setBuyerName(name);
			LabelMatcher.applyFieldBox(r, "buyerName", LabeledMatch.of(name, onlyBox));
		}

		// 统一社会信用代码：左列 = 购方，右列 = 销方
		List<PPOcrV6Result> taxBoxes = new ArrayList<>();
		for (PPOcrV6Result box : results) {
			String text = box.text();
			if (text.startsWith(TAX_NO_PREFIX)) {
				taxBoxes.add(box);
			}
		}
		if (taxBoxes.size() >= 2) {
			taxBoxes.sort(Comparator.comparingInt(LabelMatcher::minX));
			PPOcrV6Result buyerTaxBox = taxBoxes.get(0);
			PPOcrV6Result sellerTaxBox = taxBoxes.get(taxBoxes.size() - 1);
			String buyerTaxNo = stripPrefix(buyerTaxBox.text(), TAX_NO_PREFIX);
			String sellerTaxNo = stripPrefix(sellerTaxBox.text(), TAX_NO_PREFIX);
			r.setBuyerTaxNo(buyerTaxNo);
			LabelMatcher.applyFieldBox(r, "buyerTaxNo", LabeledMatch.of(buyerTaxNo, buyerTaxBox));
			r.setSellerTaxNo(sellerTaxNo);
			LabelMatcher.applyFieldBox(r, "sellerTaxNo", LabeledMatch.of(sellerTaxNo, sellerTaxBox));
		} else if (taxBoxes.size() == 1) {
			PPOcrV6Result onlyBox = taxBoxes.get(0);
			String taxNo = stripPrefix(onlyBox.text(), TAX_NO_PREFIX);
			r.setBuyerTaxNo(taxNo);
			LabelMatcher.applyFieldBox(r, "buyerTaxNo", LabeledMatch.of(taxNo, onlyBox));
		}
	}

	/**
	 * 剥前缀：若文本以 prefix 开头，去掉 prefix 及紧跟的标点/空格。
	 */
	private static String stripPrefix(String text, String prefix) {
		if (text == null) return null;
		String stripped = text.substring(prefix.length()).trim();
		return stripped.isEmpty() ? null : stripped;
	}

	// ========================================================================
	// 明细表：项目名称 / 单价 / 数量 / 金额 / 税率/征收率 / 税额
	// ========================================================================

	/**
	 * 电子发票明细表列定义（走公共行聚类解析，列 key 即 InvoiceItem 字段名）。
	 */
	private static final List<InvoiceTableParser.ColumnSpec> ELECTRONIC_COLUMNS = CollUtil.listOf(
		new InvoiceTableParser.ColumnSpec("goodsName", new String[]{"项目名称"}, null, 150),
		new InvoiceTableParser.ColumnSpec("unitPrice", new String[]{"单价"}, AMOUNT_NUM_PATTERN, 40),
		new InvoiceTableParser.ColumnSpec("quantity", new String[]{"数量"}, AMOUNT_NUM_PATTERN, 40),
		new InvoiceTableParser.ColumnSpec("amount", new String[]{"金额"}, AMOUNT_NUM_PATTERN, 40),
		new InvoiceTableParser.ColumnSpec("taxRate", new String[]{"税率/征收率", "税率"}, TAX_RATE_PATTERN, 40),
		new InvoiceTableParser.ColumnSpec("taxAmount", new String[]{"税额"}, AMOUNT_NUM_PATTERN, 40)
	);

	private void parseTable(InvoiceResult r, List<PPOcrV6Result> results) {
		InvoiceTableParser.TableResult table = InvoiceTableParser.parse(results, ELECTRONIC_COLUMNS);
		r.setItems(table.items());
	}

	// ========================================================================
	// 价税合计
	// ========================================================================

	private void parseTotal(InvoiceResult r, List<PPOcrV6Result> results) {
		// 锚定 "价税合计（大写）" 标签，避免误收明细表"合计"行的金额。
		PPOcrV6Result totalLabel = LabelMatcher.findLabelBox(results, "价税合计（大写）");
		if (totalLabel == null) {
			totalLabel = LabelMatcher.findLabelBox(results, "价税合计");
		}

		// 大写：在 totalLabel 右侧或下方找首个 UPPER_MONEY_PATTERN 命中
		if (totalLabel != null) {
			PPOcrV6Result upperValue = findNearbyValueWithPattern(
				results, totalLabel, UPPER_MONEY_PATTERN, true);
			if (upperValue != null) {
				String upper = extractUpperMoney(upperValue.text());
				if (upper != null) {
					r.setTotalAmountUpper(upper);
					LabelMatcher.applyFieldBox(r, "totalAmountUpper",
						LabeledMatch.of(upper, upperValue));
				}
			}
		}
		// 兜底：扫描全部找连续大写金额字
		if (r.getTotalAmountUpper() == null) {
			for (PPOcrV6Result box : results) {
				String upper = extractUpperMoney(box.text());
				if (upper != null) {
					r.setTotalAmountUpper(upper);
					LabelMatcher.applyFieldBox(r, "totalAmountUpper", LabeledMatch.of(upper, box));
					break;
				}
			}
		}

		// 小写：优先在 "(小写)" / "（小写）" 标签框（含合并 value）提取；否则用锚定法
		boolean lowerFound = false;
		for (PPOcrV6Result box : results) {
			String text = box.text();
			if (text.contains("（小写）") || text.contains("(小写)")) {
				Matcher m = LOWER_MONEY_PATTERN.matcher(text);
				if (m.find()) {
					String lower = normalizeMoneyPrefix(m.group());
					r.setTotalAmountLower(lower);
					LabelMatcher.applyFieldBox(r, "totalAmountLower", LabeledMatch.of(lower, box));
					lowerFound = true;
					break;
				}
			}
		}
		// 兜底：锚定 "价税合计" 下方找小写金额
		if (!lowerFound && totalLabel != null) {
			PPOcrV6Result lowerValue = findNearbyValueWithPattern(
				results, totalLabel, LOWER_MONEY_PATTERN, false);
			if (lowerValue != null) {
				Matcher m = LOWER_MONEY_PATTERN.matcher(lowerValue.text());
				if (m.find()) {
					String lower = normalizeMoneyPrefix(m.group());
					r.setTotalAmountLower(lower);
					LabelMatcher.applyFieldBox(r, "totalAmountLower",
						LabeledMatch.of(lower, lowerValue));
				}
			}
		}
	}

	/**
	 * 在 labelBox 附近找首个 text 匹配 pattern 的值框（右侧同行优先，下方 fallback）。
	 */
	private static PPOcrV6Result findNearbyValueWithPattern(List<PPOcrV6Result> results,
															PPOcrV6Result labelBox,
															Pattern pattern,
															boolean preferHorizontal) {
		int labelCenterX = (LabelMatcher.minX(labelBox) + LabelMatcher.maxX(labelBox)) / 2;
		int labelMaxX = LabelMatcher.maxX(labelBox);
		int labelMinY = LabelMatcher.minY(labelBox);
		int labelMaxY = LabelMatcher.maxY(labelBox);
		int labelCenterY = (labelMinY + labelMaxY) / 2;
		PPOcrV6Result best = null;
		int bestScore = Integer.MAX_VALUE;
		for (PPOcrV6Result box : results) {
			if (box == labelBox) continue;
			String text = box.text();
			if (!pattern.matcher(text).find()) continue;
			int x0 = LabelMatcher.minX(box);
			int centerX = (x0 + LabelMatcher.maxX(box)) / 2;
			int minYr = LabelMatcher.minY(box);
			int maxYr = LabelMatcher.maxY(box);
			int centerYr = (minYr + maxYr) / 2;
			boolean rightOverlap = centerX > labelCenterX
				&& !(maxYr < labelMinY || minYr > labelMaxY);
			boolean belowInColumn = (x0 <= labelMaxX && LabelMatcher.maxX(box) >= LabelMatcher.minX(labelBox))
				&& minYr > labelMaxY;
			if (!rightOverlap && !belowInColumn) continue;
			int xDistFromLabelRight = x0 > labelMaxX ? x0 - labelMaxX : 0;
			int yCenterDiff = Math.abs(centerYr - labelCenterY);
			int score;
			if (preferHorizontal && rightOverlap) {
				score = yCenterDiff * 1000 + xDistFromLabelRight;
			} else if (rightOverlap) {
				score = yCenterDiff * 1000 + xDistFromLabelRight + 100;  // 偏右但次选
			} else {
				score = 1_000_000 + (minYr - labelMaxY) * 1000 + x0;
			}
			if (score < bestScore) {
				bestScore = score;
				best = box;
			}
		}
		return best;
	}

	/**
	 * 从文本中抽取大写金额（≥3 字）。OCR 可能输出"⊙贰拾壹圆..."（前缀 ⊗ 等符号），正则只取金额字。
	 */
	private static String extractUpperMoney(String text) {
		Matcher m = UPPER_MONEY_PATTERN.matcher(text);
		if (m.find() && m.group().length() >= 3) {
			return m.group();
		}
		return null;
	}

	/**
	 * 归一化金额前缀：OCR 误识 ¥ 为 ? 时还原为 ¥。
	 */
	private static String normalizeMoneyPrefix(String money) {
		if (money == null || money.isEmpty()) return money;
		char first = money.charAt(0);
		if (first == '?') {
			return "¥" + money.substring(1).trim();
		}
		return money.trim();
	}

	// ========================================================================
	// 备注
	// ========================================================================

	private void parseRemark(InvoiceResult r, List<PPOcrV6Result> results) {
		// 备注：找"备"+"注"fragment 标签（可能拆成两框），向右找首个非空非标签内容
		List<PPOcrV6Result> remarkLabels = new ArrayList<>();
		for (PPOcrV6Result box : results) {
			String text = box.text().trim();
			if (text.equals("备") || text.equals("注") || text.equals("备注")) {
				remarkLabels.add(box);
			}
		}
		if (remarkLabels.isEmpty()) return;
		remarkLabels.sort(Comparator.comparingInt(LabelMatcher::minY));
		PPOcrV6Result labelBox = remarkLabels.get(0);
		int labelCenterX = (LabelMatcher.minX(labelBox) + LabelMatcher.maxX(labelBox)) / 2;
		int labelMinY = LabelMatcher.minY(labelBox);
		int labelMaxY = LabelMatcher.maxY(labelBox);

		List<PPOcrV6Result> candidates = new ArrayList<>();
		for (PPOcrV6Result box : results) {
			if (remarkLabels.contains(box)) continue;
			String text = box.text().trim();
			if (text.isEmpty()) continue;
			if (isFieldLabel(text)) continue;
			int centerX = (LabelMatcher.minX(box) + LabelMatcher.maxX(box)) / 2;
			int centerY = (LabelMatcher.minY(box) + LabelMatcher.maxY(box)) / 2;
			int maxYr = LabelMatcher.maxY(box);
			if (maxYr < labelMinY - 30) continue;
			if (LabelMatcher.minY(box) > labelMaxY + 100) continue;
			if (centerX > labelCenterX) {
				if (Math.abs(centerY - (labelMinY + labelMaxY) / 2) > Math.max(20, (labelMaxY - labelMinY))) continue;
			}
			candidates.add(box);
		}
		candidates.sort(Comparator.comparingInt(LabelMatcher::minY));
		StringBuilder sb = new StringBuilder();
		List<PPOcrV6Result> matches = new ArrayList<>();
		for (PPOcrV6Result box : candidates) {
			if (sb.length() > 0) sb.append('\n');
			sb.append(box.text().trim());
			matches.add(box);
		}
		String remark = sb.toString().trim();
		if (!remark.isEmpty()) {
			r.setRemark(remark);
			LabelMatcher.applyFieldBox(r, "remark", LabeledMatch.of(remark, matches));
		}
	}

	private static boolean isFieldLabel(String text) {
		for (String label : FIELD_LABELS) {
			if (text.equals(label) || text.startsWith(label)) return true;
		}
		return false;
	}

	// ========================================================================
	// 底栏：收款人 / 复核 / 开票人
	// ========================================================================

	private void parseFooter(InvoiceResult r, List<PPOcrV6Result> results) {
		matchFooterField(r, results, "收款人", "payee");
		matchFooterField(r, results, "复核", "reviewer");
		matchFooterField(r, results, "开票人", "issuer");
	}

	private static void matchFooterField(InvoiceResult r,
										 List<PPOcrV6Result> results,
										 String label,
										 String fieldName) {
		for (PPOcrV6Result box : results) {
			String text = box.text();
			if (text.equals(label)) {
				String right = findRightValue(results, box);
				if (right != null) {
					setField(r, fieldName, right, box);
				}
				return;
			}
			if (text.startsWith(label) && text.length() > label.length()
				&& (text.charAt(label.length()) == ':' || text.charAt(label.length()) == '：')) {
				String stripped = text.substring(label.length() + 1).trim();
				if (!stripped.isEmpty()) {
					setField(r, fieldName, stripped, box);
				}
				return;
			}
		}
	}

	private static String findRightValue(List<PPOcrV6Result> results, PPOcrV6Result labelBox) {
		int labelMinY = LabelMatcher.minY(labelBox);
		int labelMaxY = LabelMatcher.maxY(labelBox);
		int labelMaxX = LabelMatcher.maxX(labelBox);
		PPOcrV6Result best = null;
		int bestX = Integer.MAX_VALUE;
		for (PPOcrV6Result box : results) {
			if (box == labelBox) continue;
			String text = box.text().trim();
			if (text.isEmpty() || isFieldLabel(text)) continue;
			int x0 = LabelMatcher.minX(box);
			if (x0 <= labelMaxX) continue;
			int maxYr = LabelMatcher.maxY(box);
			int minYr = LabelMatcher.minY(box);
			if (maxYr < labelMinY || minYr > labelMaxY) continue;
			if (x0 < bestX) {
				bestX = x0;
				best = box;
			}
		}
		return best == null ? null : best.text().trim();
	}

	private static void setField(InvoiceResult r, String fieldName, String value, PPOcrV6Result box) {
		if ("payee".equals(fieldName)) {
			r.setPayee(value);
		} else if ("reviewer".equals(fieldName)) {
			r.setReviewer(value);
		} else if ("issuer".equals(fieldName)) {
			r.setIssuer(value);
		}
		LabelMatcher.applyFieldBox(r, fieldName, LabeledMatch.of(value, box));
	}
}