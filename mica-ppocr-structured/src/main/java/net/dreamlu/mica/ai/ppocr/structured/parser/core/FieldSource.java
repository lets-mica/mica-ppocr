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
 * 字段值的来源。
 *
 * <p>同一个字段，取值路径不同，置信度基线也不同 ——
 * "标签定位"是最强证据，"正则兜底"说明标签没找到，"推算/回填"则说明值根本不是 OCR 直接读出来的。
 *
 * @see FieldConfidence
 */
public enum FieldSource {

	/**
	 * 标签定位命中：按字段标签找到标签框，再取其右侧/下方的值框。
	 *
	 * <p>置信度基线最高 —— 版面关系本身就是强证据。
	 */
	LABEL,

	/**
	 * 内容兜底命中：标签没找到，靠正则/关键字在全文里捞。
	 *
	 * <p>比 {@link #LABEL} 略低一档：捞对了是运气，捞错了也看不出区别。
	 */
	PATTERN,

	/**
	 * 推算得出：由同一证件的其它字段按规则算出（如出生日期从身份证号推算）。
	 *
	 * <p>不依赖 OCR 文本，准确性取决于源字段本身是否可靠。
	 */
	DERIVED,

	/**
	 * 回填得出：源字段缺失时用其它证据补齐（如性别从身份证号的性别位回填）。
	 *
	 * <p>与 {@link #DERIVED} 同源，但语义是"救回"而非"换算"。
	 */
	BACKFILL,

	/**
	 * 未取到值。
	 */
	MISSING
}
