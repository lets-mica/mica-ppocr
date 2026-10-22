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

import lombok.Data;
import lombok.EqualsAndHashCode;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.BaseStructuredResult;

/**
 * 拼多多福袋 OCR 结构化解析结果。
 *
 * <p>典型版面：「百亿补贴 抽福袋 / 搜索邀请码 / 组队双方 必得现金或券 /
 * 1 打开拼多多 APP / 2 搜索以下数字邀请码 / 92463725」；
 * 重复水印"百亿补贴 福袋专享"会出现在背景。
 *
 * <p>福袋有两种形态（福袋券 / 水果券），版式一致，靠标题、口号与水印文案区分，
 * 见 {@link PddLuckyBagType}。
 *
 * <p>继承 {@link BaseStructuredResult}：
 * <ul>
 *   <li>{@code rawResults} —— 原始 OCR 结果（含所有文字框）</li>
 *   <li>{@code fieldBoxes} —— 字段名 → 对应 OCR 框坐标（key: luckyBagCode、type）</li>
 * </ul>
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PddLuckyBagResult extends BaseStructuredResult {

	/**
	 * 福袋码（拼多多 8 位数字邀请码，例如 "92463725"）
	 */
	private String luckyBagCode;

	/**
	 * 福袋类型：福袋券（现金 / 折扣券）/ 水果券。
	 *
	 * <p>由标题、口号、背景水印文案判定；判不出来时为 {@link PddLuckyBagType#UNKNOWN}。
	 */
	private PddLuckyBagType type;
}
