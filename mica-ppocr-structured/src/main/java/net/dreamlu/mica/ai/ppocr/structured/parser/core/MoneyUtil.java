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

import lombok.experimental.UtilityClass;

/**
 * 金额货币符号归一化：把金额字段的货币符号统一成半角 {@code ¥}（U+00A5）。
 *
 * <p><b>解决什么问题</b>：票面上的人民币符号有全角（{@code ￥}，U+FFE5）与
 * 半角（{@code ¥}，U+00A5）两种字形 —— 老版蓝票用全角、新版电子票用半角。
 * rec 模型忠实照抄票面字形，于是同一个字段在不同单据上会输出两种符号，
 * 下游做 {@code startsWith("￥")} 或字符串等值比对时会漏掉一半样本。
 *
 * <p>此外 OCR 偶尔把 {@code ¥} 认成 {@code ?}（发票「价税合计（小写）」里常见），
 * 本工具一并还原。
 *
 * <p><b>为什么选半角</b>：
 * <ol>
 *   <li>它是仓库既有口径 —— 发票解析器的货币符前缀早已固定返回半角 {@code ¥}；</li>
 *   <li>它是 Unicode 标准的 YEN SIGN（U+00A5），JSON / 前端 / 国际化链路更通用，
 *       而全角 {@code ￥}（U+FFE5）是中文排版专用变体。</li>
 * </ol>
 *
 * <p>⚠️ 半角 {@code ¥} 在 GBK 编码里<b>没有码位</b>
 * （{@code Charset.forName("GBK").newEncoder().canEncode("¥") == false}），
 * 打印到 Windows 控制台会被替换成 {@code ?} —— 调试工具需按 UTF-8 输出才看得到真值。
 *
 * <p>本类为纯函数，零依赖、线程安全。
 */
@UtilityClass
public class MoneyUtil {

	/**
	 * 半角人民币符号（U+00A5）—— 全仓库金额字段的统一口径。
	 */
	public static final String YUAN = "¥";

	/**
	 * 全角人民币符号（U+FFE5）—— 票面字形，归一化为半角。
	 */
	private static final char YUAN_FULL = '￥';

	/**
	 * OCR 把 {@code ¥} 认错时的输出字符 —— 还原为半角。
	 */
	private static final char OCR_CONFUSED_YUAN = '?';

	/**
	 * 归一化金额串的货币符号前缀。
	 *
	 * <p>全角 {@code ￥} 与 OCR 误识的 {@code ?} 一律还原为半角 {@code ¥}；
	 * 已是半角、或本来就没有货币符号（如出租车票的纯数字金额）时原样返回。
	 *
	 * @param money 金额串，如 {@code "￥26.00元"}；可为 null
	 * @return 归一化后的金额串；入参为 null 时返回 null
	 */
	public static String normalizeYuanPrefix(String money) {
		if (money == null || money.isEmpty()) {
			return money;
		}
		char first = money.charAt(0);
		if (first == YUAN_FULL || first == OCR_CONFUSED_YUAN) {
			return YUAN + money.substring(1).trim();
		}
		return money;
	}
}
