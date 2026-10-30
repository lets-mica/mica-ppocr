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

/**
 * 标签匹配的命中状态。
 *
 * <p><b>解决什么问题</b>：原来"没取到值"只有一个结果 —— {@code null}。
 * 但 {@code null} 至少混着两种<b>性质完全不同</b>的情况：
 * <ul>
 *   <li>{@link #NOT_FOUND} —— 这个字段<b>本来就不在这张图上</b>
 *       （例如身份证反面上根本没有"姓名"），属正常，无需处理；</li>
 *   <li>{@link #LABEL_WITHOUT_VALUE} —— 标签<b>在</b>，但值<b>没识别出来</b>
 *       （标签框找到了，右侧却空着），这是 OCR 漏识别，<b>值得复核</b>。</li>
 * </ul>
 * 不区分这两者，要么把正常情况误报成异常，要么把真异常当成正常放过。
 *
 * @see LabelMatcher.LabeledMatch
 */
public enum LabelMatchStatus {

	/**
	 * 命中：标签与值都取到了。
	 */
	FOUND,

	/**
	 * 找到标签但没取到值：标签框存在，右侧/下方没有合格的值框。
	 *
	 * <p>典型场景：值被 OCR 漏识别，或值被噪声框顶替。
	 */
	LABEL_WITHOUT_VALUE,

	/**
	 * 没找到标签：整张图上都没有这个标签。
	 *
	 * <p>通常意味着该字段本就不在这张图的当前版面（如身份证反面的"姓名"）。
	 */
	NOT_FOUND
}
