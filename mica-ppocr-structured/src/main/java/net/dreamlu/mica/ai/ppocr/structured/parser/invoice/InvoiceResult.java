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

package net.dreamlu.mica.ai.ppocr.structured.parser.invoice;

import lombok.Data;
import lombok.EqualsAndHashCode;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.BaseStructuredResult;

import java.util.List;

/**
 * 发票 OCR 结构化解析结果（老版增值税发票 + 新版电子发票统一结果集）。
 *
 * <p>老字段：发票代码/号码/日期，购销双方名称/税号/地址电话/开户行账号，
 * 商品/金额/税率/税额，价税合计大写与小写，收款人/复核人/开票人。
 *
 * <p>新版电子发票专属：{@code remark}（备注），以及由分发器标注的 {@code version}（版型）。
 * 老版没有的字段为 null。
 *
 * <p>明细：{@code items} 为行聚类结构化的明细行列表（列对齐正确，两版发票共用）。
 *
 * <p>继承 {@link BaseStructuredResult}：
 * <ul>
 *   <li>{@code rawResults} —— 原始 OCR 结果（含所有文字框）</li>
 *   <li>{@code fieldBoxes} —— 字段名 → 对应 OCR 框坐标（key 见各字段上方注释）</li>
 * </ul>
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class InvoiceResult extends BaseStructuredResult {
	/**
	 * 发票版型（由 {@link InvoiceParser} 分发器标注；直接调用子解析器时为 null）
	 */
	private InvoiceVersion version;
	/**
	 * 发票代码
	 */
	private String invoiceCode;
	/**
	 * 发票号码
	 */
	private String invoiceNo;
	/**
	 * 开票日期
	 */
	private String invoiceDate;

	/**
	 * 购买方名称
	 */
	private String buyerName;
	/**
	 * 购买方税号（统一社会信用代码/纳税人识别号）
	 */
	private String buyerTaxNo;
	/**
	 * 购买方地址、电话
	 */
	private String buyerAddressPhone;
	/**
	 * 购买方开户行、账号
	 */
	private String buyerBankAccount;

	/**
	 * 销售方名称
	 */
	private String sellerName;
	/**
	 * 销售方税号
	 */
	private String sellerTaxNo;
	/**
	 * 销售方地址、电话
	 */
	private String sellerAddressPhone;
	/**
	 * 销售方开户行、账号
	 */
	private String sellerBankAccount;

	/**
	 * 明细行（行聚类结构化，两版发票共用；空表为空列表）
	 */
	private List<InvoiceItem> items;

	/**
	 * 价税合计（大写）
	 */
	private String totalAmountUpper;
	/**
	 * 价税合计（小写）—— 货币符号统一为半角 ¥（如 ¥59976.00）
	 */
	private String totalAmountLower;

	/**
	 * 收款人
	 */
	private String payee;
	/**
	 * 复核人
	 */
	private String reviewer;
	/**
	 * 开票人
	 */
	private String issuer;
	/**
	 * 备注（新版电子发票专属，老版为 null）
	 */
	private String remark;
}
