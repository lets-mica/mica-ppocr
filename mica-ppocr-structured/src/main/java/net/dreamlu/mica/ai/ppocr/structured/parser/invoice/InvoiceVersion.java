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

/**
 * 发票版型。
 *
 * <p>由 {@link InvoiceParser}（分发器）在解析完成后标注到 {@code InvoiceResult#getVersion()}；
 * 直接调用 {@link VatInvoiceParser} / {@link ElectronicInvoiceParser} 时该字段保持 null。
 */
public enum InvoiceVersion {

	/**
	 * 老版增值税专用/普通发票（横版，含发票代码 + 8 位发票号码）。
	 */
	VAT,
	/**
	 * 新版电子发票（电子普通发票 / 全面数字化电子发票，发票号码固定 20 位）。
	 */
	ELECTRONIC
}
