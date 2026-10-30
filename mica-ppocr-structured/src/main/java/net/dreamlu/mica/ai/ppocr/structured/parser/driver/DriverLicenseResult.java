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

import lombok.Data;
import lombok.EqualsAndHashCode;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.BaseStructuredResult;

/**
 * 机动车驾驶证 OCR 结构化解析结果。
 *
 * <p>典型字段：证号、姓名、性别、国籍、住址、出生日期、首次领证日期、准驾车型、
 * 有效期限起始、有效期限截止。
 *
 * <p>继承 {@link BaseStructuredResult}：
 * <ul>
 *   <li>{@code rawResults} —— 原始 OCR 结果（含所有文字框）</li>
 *   <li>{@code fieldBoxes} —— 字段名 → 对应 OCR 框坐标（key 即上面的字段名）。
 *       本解析器走文本版标签匹配，<b>不回填</b>该 map（始终为空）；需要坐标请用 {@code rawResults} 自行定位。</li>
 * </ul>
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class DriverLicenseResult extends BaseStructuredResult {
	/**
	 * 证号
	 */
	private String licenseNumber;
	/**
	 * 姓名
	 */
	private String name;
	/**
	 * 性别
	 */
	private String gender;
	/**
	 * 国籍
	 */
	private String nationality;
	/**
	 * 住址
	 */
	private String address;
	/**
	 * 出生日期
	 */
	private String birthDate;
	/**
	 * 首次领证日期
	 */
	private String issueDate;
	/**
	 * 准驾车型
	 */
	private String vehicleClass;
	/**
	 * 签发机关
	 */
	private String issuingAuthority;
	/**
	 * 有效期限起始日期
	 */
	private String validFrom;
	/**
	 * 有效期限截止日期
	 */
	private String validTo;
}
