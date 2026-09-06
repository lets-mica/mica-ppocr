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

import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Result;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.ParserTestSupport;
import net.dreamlu.mica.ai.ppocr.utils.CollUtil;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 增值税发票解析器单元测试。
 *
 * <p>真实数据来源：{@code src/test/resources/ocr-json/invoice/invoice{N}.json}，
 * 由 {@link InvoiceDumpMain} 批量跑真实 OCR 推理后保存，
 * 测试时不依赖 ONNX Runtime / 模型文件，纯 Java 解析逻辑。
 */
class InvoiceParserTest extends ParserTestSupport {

	/**
	 * 统一分发器（电子版优先 → 20 位号码判别失败回退老版）：
	 * 老版 5 样本 + 空输入 + 标签缺失 mock 全部走端到端回归，并断言 version=VAT。
	 */
	private static final InvoiceParser PARSER = new InvoiceParser(null);

	/**
	 * 从 classpath 加载真实 OCR 结果（跳过 ONNX 推理，仅测试解析逻辑）。
	 */
	private static List<PPOcrV6Result> loadInvoice(String name) throws IOException {
		String path = "/ocr-json/invoice/" + name + ".json";
		List<PPOcrV6Result> list = new ArrayList<>();
		Pattern p = Pattern.compile(
			"\"text\":\"((?:[^\"\\\\]|\\\\.)*)\".*\"box\":\\[" +
				"\\[(\\d+),(\\d+)\\],\\[(\\d+),(\\d+)\\],\\[(\\d+),(\\d+)\\],\\[(\\d+),(\\d+)\\]\\]");
		try (InputStream is = InvoiceParserTest.class.getResourceAsStream(path);
			 BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				Matcher m = p.matcher(line);
				if (!m.find()) continue;
				String text = m.group(1)
					.replace("\\\"", "\"").replace("\\\\", "\\")
					.replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t");
				int[][] box = {
					{Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))},
					{Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5))},
					{Integer.parseInt(m.group(6)), Integer.parseInt(m.group(7))},
					{Integer.parseInt(m.group(8)), Integer.parseInt(m.group(9))}
				};
				list.add(new PPOcrV6Result(text, 1.0f, box));
			}
		}
		return list;
	}

	@Test
	void parse_emptyResults_returnsNulls() {
		InvoiceResult r = parse(PARSER, CollUtil.listOf());
		assertNotNull(r);
		assertEquals(InvoiceVersion.VAT, r.getVersion());
		assertNull(r.getInvoiceCode());
		assertNull(r.getInvoiceNo());
		assertNull(r.getInvoiceDate());
		assertNull(r.getBuyerName());
		assertNull(r.getSellerName());
		assertTrue(r.getItems().isEmpty());
		assertNull(r.getTotalAmountUpper());
		assertNull(r.getPayee());
	}

	/**
	 * PDF 文本层常见场景：fragment-merged 标签框（label 各字符间夹全角空格 + `:` + 值）、
	 * 密码区散落字符（数字 + `<>` 混排）、全角空格分隔的表头（`金　额` / `税　额`）、
	 * 明细含冲账行（`-0.20` + 免税）。
	 */
	@Test
	void parse_pdfFragmentMergedLabels() {
		List<PPOcrV6Result> results = CollUtil.listOf(
			// 顶部
			box("发票代码:000000000000", 432, 25, 535, 32),
			box("发票号码:00000000", 432, 40, 515, 46),
			box("开票日期:2024年01月01日", 432, 54, 538, 60),
			// 购方（fragment-merged + 全角空格 + `:`）
			box("名　　　　称:示例购方科技有限公司", 48, 89, 227, 95),
			box("纳税人识别号:910000000000000000", 48, 103, 208, 110),
			box("地 址、电 话:", 48, 118, 105, 124),
			box("开户行及账号:0000000000", 48, 134, 107, 139),
			// 销方
			box("名　　　　称:示例销方科技有限公司", 48, 299, 216, 305),
			box("纳税人识别号:910000000000000000", 48, 312, 209, 318),
			box("地 址、电 话:示例市示例区示例路1号0000-0000000", 48, 326, 335, 332),
			box("开户行及账号:示例银行0000000000000000000", 48, 340, 328, 345),
			// 密码区散落字符（PDF 文本层按字散落，含数字 + <>*）— 必须被忽略
			box("000<00<0<*000/>000000>0+0<0", 369, 118, 586, 126),
			box("000*+--0/000000+00000/000*00", 369, 132, 586, 140),
			// 明细表表头（全角空格分隔）
			box("货物或应税劳务、服务名称", 41, 152, 149, 157),
			box("规格型号", 189, 152, 225, 157),
			box("单位", 251, 152, 269, 157),
			box("数　量", 292, 152, 319, 157),
			box("单　价", 350, 152, 377, 157),
			box("金　额", 415, 152, 442, 157),
			box("税率", 482, 152, 500, 157),
			box("税　额", 531, 152, 558, 157),
			// 明细行 1：金额 99.62、税率 免税、税额 ＊＊＊
			box("*示例服务*服务费", 25, 164, 113, 170),
			box("次", 255, 164, 264, 170),
			box("1", 326, 164, 331, 170),
			box("99.62", 365, 164, 388, 170),
			box("99.62", 448, 164, 471, 170),
			box("免税", 486, 164, 504, 170),
			box("＊＊＊", 562, 164, 589, 170),
			// 明细行 2：冲账 -0.20、税率 免税、税额 ＊＊＊
			box("*示例服务*服务费", 25, 177, 113, 183),
			box("-0.20", 450, 177, 471, 183),
			box("免税", 486, 177, 504, 183),
			box("＊＊＊", 562, 177, 589, 183),
			// 价税合计
			box("合", 73, 262, 82, 267),
			box("计", 109, 262, 118, 267),
			box("￥99.42", 439, 261, 471, 267),
			box("＊＊＊", 563, 261, 590, 267),
			box("价税合计（大写）", 61, 279, 133, 285),
			box("玖拾玖圆肆角贰分", 184, 279, 256, 285),
			box("（小写）￥99.42", 465, 279, 532, 285),
			// 底栏（fragment-merged + 半角空格 + `:`）
			box("收 款 人:示例收款人", 31, 357, 107, 362),
			box("复 核:示例复核人", 197, 357, 250, 362),
			box("开 票 人:示例开票人", 318, 357, 394, 362)
		);
		InvoiceResult r = parse(PARSER, results);
		assertNotNull(r);
		// 购方
		assertEquals("示例购方科技有限公司", r.getBuyerName());
		assertEquals("910000000000000000", r.getBuyerTaxNo());
		assertNull(r.getBuyerAddressPhone());
		assertEquals("0000000000", r.getBuyerBankAccount());
		// 销方
		assertEquals("示例销方科技有限公司", r.getSellerName());
		assertEquals("910000000000000000", r.getSellerTaxNo());
		assertEquals("示例市示例区示例路1号0000-0000000", r.getSellerAddressPhone());
		assertEquals("示例银行0000000000000000000", r.getSellerBankAccount());
		// 明细（2 行：普通 + 冲账）
		assertEquals(2, r.getItems().size());
		assertEquals("*示例服务*服务费", r.getItems().get(0).getGoodsName());
		assertEquals("99.62", r.getItems().get(0).getAmount());
		assertEquals("免税", r.getItems().get(0).getTaxRate());
		assertEquals("＊＊＊", r.getItems().get(0).getTaxAmount());
		assertEquals("*示例服务*服务费", r.getItems().get(1).getGoodsName());
		assertEquals("-0.20", r.getItems().get(1).getAmount());
		assertEquals("免税", r.getItems().get(1).getTaxRate());
		assertEquals("＊＊＊", r.getItems().get(1).getTaxAmount());
		// 合计
		assertEquals("玖拾玖圆肆角贰分", r.getTotalAmountUpper());
		assertEquals("￥99.42", r.getTotalAmountLower());
		// 底栏
		assertEquals("示例收款人", r.getPayee());
		assertEquals("示例复核人", r.getReviewer());
		assertEquals("示例开票人", r.getIssuer());
	}

	@Test
	void parse_invoiceCodeFallbackWhenLabelMissing() {
		// "发票代码" / "发票号码" 标签缺失，按顶部数字框 + No 前缀兜底
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("3100153130", 618, 420, 901, 463),
			box("No14641426", 1554, 415, 1876, 473),
			box("开票日期：2016年06月02日", 1609, 517, 1980, 552)
		);
		InvoiceResult r = parse(PARSER, results);
		assertEquals(InvoiceVersion.VAT, r.getVersion());
		assertEquals("3100153130", r.getInvoiceCode());
		assertEquals("14641426", r.getInvoiceNo());
		assertEquals("2016年06月02日", r.getInvoiceDate());
	}

	@Test
	void parse_invoice1() throws IOException {
		// 上海增值税发票（百度时代 → 上海易火广告，信息服务费）
		// fragment "名" + "称：" 合并框剥前缀场景
		InvoiceResult r = parse(PARSER, loadInvoice("invoice1"));
		assertNotNull(r);
		assertEquals(InvoiceVersion.VAT, r.getVersion());
		assertEquals("3100153130", r.getInvoiceCode());
		assertEquals("14641426", r.getInvoiceNo());
		assertEquals("2016年06月02日", r.getInvoiceDate());
		// 购买方
		assertEquals("百度时代网络技术（北京）有限公司", r.getBuyerName());
		assertEquals("110108787751579", r.getBuyerTaxNo());
		assertEquals("北京市海淀区东北旺西路8号中关村软件园17号楼二层A2010-59108001", r.getBuyerAddressPhone());
		assertEquals("招商银行北京分行大屯路支行866182028510003", r.getBuyerBankAccount());
		// 销售方
		assertEquals("上海易火广告传媒有限公司", r.getSellerName());
		assertEquals("913101140659591751", r.getSellerTaxNo());
		assertEquals("嘉定区胜辛南路500号15幢1161室55033753", r.getSellerAddressPhone());
		assertEquals("中国银行南翔支行446863841354", r.getSellerBankAccount());
		// 明细（行聚类结构化）
		assertEquals(1, r.getItems().size());
		InvoiceItem item = r.getItems().get(0);
		assertEquals("信息服务费", item.getGoodsName());
		assertEquals("94339.62", item.getAmount());
		assertEquals("6%", item.getTaxRate());
		assertEquals("5660.38", item.getTaxAmount());
		// 合计
		assertEquals("壹拾万圆整", r.getTotalAmountUpper());
		assertEquals("￥100000.00", r.getTotalAmountLower());
		// 底栏
		assertEquals("徐蓉", r.getPayee());
		assertEquals("沈园园", r.getReviewer());
		assertEquals("沈园园", r.getIssuer());
	}

	@Test
	void parse_invoice2() throws IOException {
		// 湖北增值税发票（百度在线上海分公司 → 武汉海庭假日酒店，住宿费）
		// 标签值合并框 "称：百度在线..." 一体识别场景
		InvoiceResult r = parse(PARSER, loadInvoice("invoice2"));
		assertNotNull(r);
		assertEquals(InvoiceVersion.VAT, r.getVersion());
		assertEquals("4200162130", r.getInvoiceCode());
		assertEquals("00998959", r.getInvoiceNo());
		assertEquals("2016年10月17日", r.getInvoiceDate());
		// 购买方
		assertEquals("百度在线网络技术（北京）有限公司上海软件技术分公司", r.getBuyerName());
		assertEquals("310114772120643", r.getBuyerTaxNo());
		assertEquals("上海市嘉定区汇荣路500号021-39005678", r.getBuyerAddressPhone());
		assertEquals("招商银行上海分行准中支行212280455510001", r.getBuyerBankAccount());
		// 销售方
		assertEquals("武汉海庭假日酒店管理有限公司", r.getSellerName());
		assertEquals("914201115879926501", r.getSellerTaxNo());
		assertEquals("武汉市洪山区民院路124号027-87598879", r.getSellerAddressPhone());
		assertEquals("交通银行武汉东湖新技术开发区支行421861636018010041548", r.getSellerBankAccount());
		// 明细（行聚类结构化）
		assertEquals(1, r.getItems().size());
		InvoiceItem item = r.getItems().get(0);
		assertEquals("住宿费", item.getGoodsName());
		assertEquals("1430.19", item.getAmount());
		assertEquals("6%", item.getTaxRate());
		assertEquals("85.81", item.getTaxAmount());
		// 合计
		assertEquals("壹仟伍佰壹拾陆圆整", r.getTotalAmountUpper());
		assertEquals("￥1516.00", r.getTotalAmountLower());
		// 底栏
		assertEquals("前台", r.getPayee());
		assertEquals("肖展", r.getReviewer());
		assertEquals("前台", r.getIssuer());
	}

	@Test
	void parse_invoice3() throws IOException {
		// 江苏增值税发票（北京糯米网 → 南京慧通酒店，住宿费）
		InvoiceResult r = parse(PARSER, loadInvoice("invoice3"));
		assertNotNull(r);
		assertEquals(InvoiceVersion.VAT, r.getVersion());
		assertEquals("3200153130", r.getInvoiceCode());
		assertEquals("44071097", r.getInvoiceNo());
		assertEquals("2016年10月17日", r.getInvoiceDate());
		// 购买方
		assertEquals("北京糯米网科技发展有限公司", r.getBuyerName());
		assertEquals("110108787758500", r.getBuyerTaxNo());
		assertEquals("北京市海淀区中关村南大街甲10号银海大厦七层南719A室010-84481818", r.getBuyerAddressPhone());
		assertEquals("招商银行北京东三环支行861185196210001", r.getBuyerBankAccount());
		// 销售方
		assertEquals("南京慧通酒店管理有限责任公司", r.getSellerName());
		assertEquals("91320114302511244L", r.getSellerTaxNo());
		assertEquals("南京市雨花台区安德门大街57号2幢025-86980999", r.getSellerAddressPhone());
		assertEquals("中国工商银行江苏省分行营业部4301016509100393377", r.getSellerBankAccount());
		// 明细（行聚类结构化）
		assertEquals(1, r.getItems().size());
		InvoiceItem item = r.getItems().get(0);
		assertEquals("住宿费", item.getGoodsName());
		assertEquals("377.36", item.getAmount());
		assertEquals("6%", item.getTaxRate());
		assertEquals("22.64", item.getTaxAmount());
		// 合计
		assertEquals("肆佰圆整", r.getTotalAmountUpper());
		assertEquals("￥400.00", r.getTotalAmountLower());
		// 底栏
		assertEquals("高梦雅", r.getPayee());
		assertEquals("梁笑", r.getReviewer());
		assertEquals("孙莉琼", r.getIssuer());
	}

	@Test
	void parse_invoice4() throws IOException {
		// 北京增值税发票（北京百度网讯 → 北京圣紫茗管理咨询，服务费）
		// 收款人标签后无人名 → null
		InvoiceResult r = parse(PARSER, loadInvoice("invoice4"));
		assertNotNull(r);
		assertEquals(InvoiceVersion.VAT, r.getVersion());
		assertEquals("1100154130", r.getInvoiceCode());
		assertEquals("00772445", r.getInvoiceNo());
		assertEquals("2016年11月15日", r.getInvoiceDate());
		// 购买方
		assertEquals("北京百度网讯科技有限公司", r.getBuyerName());
		assertEquals("110108802100433", r.getBuyerTaxNo());
		assertEquals("北京市海淀区上地十街10号百度大厦2层010-59928888", r.getBuyerAddressPhone());
		assertEquals("招商银行北京分行上地支行110902160610706", r.getBuyerBankAccount());
		// 销售方
		assertEquals("北京圣紫茗管理容询有限公司", r.getSellerName());
		assertEquals("110105057317113", r.getSellerTaxNo());
		assertEquals("北京市朝阳区64377727", r.getSellerAddressPhone());
		assertEquals("上海浦发银行91150154740007408", r.getSellerBankAccount());
		// 明细（行聚类结构化）
		assertEquals(1, r.getItems().size());
		InvoiceItem item = r.getItems().get(0);
		assertEquals("服务费", item.getGoodsName());
		assertEquals("5785.38", item.getAmount());
		assertEquals("6%", item.getTaxRate());
		assertEquals("347.12", item.getTaxAmount());
		// 合计
		assertEquals("陆仟壹佰叁拾贰圆伍角整", r.getTotalAmountUpper());
		assertEquals("￥6132.50", r.getTotalAmountLower());
		// 底栏
		assertNull(r.getPayee());
		assertEquals("马学琦", r.getReviewer());
		assertEquals("焦红娟", r.getIssuer());
	}

	@Test
	void parse_invoice5() throws IOException {
		// 安徽增值税发票（上海优扬新媒 → 合肥乐堂动漫，信息费）
		// "金额" 表头残缺为 "额" 单字场景
		InvoiceResult r = parse(PARSER, loadInvoice("invoice5"));
		assertNotNull(r);
		assertEquals(InvoiceVersion.VAT, r.getVersion());
		assertEquals("3400161130", r.getInvoiceCode());
		assertEquals("00666375", r.getInvoiceNo());
		assertEquals("2016年11月11日", r.getInvoiceDate());
		// 购买方
		assertEquals("上海优扬新媒信息技术有限公司", r.getBuyerName());
		assertEquals("91310114585239729M", r.getBuyerTaxNo());
		assertEquals("上海市嘉定区工业区汇源路55号H幢3层A区021-63460206", r.getBuyerAddressPhone());
		assertEquals("中国工商银行上海市嘉定支行1001700819300415148", r.getBuyerBankAccount());
		// 销售方
		assertEquals("合肥乐堂动漫信息技术有限公司", r.getSellerName());
		assertEquals("91340100686877076E", r.getSellerTaxNo());
		assertEquals("合肥市金寨路71号科茂大厦5层0551-65411799", r.getSellerAddressPhone());
		assertEquals("招商银行合肥南七支行551903169110102", r.getSellerBankAccount());
		// 明细（行聚类结构化）
		assertEquals(1, r.getItems().size());
		InvoiceItem item = r.getItems().get(0);
		assertEquals("信息费", item.getGoodsName());
		assertEquals("2524.75", item.getAmount());
		assertEquals("6%", item.getTaxRate());
		assertEquals("151.49", item.getTaxAmount());
		// 合计
		assertEquals("贰仟陆佰柒拾陆圆贰角肆分", r.getTotalAmountUpper());
		assertEquals("￥2676.24", r.getTotalAmountLower());
		// 底栏
		assertEquals("李平", r.getPayee());
		assertEquals("李平", r.getReviewer());
		assertEquals("秦丽萍", r.getIssuer());
	}

	// ========================================================================
	// 新版电子发票（数电票）用例
	// ========================================================================

	/**
	 * 直测判别器：空输入 → electronic 返回 null（分发器回退老版）。
	 */
	@Test
	void electronic_emptyResults_returnsNull() {
		assertNull(new ElectronicInvoiceParser().parseResults(CollUtil.listOf()));
	}

	/**
	 * 直测判别器：老版增值税发票 OCR 结果中无 20 位连续数字 → 返回 null。
	 */
	@Test
	void electronic_vatResults_returnsNull() throws IOException {
		assertNull(new ElectronicInvoiceParser().parseResults(loadInvoice("invoice1")));
	}

	/**
	 * 数电票端到端：合肥 → 合肥，旅客运输服务。
	 *
	 * <p>OCR 已知瑕疵（解析器如实透传，不做修正）：
	 * <ul>
	 *   <li>买名称"锐域"误识为"皖域"；开票人"鋆"误识为"寒"</li>
	 *   <li>税率列"3%"误识为"3%6"（正则按 % 截断）</li>
	 *   <li>小写金额前缀"¥"误识为"?"（归一化为 ¥）</li>
	 * </ul>
	 */
	@Test
	void parse_electronic_invoice_elec1() throws IOException {
		InvoiceResult r = parse(PARSER, loadInvoice("invoice6"));
		assertNotNull(r);
		assertEquals(InvoiceVersion.ELECTRONIC, r.getVersion());

		// 顶部
		assertEquals("26347000000187619471", r.getInvoiceNo());
		assertEquals("2026年07月28日", r.getInvoiceDate());

		// 购方
		assertEquals("合肥皖域信息科技有限公司", r.getBuyerName());
		assertEquals("913401000723997351", r.getBuyerTaxNo());

		// 销方
		assertEquals("合肥吉利优行科技有限公司", r.getSellerName());
		assertEquals("91340100MA2MRTW78F", r.getSellerTaxNo());

		// 明细表（行聚类结构化，2 行）
		assertEquals(2, r.getItems().size());
		InvoiceItem row0 = r.getItems().get(0);
		assertEquals("交通运输服务*客运服务费", row0.getGoodsName());
		assertEquals("24.49", row0.getAmount());
		assertEquals("3%", row0.getTaxRate());
		assertEquals("0.73", row0.getTaxAmount());
		InvoiceItem row1 = r.getItems().get(1);
		assertEquals("交通运输服务*客运服务费", row1.getGoodsName());
		assertEquals("-3.33", row1.getAmount());
		assertEquals("3%", row1.getTaxRate());
		assertEquals("-0.10", row1.getTaxAmount());

		// 价税合计
		assertEquals("贰拾壹圆柒角玖分", r.getTotalAmountUpper());
		assertEquals("¥21.79", r.getTotalAmountLower());

		// 备注（空）
		assertNull(r.getRemark());

		// 底栏（仅开票人）
		assertNull(r.getPayee());
		assertNull(r.getReviewer());
		assertEquals("钟寒冰", r.getIssuer());
	}

	/**
	 * 数电票（旅客运输服务）：金额表头残缺为单字 "金"（无 "额" fragment）。
	 *
	 * <p>修复前 findColumnHeader step 4 只匹配后缀 "额"，"金" 是前缀不匹配 →
	 * 金额列整列丢失 → 24.49 / -3.33 落入税率列范围但不匹配税率 pattern → 金额 null。
	 * 修复后 step 4 同时接受前缀匹配，"金" → 金额列表头，金额正确解析。
	 */
	@Test
	void parse_electronic_invoice_amountFragmentPrefix() {
		List<PPOcrV6Result> results = buildElectronicInvoiceOcr();
		InvoiceResult r = PARSER.parseResults(results);
		assertNotNull(r);
		assertEquals(InvoiceVersion.ELECTRONIC, r.getVersion());
		assertEquals("26347000000187619471", r.getInvoiceNo());
		assertEquals("2026年07月28日", r.getInvoiceDate());
		assertEquals("合肥皖域信息科技有限公司", r.getBuyerName());
		assertEquals("913401000723997351", r.getBuyerTaxNo());
		assertEquals("合肥吉利优行科技有限公司", r.getSellerName());
		assertEquals("91340100MA2MRTW78F", r.getSellerTaxNo());
		// 明细（金额表头残缺为 "金"，值在金额/税率列重叠区）
		assertNotNull(r.getItems());
		assertEquals(2, r.getItems().size());
		InvoiceItem row0 = r.getItems().get(0);
		assertEquals("交通运输服务*客运服务费", row0.getGoodsName());
		assertEquals("24.49", row0.getAmount());
		assertEquals("3%", row0.getTaxRate());
		assertEquals("0.73", row0.getTaxAmount());
		InvoiceItem row1 = r.getItems().get(1);
		assertEquals("交通运输服务*客运服务费", row1.getGoodsName());
		assertEquals("-3.33", row1.getAmount());
		assertEquals("3%", row1.getTaxRate());
		assertEquals("-0.10", row1.getTaxAmount());
		// 价税合计
		assertEquals("贰拾壹圆柒角玖分", r.getTotalAmountUpper());
		assertEquals("¥21.79", r.getTotalAmountLower());
		// 底栏
		assertEquals("钟寒冰", r.getIssuer());
	}

	/**
	 * 数电票购销方版式：左侧合并框（"名称：xxx"），右侧 label 与 value 独立。
	 *
	 * <p>修复前 {@code parseParties} 只把"以 名称：开头"的框加进 nameBoxes，
	 * 右侧独立 label "名称" + 独立 value "陕西滴滴出行科技有限公司" 只有一个框
	 * 入选 → size == 1 → 走 else-if 分支只设 buyerName，sellerName 永远 null。
	 * 修复后增加 label 独立路径：纯 label 框"名称"右侧 y 重叠区域找 value，
	 * 再按 minX 分两列。
	 */
	@Test
	void parse_electronic_invoice_splitLabelOnRight() {
		List<PPOcrV6Result> results = buildElectronicInvoiceSplitLabelOcr();
		InvoiceResult r = PARSER.parseResults(results);
		assertNotNull(r);
		assertEquals(InvoiceVersion.ELECTRONIC, r.getVersion());
		assertEquals("26347000000000999999", r.getInvoiceNo());
		assertEquals("2026年02月04日", r.getInvoiceDate());
		// 购方：合并框 → 剥前缀
		assertEquals("北京某某有限公司", r.getBuyerName());
		assertEquals("91110000000000000X", r.getBuyerTaxNo());
		// 销方：label 独立 + value 独立（修复前为 null）
		assertEquals("陕西滴滴出行科技有限公司", r.getSellerName());
		assertEquals("91610138MA6X3B1A27", r.getSellerTaxNo());
	}

	private static List<PPOcrV6Result> buildElectronicInvoiceSplitLabelOcr() {
		String[] lines = {
			"text=\"电子发票（普通发票）\"  score=0.99  box=[(295,30),(513,73)]",
			"text=\"发票号码：26347000000000999999\"  score=0.99  box=[(660,44),(871,59)]",
			"text=\"开票日期：2026年02月04日\"  score=0.99  box=[(658,69),(831,87)]",
			// 购方：合并框
			"text=\"名称：北京某某有限公司\"  score=0.99  box=[(48,136),(242,150)]",
			"text=\"统一社会信用代码/纳税人识别号：91110000000000000X\"  score=0.99  box=[(47,193),(411,208)]",
			// 销方：label 独立 + value 独立（核心场景）
			"text=\"名称\"  score=0.99  box=[(478,136),(503,150)]",
			"text=\"陕西滴滴出行科技有限公司\"  score=0.99  box=[(510,136),(673,150)]",
			"text=\"统一社会信用代码/纳税人识别号\"  score=0.99  box=[(475,192),(672,208)]",
			"text=\"91610138MA6X3B1A27\"  score=0.99  box=[(680,192),(845,208)]",
			// 明细表
			"text=\"项目名称\"  score=0.99  box=[(113,223),(172,238)]",
			"text=\"单价\"  score=0.99  box=[(376,220),(409,241)]",
			"text=\"数量\"  score=0.99  box=[(504,220),(538,242)]",
			"text=\"金额\"  score=0.99  box=[(615,222),(649,240)]",
			"text=\"税率/征收率\"  score=0.99  box=[(662,223),(741,239)]",
			"text=\"税额\"  score=0.99  box=[(845,220),(879,241)]",
			"text=\"*运输服务*客运服务费\"  score=0.99  box=[(20,240),(181,255)]",
			"text=\"155.63\"  score=0.99  box=[(342,239),(410,257)]",
			"text=\"1\"  score=0.99  box=[(525,241),(537,255)]",
			"text=\"155.63\"  score=0.99  box=[(608,238),(650,258)]",
			"text=\"3%\"  score=0.99  box=[(694,240),(712,257)]",
			"text=\"4.67\"  score=0.99  box=[(844,237),(880,259)]",
			"text=\"*运输服务*客运服务费\"  score=0.99  box=[(20,258),(182,275)]",
			"text=\"-49.42\"  score=0.99  box=[(607,257),(650,277)]",
			"text=\"3%\"  score=0.99  box=[(692,257),(714,277)]",
			"text=\"-1.48\"  score=0.99  box=[(839,258),(881,276)]",
			"text=\"价税合计（大写）\"  score=0.99  box=[(80,416),(181,432)]",
			"text=\"壹佰零玖圆肆角整\"  score=0.99  box=[(253,412),(407,433)]",
			"text=\"（小写）¥109.40\"  score=0.99  box=[(621,414),(722,433)]",
			"text=\"开票人：赵笑林\"  score=0.99  box=[(470,520),(588,541)]",
		};
		return parseTextLines(lines);
	}

	/**
	 * 与 buildElectronicInvoiceOcr 类似但 box 坐标是 4 个点 (x,y) 而非 2 个点。
	 * 单元测试内复用：把 "text=...  box=[(x1,y1),(x2,y2),...]" 转成 PPOcrV6Result。
	 */
	private static List<PPOcrV6Result> parseTextLines(String[] lines) {
		List<PPOcrV6Result> list = new ArrayList<>();
		Pattern p = Pattern.compile(
			"text=\"((?:[^\"\\\\]|\\\\.)*)\"\\s+score=[\\d.]+\\s+box=\\[([^\\]]+)\\]");
		for (String line : lines) {
			Matcher m = p.matcher(line);
			if (!m.find()) continue;
			String text = m.group(1)
				.replace("\\\"", "\"").replace("\\\\", "\\")
				.replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t");
			String[] pts = m.group(2).split("\\),\\(");
			int[][] box = new int[pts.length][2];
			for (int i = 0; i < pts.length; i++) {
				String clean = pts[i].replace("(", "").replace(")", "").trim();
				String[] xy = clean.split(",");
				box[i][0] = Integer.parseInt(xy[0].trim());
				box[i][1] = Integer.parseInt(xy[1].trim());
			}
			list.add(new PPOcrV6Result(text, 1.0f, box));
		}
		return list;
	}

	private static List<PPOcrV6Result> buildElectronicInvoiceOcr() {
		String[] lines = {
			"text=\"电子发晨(音通\"  score=0.698850  box=[(295,30),(513,73)]",
			"text=\"发票）\"  score=0.857711  box=[(500,31),(585,69)]",
			"text=\"发票号码：26347000000187619471\"  score=0.968474  box=[(660,44),(871,59)]",
			"text=\"旅客运输服务\"  score=0.997250  box=[(142,58),(224,74)]",
			"text=\"开票日期：2026年07月28日\"  score=0.998373  box=[(658,69),(831,87)]",
			"text=\"安徽有税务的\"  score=0.655170  box=[(413,90),(489,110)]",
			"text=\"购买方信息\"  score=0.997860  box=[(21,130),(43,216)]",
			"text=\"名称：合肥皖域信息科技有限公司\"  score=0.994116  box=[(48,136),(242,150)]",
			"text=\"銷售方信息\"  score=0.993957  box=[(451,131),(472,217)]",
			"text=\"名称：合肥吉利优行科技有限公司\"  score=0.981138  box=[(478,136),(673,150)]",
			"text=\"统一社会信用代码/纳税人识别号：913401000723997351\"  score=0.976740  box=[(47,193),(411,208)]",
			"text=\"统一社会信用代码/纳税人识别号：91340100MA2MRTW78F\"  score=0.990431  box=[(475,192),(845,208)]",
			"text=\"项目名称\"  score=0.998479  box=[(113,223),(172,238)]",
			"text=\"单价\"  score=0.999799  box=[(376,220),(409,241)]",
			"text=\"数量\"  score=0.992006  box=[(504,220),(538,242)]",
			"text=\"金\"  score=0.969826  box=[(615,222),(649,240)]",
			"text=\"税率/征收率\"  score=0.999717  box=[(662,223),(741,239)]",
			"text=\"税额\"  score=0.856615  box=[(845,220),(879,241)]",
			"text=\"交通运输服务*客运服务费\"  score=0.927945  box=[(20,240),(181,255)]",
			"text=\"24.485437\"  score=0.999180  box=[(342,239),(410,257)]",
			"text=\"1\"  score=0.998791  box=[(525,241),(537,255)]",
			"text=\"24.49\"  score=0.999931  box=[(608,238),(650,258)]",
			"text=\"3%\"  score=0.999954  box=[(694,240),(712,257)]",
			"text=\"0.73\"  score=0.937672  box=[(844,237),(880,259)]",
			"text=\"交通运输服务*客运服务费\"  score=0.989175  box=[(20,258),(182,275)]",
			"text=\"-3.33\"  score=0.994756  box=[(607,257),(650,277)]",
			"text=\"3%\"  score=0.999853  box=[(692,257),(714,277)]",
			"text=\"-0.10\"  score=0.988283  box=[(839,258),(881,276)]",
			"text=\"合\"  score=0.976046  box=[(90,294),(110,314)]",
			"text=\"计\"  score=0.999416  box=[(162,294),(179,312)]",
			"text=\"¥21.16\"  score=0.984282  box=[(602,293),(659,316)]",
			"text=\"¥0.63\"  score=0.962306  box=[(829,294),(877,314)]",
			"text=\"出行人\"  score=0.998541  box=[(36,315),(83,334)]",
			"text=\"有效身份证件号\"  score=0.999904  box=[(137,317),(232,332)]",
			"text=\"出行日期\"  score=0.999031  box=[(290,316),(350,334)]",
			"text=\"出发地\"  score=0.998149  box=[(430,315),(478,334)]",
			"text=\"到达地\"  score=0.998833  box=[(593,315),(640,334)]",
			"text=\"等级\"  score=0.999824  box=[(717,316),(749,333)]",
			"text=\"交通工具类型\"  score=0.979736  box=[(779,317),(862,332)]",
			"text=\"价税合计（大写)\"  score=0.905461  box=[(80,416),(181,432)]",
			"text=\"贰拾壹圆柒角玖分\"  score=0.994728  box=[(253,412),(407,433)]",
			"text=\"(小写)¥21.79\"  score=0.958524  box=[(621,414),(722,433)]",
			"text=\"备\"  score=0.877858  box=[(20,448),(44,466)]",
			"text=\"注\"  score=0.999699  box=[(22,482),(42,502)]",
			"text=\"开票人：钟寒冰\"  score=0.980739  box=[(80,545),(172,561)]"
		};
		Pattern p = Pattern.compile(
			"text=\"((?:[^\"\\\\]|\\\\.)*)\"\\s+score=([0-9.]+)\\s+box=\\[\\((\\d+),(\\d+)\\),\\((\\d+),(\\d+)\\)\\]");
		List<PPOcrV6Result> list = new ArrayList<>();
		for (String line : lines) {
			Matcher m = p.matcher(line);
			if (!m.find()) {
				throw new IllegalArgumentException("OCR 行解析失败: " + line);
			}
			String text = m.group(1)
				.replace("\\\"", "\"").replace("\\\\", "\\")
				.replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t");
			float score = Float.parseFloat(m.group(2));
			int x0 = Integer.parseInt(m.group(3));
			int y0 = Integer.parseInt(m.group(4));
			int x1 = Integer.parseInt(m.group(5));
			int y1 = Integer.parseInt(m.group(6));
			int[][] box = {{x0, y0}, {x1, y0}, {x1, y1}, {x0, y1}};
			list.add(new PPOcrV6Result(text, score, box));
		}
		return list;
	}

	// ========================================================================
	// 诊断：直接调 VatInvoiceParser 跑通行费发票 OCR 数据
	// ========================================================================

	/**
	 * 用统一分发器 {@link InvoiceParser} 跑用户提供的河南增值通行费发票 OCR 数据,
	 * 验证"校验码"标签触发的 VAT 分支路由,输出全部字段供诊断,断言只验证关键字段。
	 */
	@Test
	void diagnose_tollFeeInvoice_dispatcher() {
		List<PPOcrV6Result> results = buildTollFeeOcr();
		InvoiceResult r = PARSER.parseResults(results);
		StringBuilder sb = new StringBuilder();
		sb.append("\n========== InvoiceParser 分发器输出 ==========\n");
		sb.append("version:           ").append(r.getVersion()).append('\n');
		sb.append("invoiceCode:       ").append(r.getInvoiceCode()).append('\n');
		sb.append("invoiceNo:         ").append(r.getInvoiceNo()).append('\n');
		sb.append("invoiceDate:       ").append(r.getInvoiceDate()).append('\n');
		sb.append("buyerName:         ").append(r.getBuyerName()).append('\n');
		sb.append("buyerTaxNo:        ").append(r.getBuyerTaxNo()).append('\n');
		sb.append("buyerAddressPhone: ").append(r.getBuyerAddressPhone()).append('\n');
		sb.append("buyerBankAccount:  ").append(r.getBuyerBankAccount()).append('\n');
		sb.append("sellerName:        ").append(r.getSellerName()).append('\n');
		sb.append("sellerTaxNo:       ").append(r.getSellerTaxNo()).append('\n');
		sb.append("sellerAddressPhone:").append(r.getSellerAddressPhone()).append('\n');
		sb.append("sellerBankAccount: ").append(r.getSellerBankAccount()).append('\n');
		if (r.getItems() != null) {
			sb.append("items.size:        ").append(r.getItems().size()).append('\n');
			for (int i = 0; i < r.getItems().size(); i++) {
				InvoiceItem it = r.getItems().get(i);
				sb.append(String.format("  item[%d]: name=%s amount=%s rate=%s tax=%s%n",
					i, it.getGoodsName(), it.getAmount(), it.getTaxRate(), it.getTaxAmount()));
			}
		}
		sb.append("totalAmountUpper:  ").append(r.getTotalAmountUpper())
			.append("  (chars=").append(r.getTotalAmountUpper() == null ? "null" : r.getTotalAmountUpper().length()).append(")\n");
		sb.append("totalAmountLower:  ").append(r.getTotalAmountLower()).append('\n');
		sb.append("payee:             ").append(r.getPayee()).append('\n');
		sb.append("reviewer:          ").append(r.getReviewer()).append('\n');
		sb.append("issuer:            ").append(r.getIssuer()).append('\n');
		sb.append("==============================================\n");
		String output = sb.toString();
		System.out.println(output);
		try {
			java.nio.file.Path out = java.nio.file.Paths.get("target", "diagnose-tollfee-dispatcher.txt").toAbsolutePath();
			java.nio.file.Files.write(out, output.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		} catch (Exception e) {
			System.err.println("诊断输出写文件失败: " + e.getMessage());
		}

		// 关键断言: 分发器应正确路由到 VAT 分支 (因含"校验码"标签)
		assertEquals(InvoiceVersion.VAT, r.getVersion());
		assertEquals("041002000112", r.getInvoiceCode());
		assertEquals("53329252", r.getInvoiceNo());
		assertEquals("2022年11月13日", r.getInvoiceDate());
		assertEquals("郑州约克计算机技术有限公司", r.getBuyerName());
		assertEquals("91410105665970335G", r.getBuyerTaxNo());
		assertEquals("河南交通投资集团有限公司", r.getSellerName());
		assertEquals("91410000693505019R", r.getSellerTaxNo());
		assertEquals("朱晓珂", r.getPayee());
		assertEquals("关济民", r.getReviewer());
		assertEquals("任秋颖", r.getIssuer());
		assertEquals("玖拾柒元叁角叁分", r.getTotalAmountUpper());
		assertEquals("￥97.33", r.getTotalAmountLower());
		assertNotNull(r.getItems());
		assertEquals(1, r.getItems().size());
		InvoiceItem it = r.getItems().get(0);
		assertEquals("94.50", it.getAmount());
		assertEquals("3%", it.getTaxRate());
		assertEquals("2.83", it.getTaxAmount());
	}

	/**
	 * 直接调 {@link VatInvoiceParser} 跑用户提供的河南增值通行费发票 OCR 数据
	 * (绕过分发器,不进入 ElectronicInvoiceParser 误判分支),
	 * 输出全部字段供诊断,断言只验证关键字段以避免 OCR 噪声脆性。
	 */
	@Test
	void diagnose_tollFeeInvoice_vatOnly() {
		List<PPOcrV6Result> results = buildTollFeeOcr();
		VatInvoiceParser vat = new VatInvoiceParser();
		InvoiceResult r = vat.parseResults(results);
		System.out.println("\n========== VatInvoiceParser 直测输出 ==========");
		System.out.println("version:           " + (String) null);
		System.out.println("invoiceCode:       " + r.getInvoiceCode());
		System.out.println("invoiceNo:         " + r.getInvoiceNo());
		System.out.println("invoiceDate:       " + r.getInvoiceDate());
		System.out.println("buyerName:         " + r.getBuyerName());
		System.out.println("buyerTaxNo:        " + r.getBuyerTaxNo());
		System.out.println("buyerAddressPhone: " + r.getBuyerAddressPhone());
		System.out.println("buyerBankAccount:  " + r.getBuyerBankAccount());
		System.out.println("sellerName:        " + r.getSellerName());
		System.out.println("sellerTaxNo:       " + r.getSellerTaxNo());
		System.out.println("sellerAddressPhone:" + r.getSellerAddressPhone());
		System.out.println("sellerBankAccount: " + r.getSellerBankAccount());
		if (r.getItems() != null) {
			System.out.println("items.size:        " + r.getItems().size());
			for (int i = 0; i < r.getItems().size(); i++) {
				InvoiceItem it = r.getItems().get(i);
				System.out.printf("  item[%d]: name=%s amount=%s rate=%s tax=%s%n",
					i, it.getGoodsName(), it.getAmount(), it.getTaxRate(), it.getTaxAmount());
			}
		}
		System.out.println("totalAmountUpper:  " + r.getTotalAmountUpper()
			+ "  (chars=" + (r.getTotalAmountUpper() == null ? "null" : r.getTotalAmountUpper().length()) + ")");
		System.out.println("totalAmountLower:  " + r.getTotalAmountLower());
		System.out.println("payee:             " + r.getPayee());
		System.out.println("reviewer:          " + r.getReviewer());
		System.out.println("issuer:            " + r.getIssuer());
		System.out.println("==============================================\n");

		// 已知正确值的最小断言集(确认 Vat 直测关键字段正确)
		assertEquals("041002000112", r.getInvoiceCode());
		assertEquals("53329252", r.getInvoiceNo());
		assertEquals("2022年11月13日", r.getInvoiceDate());
		assertEquals("郑州约克计算机技术有限公司", r.getBuyerName());
		assertEquals("91410105665970335G", r.getBuyerTaxNo());
		assertEquals("河南交通投资集团有限公司", r.getSellerName());
		assertEquals("91410000693505019R", r.getSellerTaxNo());
		assertEquals("朱晓珂", r.getPayee());
		assertEquals("关济民", r.getReviewer());
		assertEquals("任秋颖", r.getIssuer());
		assertEquals("玖拾柒元叁角叁分", r.getTotalAmountUpper());
		assertEquals("￥97.33", r.getTotalAmountLower());
		assertNotNull(r.getItems());
		assertEquals(1, r.getItems().size());
		InvoiceItem it = r.getItems().get(0);
		assertEquals("94.50", it.getAmount());
		assertEquals("3%", it.getTaxRate());
		assertEquals("2.83", it.getTaxAmount());
		assertNotNull(it.getGoodsName());
		assertTrue(it.getGoodsName().contains("经营租赁"),
			"goodsName 应含'经营租赁', 实际=" + it.getGoodsName());
	}

	/**
	 * 从用户提供的 OCR 文本构造 67 个 {@link PPOcrV6Result}。
	 * 框坐标格式:[(x0,y0),(x1,y1)] → 4 顶点 axis-aligned 矩形。
	 */
	private static List<PPOcrV6Result> buildTollFeeOcr() {
		String[] lines = {
			"text=\"河南增值\"  score=0.999169  box=[(286,25),(413,63)]",
			"text=\"发票代码：041002000112\"  score=0.955184  box=[(641,27),(798,42)]",
			"text=\"发票号码：53329252\"  score=0.998719  box=[(642,50),(768,65)]",
			"text=\"通行费\"  score=0.999351  box=[(131,66),(211,93)]",
			"text=\"\"  score=0.000000  box=[(403,64),(415,72)]",
			"text=\"国作院方5-局\"  score=0.289549  box=[(420,68),(490,83)]",
			"text=\"开票日期：2022年11月13日\"  score=0.981407  box=[(642,71),(801,86)]",
			"text=\"河南省税务局\"  score=0.839479  box=[(423,87),(486,106)]",
			"text=\"机器编号：499097952096\"  score=0.999048  box=[(31,97),(194,115)]",
			"text=\"校验码：12301398404206110858\"  score=0.994422  box=[(641,92),(868,107)]",
			"text=\"购\"  score=0.998940  box=[(36,128),(57,151)]",
			"text=\"名\"  score=0.999774  box=[(69,122),(84,138)]",
			"text=\"称：郑州约克计算机技术有限公司\"  score=0.993106  box=[(136,122),(336,137)]",
			"text=\"03180*7<0>-639*>1>34+9228*4>\"  score=0.986908  box=[(546,124),(876,142)]",
			"text=\"买方\"  score=0.996325  box=[(36,143),(57,198)]",
			"text=\"纳税人识别号：91410105665970335G\"  score=0.995323  box=[(68,143),(300,161)]",
			"text=\"密\"  score=0.668357  box=[(521,134),(532,151)]",
			"text=\"码\"  score=0.746846  box=[(520,153),(534,173)]",
			"text=\"492+2*091*/*<0-21994/*>31<67\"  score=0.963237  box=[(545,146),(875,164)]",
			"text=\"地址、电话：郑州市北环路116号中方园小区西区1号楼东1单元3层B室13838153773\"  score=0.971132  box=[(68,165),(481,185)]",
			"text=\"区\"  score=0.999079  box=[(520,176),(534,195)]",
			"text=\"265-68<5*<+-<0**+221*<11-+68\"  score=0.990307  box=[(545,167),(875,185)]",
			"text=\"开户行及账号：浦发银行经三支行66376130154800000547\"  score=0.985704  box=[(69,188),(411,206)]",
			"text=\"5>916*<61*01*-141922<70*1-3>\"  score=0.929794  box=[(544,188),(876,206)]",
			"text=\"项目名称\"  score=0.998872  box=[(97,215),(153,231)]",
			"text=\"车牌号\"  score=0.938037  box=[(266,215),(307,232)]",
			"text=\"类型\"  score=0.991976  box=[(369,215),(400,232)]",
			"text=\"通行日期起\"  score=0.997254  box=[(417,215),(487,231)]",
			"text=\"通行日期止\"  score=0.991027  box=[(501,215),(571,231)]",
			"text=\"金额\"  score=0.990914  box=[(617,215),(662,232)]",
			"text=\"税率\"  score=0.994632  box=[(710,214),(744,232)]",
			"text=\"税额\"  score=0.996450  box=[(786,215),(832,232)]",
			"text=\"*经营租赁*通行费\"  score=0.957663  box=[(33,233),(142,253)]",
			"text=\"豫HL7223\"  score=0.948611  box=[(218,233),(279,253)]",
			"text=\"货车\"  score=0.998190  box=[(367,232),(401,254)]",
			"text=\"20220210\"  score=0.988788  box=[(418,233),(485,253)]",
			"text=\"20220211\"  score=0.995916  box=[(505,234),(570,251)]",
			"text=\"94.50\"  score=0.999883  box=[(662,232),(704,253)]",
			"text=\"3%\"  score=0.998936  box=[(724,231),(754,254)]",
			"text=\"2.83\"  score=0.999940  box=[(845,231),(883,254)]",
			"text=\"合\"  score=0.896146  box=[(95,380),(111,396)]",
			"text=\"计\"  score=0.999518  box=[(143,379),(160,396)]",
			"text=\"￥94.50\"  score=0.981871  box=[(651,378),(705,397)]",
			"text=\"￥2.83\"  score=0.984229  box=[(835,378),(883,398)]",
			"text=\"价税合计（大写)\"  score=0.940867  box=[(76,406),(182,422)]",
			"text=\"玖拾柒元叁角叁分\"  score=0.939852  box=[(219,406),(346,422)]",
			"text=\"(小写)\"  score=0.945757  box=[(685,406),(735,422)]",
			"text=\"￥97.33\"  score=0.959740  box=[(742,406),(793,422)]",
			"text=\"名\"  score=0.999970  box=[(68,435),(85,452)]",
			"text=\"称：河南交通投资集团有限公司\"  score=0.994846  box=[(136,435),(319,453)]",
			"text=\"汇总开具\"  score=0.999928  box=[(540,434),(601,454)]",
			"text=\"销\"  score=0.824987  box=[(37,445),(54,464)]",
			"text=\"纳税人识别号：91410000693505019R\"  score=0.991740  box=[(67,454),(299,475)]",
			"text=\"备\"  score=0.730131  box=[(519,448),(535,467)]",
			"text=\"售\"  score=0.911221  box=[(36,459),(54,488)]",
			"text=\"地址、电话：河南省郑州市郑东新区金水东路26号0371-87165330\"  score=0.962485  box=[(67,475),(463,495)]",
			"text=\"方\"  score=0.505874  box=[(35,487),(55,509)]",
			"text=\"开户行及账号：中国工商银行股份有限公司郑州商都路支行1702022809200058895\"  score=0.987739  box=[(68,495),(502,513)]",
			"text=\"注\"  score=0.862311  box=[(519,487),(535,503)]",
			"text=\"南\"  score=0.993496  box=[(716,496),(737,516)]",
			"text=\"交\"  score=0.990029  box=[(721,490),(748,488)]",
			"text=\"9141000693505019R\"  score=0.993894  box=[(737,504),(850,523)]",
			"text=\"收款人：朱晓珂\"  score=0.983252  box=[(43,521),(158,538)]",
			"text=\"复核：关济民\"  score=0.961283  box=[(293,521),(385,538)]",
			"text=\"开票人：任秋颖\"  score=0.963243  box=[(470,520),(588,541)]",
			"text=\"销售方\"  score=0.998427  box=[(654,520),(713,540)]",
			"text=\"发票专用章\"  score=0.999321  box=[(743,522),(841,549)]"
		};
		Pattern p = Pattern.compile(
			"text=\"((?:[^\"\\\\]|\\\\.)*)\"\\s+score=([0-9.]+)\\s+box=\\[\\((\\d+),(\\d+)\\),\\((\\d+),(\\d+)\\)\\]");
		List<PPOcrV6Result> list = new ArrayList<>();
		for (String line : lines) {
			Matcher m = p.matcher(line);
			if (!m.find()) {
				throw new IllegalArgumentException("OCR 行解析失败: " + line);
			}
			String text = m.group(1)
				.replace("\\\"", "\"").replace("\\\\", "\\")
				.replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t");
			float score = Float.parseFloat(m.group(2));
			int x0 = Integer.parseInt(m.group(3));
			int y0 = Integer.parseInt(m.group(4));
			int x1 = Integer.parseInt(m.group(5));
			int y1 = Integer.parseInt(m.group(6));
			int[][] box = {{x0, y0}, {x1, y0}, {x1, y1}, {x0, y1}};
			list.add(new PPOcrV6Result(text, score, box));
		}
		return list;
	}

	/**
	 * 河南增值税普通发票（飞利浦剃须刀）。
	 *
	 * <p>关键场景：明细行金额"123.01"的 x 中心(706)落入金额列 [607,714]
	 * 与税率列 [697,792] 的重叠区。修复前 isNearestColumn 按几何距离
	 * 将其误归到税率列（更近），但"123.01"不匹配税率 pattern（无 %）→
	 * extractCell 返回 null → 金额丢失。
	 */
	@Test
	void parse_henanVatInvoice_amountInOverlapZone() {
		List<PPOcrV6Result> results = buildHenanVatOcr();
		InvoiceResult r = PARSER.parseResults(results);
		assertNotNull(r);
		assertEquals(InvoiceVersion.VAT, r.getVersion());
		// 顶部
		assertEquals("041002200211", r.getInvoiceCode());
		assertEquals("24867153", r.getInvoiceNo());
		assertEquals("2022年12月06日", r.getInvoiceDate());
		// 购方
		assertEquals("郑州约克计算机技术有限公司", r.getBuyerName());
		assertEquals("91410105665970335G", r.getBuyerTaxNo());
		// 销方
		assertEquals("河南世纪联华超市有限公司", r.getSellerName());
		assertEquals("91410100744070589Y", r.getSellerTaxNo());
		assertEquals("郑州市金水区经三路68号1号楼5层0371-658610620371-65861062", r.getSellerAddressPhone());
		assertEquals("招商银行郑州东风路支行371902422710102", r.getSellerBankAccount());
		// 明细（金额在金额/税率列重叠区，必须正确归到金额列）
		assertNotNull(r.getItems());
		assertEquals(1, r.getItems().size());
		InvoiceItem item = r.getItems().get(0);
		assertNotNull(item.getGoodsName());
		assertTrue(item.getGoodsName().contains("飞利浦剃须刀"));
		assertEquals("123.01", item.getAmount());
		assertEquals("13%", item.getTaxRate());
		assertEquals("15.99", item.getTaxAmount());
		// 合计
		assertEquals("壹佰叁拾玖圆整", r.getTotalAmountUpper());
		assertEquals("¥139.00", r.getTotalAmountLower());
		// 底栏
		assertEquals("李明丽", r.getPayee());
		assertEquals("齐艳民", r.getReviewer());
		assertEquals("韩艳红", r.getIssuer());
	}

	private static List<PPOcrV6Result> buildHenanVatOcr() {
		String[] lines = {
			"text=\"发票代码：041002200211\"  score=0.971259  box=[(658,15),(794,29)]",
			"text=\"河南增值電\"  score=0.883697  box=[(296,25),(518,73)]",
			"text=\"普通发票\"  score=0.966819  box=[(504,25),(637,73)]",
			"text=\"发票号码:24867153\"  score=0.966150  box=[(656,42),(770,57)]",
			"text=\"日家科务馬局\"  score=0.447162  box=[(423,68),(499,83)]",
			"text=\"*\"  score=0.429490  box=[(506,69),(518,76)]",
			"text=\"开票日期：2022年12月06日\"  score=0.994740  box=[(656,68),(832,82)]",
			"text=\"机器编号：661616199301\"  score=0.999123  box=[(46,94),(190,108)]",
			"text=\"河南省税务局\"  score=0.917756  box=[(428,92),(494,111)]",
			"text=\"校验码:57915131911755844768\"  score=0.930958  box=[(656,94),(863,109)]",
			"text=\"购买方\"  score=0.828749  box=[(36,134),(65,204)]",
			"text=\"名\"  score=0.999964  box=[(70,126),(88,143)]",
			"text=\"称：郑州约克计算机技术有限公司\"  score=0.996937  box=[(141,128),(339,143)]",
			"text=\"密\"  score=0.999899  box=[(529,129),(548,151)]",
			"text=\"468<>5813463829*02/+*30+0-1\"  score=0.992859  box=[(577,128),(869,143)]",
			"text=\"纳税人识别号：91410105665970335G\"  score=0.977695  box=[(70,149),(360,167)]",
			"text=\"码\"  score=0.999423  box=[(530,157),(549,180)]",
			"text=\"3>84*+1-6/0055+7288*87+08-7\"  score=0.994720  box=[(576,150),(870,168)]",
			"text=\"地址、电话：\"  score=0.968846  box=[(71,174),(160,189)]",
			"text=\"4505-2*>7334*66<829-43/+*45\"  score=0.989738  box=[(577,174),(870,189)]",
			"text=\"区\"  score=0.999945  box=[(530,186),(550,208)]",
			"text=\"开户行及账号：\"  score=0.998172  box=[(71,196),(161,212)]",
			"text=\"+2-9+/+5827<7>4026+7<8/+18<\"  score=0.999266  box=[(575,195),(872,213)]",
			"text=\"货物或应税劳务、服务名称\"  score=0.994173  box=[(66,220),(231,234)]",
			"text=\"规格型号\"  score=0.999866  box=[(268,217),(328,236)]",
			"text=\"单位\"  score=0.999572  box=[(338,217),(373,237)]",
			"text=\"数量\"  score=0.994642  box=[(412,217),(453,237)]",
			"text=\"单价\"  score=0.999486  box=[(517,218),(556,237)]",
			"text=\"金额\"  score=0.983757  box=[(637,219),(684,238)]",
			"text=\"税率\"  score=0.927768  box=[(727,217),(762,237)]",
			"text=\"税额\"  score=0.994457  box=[(807,219),(856,238)]",
			"text=\"*家用美容保健电器*飞利浦剃须刀\"  score=0.955511  box=[(39,240),(243,255)]",
			"text=\"PQ182\"  score=0.999175  box=[(266,239),(308,256)]",
			"text=\"个\"  score=0.999414  box=[(348,237),(369,258)]",
			"text=\"123.00884956\"  score=0.986291  box=[(516,240),(592,256)]",
			"text=\"123.01\"  score=0.935905  box=[(686,239),(726,256)]",
			"text=\"13%\"  score=0.999892  box=[(731,236),(762,258)]",
			"text=\"15.99\"  score=0.968798  box=[(862,239),(895,257)]",
			"text=\"合\"  score=0.973572  box=[(89,383),(109,403)]",
			"text=\"计\"  score=0.999976  box=[(150,384),(168,401)]",
			"text=\"¥123.01\"  score=0.956272  box=[(651,388),(724,404)]",
			"text=\"¥15.99\"  score=0.943660  box=[(830,388),(893,404)]",
			"text=\"价税合计(大写)\"  score=0.964716  box=[(92,414),(187,429)]",
			"text=\"壹佰叁拾玖圆整\"  score=0.990913  box=[(268,412),(385,430)]",
			"text=\"(小写)¥139.00\"  score=0.932214  box=[(680,414),(787,428)]",
			"text=\"銷售方\"  score=0.921529  box=[(40,450),(63,514)]",
			"text=\"名\"  score=0.999987  box=[(70,441),(87,459)]",
			"text=\"称：河南世纪联华超市有限公司\"  score=0.991459  box=[(139,443),(324,458)]",
			"text=\"备\"  score=0.999736  box=[(530,452),(546,471)]",
			"text=\"纳税人识别号:91410100744070589Y\"  score=0.977960  box=[(70,465),(356,479)]",
			"text=\"地址、电话：郑州市金水区经三路68号1号楼5层0371-658610620371-65861062\"  score=0.958286  box=[(70,486),(492,501)]",
			"text=\"开户行及账号：招商银行郑州东风路支行371902422710102\"  score=0.984525  box=[(70,505),(410,522)]",
			"text=\"注\"  score=0.999762  box=[(528,496),(548,516)]",
			"text=\"阿91410100744070589Y\"  score=0.924382  box=[(730,505),(884,525)]",
			"text=\"收款人:李明丽\"  score=0.928979  box=[(47,533),(140,552)]",
			"text=\"复核：齐艳民\"  score=0.989830  box=[(290,535),(367,551)]",
			"text=\"开票人：韩艳红\"  score=0.986418  box=[(474,535),(567,550)]",
			"text=\"销售方：(章)\"  score=0.926249  box=[(658,534),(732,552)]",
			"text=\"发票专用章\"  score=0.998935  box=[(765,530),(850,556)]"
		};
		Pattern p = Pattern.compile(
			"text=\"((?:[^\"\\\\]|\\\\.)*)\"\\s+score=([0-9.]+)\\s+box=\\[\\((\\d+),(\\d+)\\),\\((\\d+),(\\d+)\\)\\]");
		List<PPOcrV6Result> list = new ArrayList<>();
		for (String line : lines) {
			Matcher m = p.matcher(line);
			if (!m.find()) {
				throw new IllegalArgumentException("OCR 行解析失败: " + line);
			}
			String text = m.group(1)
				.replace("\\\"", "\"").replace("\\\\", "\\")
				.replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t");
			float score = Float.parseFloat(m.group(2));
			int x0 = Integer.parseInt(m.group(3));
			int y0 = Integer.parseInt(m.group(4));
			int x1 = Integer.parseInt(m.group(5));
			int y1 = Integer.parseInt(m.group(6));
			int[][] box = {{x0, y0}, {x1, y0}, {x1, y1}, {x0, y1}};
			list.add(new PPOcrV6Result(text, score, box));
		}
		return list;
	}
}
