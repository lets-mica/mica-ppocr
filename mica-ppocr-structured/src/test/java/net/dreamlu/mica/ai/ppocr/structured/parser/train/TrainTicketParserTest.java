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

import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Result;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.ParserTestSupport;
import net.dreamlu.mica.ai.ppocr.utils.CollUtil;
import org.junit.jupiter.api.Assumptions;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 火车票解析器单元测试。
 *
 * <p>测试数据来源：
 * <ul>
 *   <li>手工 mock：构造 OCR 框模拟典型新版电子客票 / 旧版纸质票版面；</li>
 *   <li>真实 OCR：{@code src/test/resources/ocr-json/train/train{N}.json}，
 *       由 {@link TrainDumpMain} 跑真实图片后保存，文件不存在时通过
 *       {@link Assumptions} 跳过。</li>
 * </ul>
 *
 * <p>图片坐标约定（左上角原点，x→右，y→下）：
 * <pre>{@code
 *   y≈40      始发站            到达站
 *   y≈120     G1234              ¥26.00元
 *   y≈200     02车12A号 二等座    张三
 *   y≈280     出发日期 2024-12-08  时间 14:30
 *   y≈360     车票号 1234567890
 *   y≈420     身份证号 310108200601011234
 * }</pre>
 */
class TrainTicketParserTest extends ParserTestSupport {

	/**
	 * 从 classpath 加载真实 OCR 结果（跳过 ONNX 推理，仅测试解析逻辑）。
	 * 文件缺失时通过 {@link Assumptions#assumeTrue} 跳过测试。
	 */
	private static List<PPOcrV6Result> loadTrainTicket(String name) throws IOException {
		String path = "/ocr-json/train/" + name + ".json";
		InputStream is = TrainTicketParserTest.class.getResourceAsStream(path);
		Assumptions.assumeTrue(is != null, "真实 OCR 数据缺失: " + path + "（运行 TrainDumpMain 生成）");
		List<PPOcrV6Result> list = new ArrayList<>();
		Pattern p = Pattern.compile(
			"\"text\":\"((?:[^\"\\\\]|\\\\.)*)\".*\"box\":\\[" +
				"\\[(\\d+),(\\d+)\\],\\[(\\d+),(\\d+)\\],\\[(\\d+),(\\d+)\\],\\[(\\d+),(\\d+)\\]\\]");
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
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

	/**
	 * 新版电子客票：标签 + 值独立框版式。
	 */
	@Test
	void parse_electronicTicket() {
		List<PPOcrV6Result> results = CollUtil.listOf(
			// 顶部行程
			box("始发站", 50, 40, 130, 70),
			box("北京南站", 150, 40, 290, 70),
			box("到达站", 400, 40, 480, 70),
			box("廊坊站", 500, 40, 620, 70),
			// 车次
			box("车次", 50, 110, 90, 140),
			box("G1234", 100, 110, 180, 140),
			// 金额
			box("车票金额", 400, 110, 480, 140),
			box("￥26.00元", 490, 110, 600, 140),
			// 座位与席别
			box("座位号", 50, 200, 110, 230),
			box("02车12A号", 120, 200, 260, 230),
			box("席别", 300, 200, 350, 230),
			box("二等座", 360, 200, 440, 230),
			// 乘客
			box("姓名", 500, 200, 540, 230),
			box("张三", 550, 200, 600, 230),
			// 日期时间
			box("出发日期", 50, 280, 130, 310),
			box("2024年12月08日", 140, 280, 320, 310),
			box("出发时间", 350, 280, 430, 310),
			box("14:30", 440, 280, 510, 310),
			// 票号
			box("车票号", 50, 360, 120, 390),
			box("1234567890", 130, 360, 250, 390),
			// 身份证
			box("身份证号", 50, 420, 130, 450),
			box("310108200601011234", 140, 420, 360, 450)
		);
		TrainTicketResult r = parse(new TrainTicketParser(null), results);
		assertNotNull(r);
		assertEquals("北京南", r.getDeparture());
		assertEquals("廊坊", r.getArrival());
		assertEquals("G1234", r.getTrainNumber());
		assertEquals("2024年12月08日", r.getDepartureDate());
		assertEquals("14:30", r.getDepartureTime());
		assertEquals("02车12A号", r.getSeatNumber());
		assertEquals("二等座", r.getSeatClass());
		assertEquals("张三", r.getPassengerName());
		assertEquals("310108200601011234", r.getIdNumber());
		assertEquals("¥26.00元", r.getAmount());
		assertEquals("1234567890", r.getTicketNo());
	}

	/**
	 * 旧版纸质票：标签与值合并到一个框（如 "始发站 北京南站"）。
	 */
	@Test
	void parse_paperTicketMergedLabel() {
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("始发站 北京南站", 50, 40, 320, 70),
			box("到达站 廊坊站", 400, 40, 620, 70),
			box("G1234", 100, 110, 180, 140),
			box("￥26.00元", 400, 110, 600, 140),
			box("02车12A号", 120, 200, 260, 230),
			box("二等座", 300, 200, 380, 230),
			box("张三", 500, 200, 560, 230),
			box("2024年12月08日 14:30", 140, 280, 510, 310),
			box("1234567890", 130, 360, 250, 390),
			box("310108200601011234", 140, 420, 360, 450)
		);
		TrainTicketResult r = parse(new TrainTicketParser(null), results);
		assertNotNull(r);
		assertEquals("北京南", r.getDeparture());
		assertEquals("廊坊", r.getArrival());
		assertEquals("G1234", r.getTrainNumber());
		assertEquals("02车12A号", r.getSeatNumber());
		assertEquals("张三", r.getPassengerName());
	}

	/**
	 * 动车 D 字头车次。
	 */
	@Test
	void parse_dPrefixTrainNumber() {
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("始发站", 50, 40, 130, 70),
			box("上海虹桥", 150, 40, 290, 70),
			box("到达站", 400, 40, 480, 70),
			box("杭州东", 500, 40, 620, 70),
			box("D456", 100, 110, 180, 140),
			box("￥73.00元", 490, 110, 600, 140)
		);
		TrainTicketResult r = parse(new TrainTicketParser(null), results);
		assertNotNull(r);
		assertEquals("上海虹桥", r.getDeparture());
		assertEquals("杭州东", r.getArrival());
		assertEquals("D456", r.getTrainNumber());
		assertEquals("¥73.00元", r.getAmount());
	}

	/**
	 * 普快 K 字头。
	 */
	@Test
	void parse_kPrefixTrainNumber() {
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("始发站 北京西", 50, 40, 320, 70),
			box("到达站 郑州", 400, 40, 620, 70),
			box("K789", 100, 110, 180, 140),
			box("￥148.50元", 490, 110, 600, 140),
			box("硬座", 360, 200, 420, 230)
		);
		TrainTicketResult r = parse(new TrainTicketParser(null), results);
		assertNotNull(r);
		assertEquals("北京西", r.getDeparture());
		assertEquals("郑州", r.getArrival());
		assertEquals("K789", r.getTrainNumber());
		assertEquals("硬座", r.getSeatClass());
		assertEquals("¥148.50元", r.getAmount());
	}

	/**
	 * 改签标识 + 不含税金额（电子客票常见字段）。
	 */
	@Test
	void parse_eTicketAdditionalFields() {
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("始发站 北京南", 50, 40, 320, 70),
			box("到达站 廊坊", 400, 40, 620, 70),
			box("G1234", 100, 110, 180, 140),
			box("车票金额 ￥26.00元", 400, 110, 620, 140),
			box("不含税金额 ￥23.01元", 400, 150, 620, 180),
			box("电子客票号", 50, 280, 150, 310),
			box("0000000000000000000000000", 160, 280, 510, 310),
			box("发票号码", 50, 360, 130, 390),
			box("00000000000000000000", 140, 360, 460, 390),
			box("开票日期", 50, 420, 130, 450),
			box("2024年12月10日", 140, 420, 360, 450),
			box("标识 始发改签", 50, 480, 200, 510)
		);
		TrainTicketResult r = parse(new TrainTicketParser(null), results);
		assertNotNull(r);
		assertEquals("¥26.00元", r.getAmount());
		assertEquals("¥23.01元", r.getAmountExcludingTax());
		assertEquals("始发改签", r.getChangedFlag());
	}

	/**
	 * 金额货币符号归一化：票面全角 ￥ 统一输出半角 ¥。
	 *
	 * <p>票面字形随票据版本而变（老版蓝票全角、新版电子票半角），rec 模型忠实照抄；
	 * 解析层统一成半角，下游不必两个符号都判。
	 */
	@Test
	void parse_amountCurrencySymbolNormalizedToHalfWidth() {
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("始发站", 50, 40, 130, 70),
			box("北京南站", 150, 40, 290, 70),
			box("到达站", 400, 40, 480, 70),
			box("廊坊站", 500, 40, 620, 70),
			box("G1234", 100, 110, 180, 140),
			// 老版蓝票：全角 ￥
			box("￥26.00元", 490, 110, 600, 140)
		);
		TrainTicketResult r = parse(new TrainTicketParser(null), results);
		assertNotNull(r);
		assertEquals("¥26.00元", r.getAmount());
	}

	/**
	 * 身份证部分脱敏（3101082006****0000），P0 优化保留原样。
	 *
	 * <p>旧版要求"剥星号后剩 18 位"，实际 OCR 经常输出 14 位数字 + 4 个 * 的脱敏形式
	 * （14+4=18 不对，因为 * 不算数字）。P0 优化：剥星号后 14-18 位数字都返回脱敏原样，
	 * 业务可自行决定是否使用。
	 */
	@Test
	void parse_partialMaskedIdNumber() {
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("始发站 北京南", 50, 40, 320, 70),
			box("到达站 廊坊", 400, 40, 620, 70),
			box("G1234", 100, 110, 180, 140),
			box("身份证号", 50, 200, 130, 230),
			box("3101082006****0000", 140, 200, 360, 230)
		);
		TrainTicketResult r = parse(new TrainTicketParser(null), results);
		assertNotNull(r);
		// P0 优化：保留脱敏形式，业务可后续处理
		assertEquals("3101082006****0000", r.getIdNumber());
	}

	/**
	 * 时间格式兼容：HH:mm / H:mm / 0:00。
	 */
	@Test
	void parse_timeFormats() {
		List<PPOcrV6Result> results = CollUtil.listOf(
			box("始发站", 50, 40, 130, 70),
			box("北京南站", 150, 40, 290, 70),
			box("到达站", 400, 40, 480, 70),
			box("天津站", 500, 40, 620, 70),
			box("出发日期", 50, 200, 130, 230),
			box("2024-12-08", 140, 200, 320, 230),
			box("时间", 350, 200, 400, 230),
			box("0:00", 410, 200, 470, 230)
		);
		TrainTicketResult r = parse(new TrainTicketParser(null), results);
		assertNotNull(r);
		assertEquals("2024-12-08", r.getDepartureDate());
		assertEquals("0:00", r.getDepartureTime());
	}

	/**
	 * 空结果：所有字段应为 null，不抛异常。
	 */
	@Test
	void parse_emptyResults() {
		TrainTicketResult r = parse(new TrainTicketParser(null), CollUtil.listOf());
		assertNotNull(r);
		assertEquals(null, r.getDeparture());
		assertEquals(null, r.getArrival());
		assertEquals(null, r.getTrainNumber());
		assertEquals(null, r.getPassengerName());
	}

	// ====================================================================
	// 真实图片 OCR 测试（数据来源：src/test/resources/ocr-json/train/train{N}.json）
	//   由 TrainDumpMain 跑 test_images/train/train{N}.png 真实图片后保存。
	//   文件缺失时通过 Assumptions 跳过测试。
	// ====================================================================

	/**
	 * train1：北京南 → 廊坊 电子发票（始发改签）。
	 *
	 * <p>P0 优化：放宽 departure minX 阈值到 500，识别"北京南"为始发站。
	 * P0 优化：从合并框"2024年12月08日00:00开"切出出发日期/时间。
	 * P0 优化：保留部分脱敏身份证号"3101082006****0000"原样。
	 * P0 优化：ticketNo 放宽到 7-10 位（实际票号 OCR 噪声 7-10 位都常见）。
	 */
	@Test
	void parse_train1() throws IOException {
		TrainTicketResult r = parse(new TrainTicketParser(null), loadTrainTicket("train1"));
		assertNotNull(r);
		assertEquals("北京南", r.getDeparture());
		assertEquals("廊坊", r.getArrival());
		// P0 优化：合并框"2024年12月08日00:00开"切出 date + time
		assertEquals("2024年12月08日", r.getDepartureDate());
		assertEquals("00:00", r.getDepartureTime());
		assertEquals("00车00D号", r.getSeatNumber());
		assertEquals("二等座", r.getSeatClass());
		assertEquals("小度", r.getPassengerName());
		// P0 优化：保留脱敏形式（业务可决定是否使用）
		assertEquals("3101082006****0000", r.getIdNumber());
		assertEquals("¥26.00", r.getAmount());
		assertEquals(null, r.getAmountExcludingTax());
		// 票号：train1 没有 7-10 位纯数字票号（电子发票版无纸质票号）
		assertEquals(null, r.getTicketNo());
		assertEquals("00000000000000000000", r.getInvoiceNo());
		// P1 优化：eTicketNo 全图 25 位纯数字兜底
		assertEquals("0000000000000000000000000", r.getETicketNo());
		assertEquals("2024年12月10日", r.getInvoiceDate());
		assertEquals(null, r.getSellStation());
		assertEquals(null, r.getSerialNumber());
		assertEquals("始发改签", r.getChangedFlag());
		// P1 优化：GO000 OCR 噪声 → G0000（O 归一化为 0）
		assertEquals("G0000", r.getTrainNumber());
	}

	/**
	 * train2：天津 → 北京南 C2038 二等座。
	 *
	 * <p>P0 优化：放宽 departure minX 阈值到 500，识别"天津"为始发站。
	 * P0 优化：从合并框"2019年09月28日12:33开"切出日期+时间。
	 * P0 优化：ticketNo 放宽到 7-10 位，识别"E014470"为车票号。
	 * P0 优化：身份证"2024231998****156X赵璇丽" 合并框剥出"赵璇丽"。
	 * P0 优化：票面底部"天津售"兜底识别售站。
	 */
	@Test
	void parse_train2() throws IOException {
		TrainTicketResult r = parse(new TrainTicketParser(null), loadTrainTicket("train2"));
		assertNotNull(r);
		assertEquals("天津", r.getDeparture());
		assertEquals("北京南", r.getArrival());
		assertEquals("C2038", r.getTrainNumber());
		assertEquals("2019年09月28日", r.getDepartureDate());
		assertEquals("12:33", r.getDepartureTime());
		assertEquals("08车06B号", r.getSeatNumber());
		assertEquals("二等座", r.getSeatClass());
		// P0 优化：从"2024231998****156X赵璇丽"剥出"赵璇丽"
		assertEquals("赵璇丽", r.getPassengerName());
		assertEquals("2024231998****156X", r.getIdNumber());
		assertEquals("¥54.5元", r.getAmount());
		assertEquals(null, r.getAmountExcludingTax());
		// P0 优化：E014470 7 位纯数字（OCR 把 0 误识别为 O，但 E/R/U 字头保留）
		assertEquals("E014470", r.getTicketNo());
		assertEquals(null, r.getInvoiceNo());
		assertEquals(null, r.getETicketNo());
		assertEquals(null, r.getInvoiceDate());
		// P0 优化：底部"天津售"兜底
		assertEquals("天津", r.getSellStation());
		assertEquals(null, r.getSerialNumber());
		assertEquals(null, r.getChangedFlag());
	}

	/**
	 * train3：银川 → 北京 K1178 硬卧。
	 *
	 * <p>P0 优化：识别"银川"为始发站，"北京"为到达站。
	 * P0 优化：从合并框"2019年10月06日16:05开"切出日期+时间。
	 * P0 优化：座位号"14车015号上铺"合并框切出"14车015号"（剥上铺）。
	 * P0 优化：身份证"3424231998****1540裴丽丽"剥出"裴丽丽"。
	 * P0 优化：底部"银川售"识别为售站。
	 */
	@Test
	void parse_train3() throws IOException {
		TrainTicketResult r = parse(new TrainTicketParser(null), loadTrainTicket("train3"));
		assertNotNull(r);
		assertEquals("银川", r.getDeparture());
		assertEquals("北京", r.getArrival());
		assertEquals("K1178", r.getTrainNumber());
		assertEquals("2019年10月06日", r.getDepartureDate());
		assertEquals("16:05", r.getDepartureTime());
		// P0 优化："14车015号上铺" → "14车015号"
		assertEquals("14车015号", r.getSeatNumber());
		assertEquals("硬卧", r.getSeatClass());
		// P0 优化：身份证+姓名合并框剥出
		assertEquals("裴丽丽", r.getPassengerName());
		assertEquals("3424231998****1540", r.getIdNumber());
		assertEquals("¥280.5元", r.getAmount());
		assertEquals(null, r.getAmountExcludingTax());
		assertEquals("R093443", r.getTicketNo());
		assertEquals(null, r.getInvoiceNo());
		assertEquals(null, r.getETicketNo());
		assertEquals(null, r.getInvoiceDate());
		// P0 优化：底部"银川售"兜底
		assertEquals("银川", r.getSellStation());
		assertEquals(null, r.getSerialNumber());
		assertEquals(null, r.getChangedFlag());
	}

	/**
	 * train4：平顶山西 → 上海 K284 硬卧。
	 *
	 * <p>P0 优化：识别"平顶山西"为始发站（站名"西"后缀不会被误判为人名）。
	 * P0 优化：识别"上海"为到达站。
	 * P0 优化：从合并框"2014年09月09日15:52开"切出日期+时间。
	 * P0 优化：身份证"4114211992****4212" 脱敏保留。
	 */
	@Test
	void parse_train4() throws IOException {
		TrainTicketResult r = parse(new TrainTicketParser(null), loadTrainTicket("train4"));
		assertNotNull(r);
		assertEquals("平顶山西", r.getDeparture());
		assertEquals("上海", r.getArrival());
		assertEquals("K284", r.getTrainNumber());
		assertEquals("2014年09月09日", r.getDepartureDate());
		assertEquals("15:52", r.getDepartureTime());
		// P0 优化："04车019号中铺" → "04车019号"
		assertEquals("04车019号", r.getSeatNumber());
		assertEquals("硬卧", r.getSeatClass());
		// train4 OCR 漏识别姓名（"4114211992****4212" 后面没接姓名）
		assertEquals(null, r.getPassengerName());
		assertEquals("4114211992****4212", r.getIdNumber());
		assertEquals("¥194.50元", r.getAmount());
		assertEquals(null, r.getAmountExcludingTax());
		// P0 优化：票号"U028534"在票面顶部（独立框），是 E/R/U 前缀的合法票号
		assertEquals("U028534", r.getTicketNo());
		assertEquals(null, r.getInvoiceNo());
		assertEquals(null, r.getETicketNo());
		assertEquals(null, r.getInvoiceDate());
		// train4 没有"XX售"模式
		assertEquals(null, r.getSellStation());
		assertEquals(null, r.getSerialNumber());
		assertEquals(null, r.getChangedFlag());
	}

	/**
	 * train5：青岛→青州市 越站补票 L0956 二等座。
	 *
	 * <p>P0 优化：识别"青州市"为始发站（左侧）。
	 * P0 优化：trainNumber 排除"原票"上下文，"L0956"不再被误识别为车次。
	 * P0 优化：底部"4901016740709L028534济局青客补"识别售站。
	 */
	@Test
	void parse_train5() throws IOException {
		TrainTicketResult r = parse(new TrainTicketParser(null), loadTrainTicket("train5"));
		assertNotNull(r);
		// P0 优化："青州市站"在左侧 (minX=367) → 兜底为始发站
		assertEquals("青州市", r.getDeparture());
		// P0 优化："北京南站"在右侧 (minX=980) → 兜底为到达站
		assertEquals("北京南", r.getArrival());
		// P0 优化：trainNumber 排除"原票"上下文 → null（旧版错识别 L0956）
		assertEquals(null, r.getTrainNumber());
		assertEquals("2019年07月09日", r.getDepartureDate());
		assertEquals(null, r.getDepartureTime());
		assertEquals(null, r.getSeatNumber());
		assertEquals("二等座", r.getSeatClass());
		assertEquals(null, r.getPassengerName());
		assertEquals(null, r.getIdNumber());
		assertEquals("¥241.0元", r.getAmount());
		assertEquals(null, r.getAmountExcludingTax());
		// P0 优化：票号"U028534"独立框（票面顶部）是合法票号
		assertEquals("U028534", r.getTicketNo());
		assertEquals(null, r.getInvoiceNo());
		assertEquals(null, r.getETicketNo());
		assertEquals(null, r.getInvoiceDate());
		// P0 优化：底部"济局青客补"含"青"字，但"补"是关键词"XX售"不是"XX补"
		// 所以 SELL_STATION_PATTERN 不匹配
		assertEquals(null, r.getSellStation());
		assertEquals(null, r.getSerialNumber());
		assertEquals(null, r.getChangedFlag());
	}
}
