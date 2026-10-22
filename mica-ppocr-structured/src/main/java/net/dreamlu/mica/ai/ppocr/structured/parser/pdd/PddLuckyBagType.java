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

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 拼多多百亿补贴福袋类型枚举。
 *
 * <p>拼多多「百亿补贴」分享图有福袋 / 水果两种形态，版式几乎一致
 * （搜索邀请码 → 组队双方 必得 XX → 大字号邀请码），仅靠文案区分：
 *
 * <table border="1">
 *   <caption>两类分享图文案对照</caption>
 *   <tr><th>类型</th><th>标题</th><th>口号</th><th>背景水印</th></tr>
 *   <tr><td>{@link #LUCKY_BAG}</td><td>百亿补贴 抽福袋</td><td>组队双方 必得现金或券</td><td>百亿补贴 福袋专享</td></tr>
 *   <tr><td>{@link #FRUIT}</td><td>百亿补贴 水果分享券</td><td>组队双方 必得水果券</td><td>百亿补贴 水果专享</td></tr>
 * </table>
 *
 * <p>{@link #UNKNOWN}：文案特征缺失（如只识别出邀请码数字框），无法判定。
 */
@RequiredArgsConstructor
public enum PddLuckyBagType {

	/**
	 * 福袋券：「百亿补贴 抽福袋」，组队双方必得现金或券。
	 */
	LUCKY_BAG("福袋券"),

	/**
	 * 水果券：「百亿补贴 水果分享券」，组队双方必得水果券。
	 */
	FRUIT("水果券"),

	/**
	 * 无法判定（OCR 结果中没有出现两类之一的特征文案）。
	 */
	UNKNOWN("未知");

	/**
	 * 中文描述，便于直接展示给用户。
	 */
	@Getter
	private final String desc;
}
