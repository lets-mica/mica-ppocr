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
 * 校验位规则的判定结果状态。
 *
 * <p>刻意区分三态而非简单的 true/false：OCR 认出来的号码<b>可能压根不是该格式</b>
 * （长度不对、含非法字符），此时既不能说"通过"，也不能说"不通过" ——
 * 说"不通过"会误导下游去复核一个根本没识别对的值。
 *
 * @see ValidationResult
 */
public enum ValidationStatus {

	/**
	 * 校验通过：格式合法，且校验位自洽。
	 */
	PASS,

	/**
	 * 校验不通过：格式合法（长度、字符集都对），但校验位与期望值不符。
	 *
	 * <p>这是最有价值的信号 —— 意味着 OCR 至少认错了<b>一个字符</b>，
	 * 识别结果不可直接采信。
	 */
	FAIL,

	/**
	 * 无法校验：格式本身就不合法（长度不对、含非法字符等），无从谈起校验位。
	 *
	 * <p>此时不能据此判定识别错误 —— 只说明这个值"长得就不像"该类证件号。
	 */
	UNCHECKABLE
}
