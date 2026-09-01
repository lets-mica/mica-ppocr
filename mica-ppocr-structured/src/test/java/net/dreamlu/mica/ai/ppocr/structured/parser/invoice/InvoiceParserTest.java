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
}
