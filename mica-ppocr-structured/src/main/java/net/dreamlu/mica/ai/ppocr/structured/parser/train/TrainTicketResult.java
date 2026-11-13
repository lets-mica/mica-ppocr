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

package net.dreamlu.mica.ai.ppocr.structured.parser.train;

import lombok.Data;
import lombok.EqualsAndHashCode;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.BaseStructuredResult;

/**
 * 火车票 OCR 结构化解析结果（对齐百度 OCR 火车票接口字段）。
 *
 * <p>典型字段（13 个核心 + 5 个辅助）：
 * <ul>
 *   <li><b>行程</b>：始发站、到达站、车次、出发日期、出发时间、座位号、席别</li>
 *   <li><b>乘客</b>：乘客姓名、身份证号</li>
 *   <li><b>金额</b>：车票金额、不含税金额</li>
 *   <li><b>票号</b>：车票号、发票号码、电子客票号</li>
 *   <li><b>其他</b>：开票日期、售站、序列号、改签标识</li>
 * </ul>
 *
 * <p>继承 {@link BaseStructuredResult}：
 * <ul>
 *   <li>{@code rawResults} —— 原始 OCR 结果（含所有文字框）</li>
 *   <li>{@code fieldBoxes} —— 字段名 → 对应 OCR 框坐标（key 见各字段上方注释）</li>
 * </ul>
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class TrainTicketResult extends BaseStructuredResult {

	// ========================================================================
	// 行程
	// ========================================================================

	/**
	 * 始发站
	 */
	private String departure;
	/**
	 * 到达站
	 */
	private String arrival;
	/**
	 * 车次（如 G123、D456、K789、T1234）
	 */
	private String trainNumber;
	/**
	 * 出发日期（格式 yyyy年MM月dd日）
	 */
	private String departureDate;
	/**
	 * 出发时间（格式 HH:mm）
	 */
	private String departureTime;
	/**
	 * 座位号（如 05车12A号）
	 */
	private String seatNumber;
	/**
	 * 席别（一等座/二等座/硬座/软卧/商务座）
	 */
	private String seatClass;

	// ========================================================================
	// 乘客
	// ========================================================================

	/**
	 * 乘客姓名
	 */
	private String passengerName;
	/**
	 * 乘客身份证号（部分票会脱敏显示，需兼容星号）
	 */
	private String idNumber;

	// ========================================================================
	// 金额
	// ========================================================================

	/**
	 * 车票金额（格式 ¥26.00元，货币符号统一为半角 ¥）
	 */
	private String amount;
	/**
	 * 不含税金额（格式 ¥23.01元，货币符号统一为半角 ¥）
	 */
	private String amountExcludingTax;

	// ========================================================================
	// 票号
	// ========================================================================

	/**
	 * 车票号（10 位数字）
	 */
	private String ticketNo;
	/**
	 * 发票号码（电子客票 20 位）
	 */
	private String invoiceNo;
	/**
	 * 电子客票号（25 位数字）
	 */
	private String eTicketNo;

	// ========================================================================
	// 其他
	// ========================================================================

	/**
	 * 开票日期
	 */
	private String invoiceDate;
	/**
	 * 售站
	 */
	private String sellStation;
	/**
	 * 序列号
	 */
	private String serialNumber;
	/**
	 * 改签标识（始发改签 / 退票等）
	 */
	private String changedFlag;
}
