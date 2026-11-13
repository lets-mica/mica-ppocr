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

package net.dreamlu.mica.ai.ppocr.structured.parser.household;

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
 * 户口本解析器单元测试。
 *
 * <p>真实数据来源：{@code src/test/resources/ocr-json/household_register/household_register{N}.json}，
 * 由 {@link HouseholdRegisterDumpMain} 批量跑真实 OCR 推理后保存，
 * 测试时不依赖 ONNX Runtime / 模型文件，纯 Java 解析逻辑。
 */
class HouseholdRegisterParserTest extends ParserTestSupport {

	private static final Pattern JSON_LINE = Pattern.compile(
		"\"text\":\"((?:[^\"\\\\]|\\\\.)*)\".*?\"box\":\\[" +
			"\\[(\\d+),(\\d+)\\],\\[(\\d+),(\\d+)\\],\\[(\\d+),(\\d+)\\],\\[(\\d+),(\\d+)\\]\\]");

	/**
	 * 从 classpath 加载真实 OCR 结果（跳过 ONNX 推理，仅测试解析逻辑）。
	 */
	private static List<PPOcrV6Result> load(String name) throws IOException {
		String path = "/ocr-json/household_register/" + name + ".json";
		List<PPOcrV6Result> list = new ArrayList<>();
		try (InputStream is = HouseholdRegisterParserTest.class.getResourceAsStream(path);
			 BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				Matcher m = JSON_LINE.matcher(line);
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
		HouseholdRegisterResult r = parse(new HouseholdRegisterParser(null), CollUtil.listOf());
		assertNotNull(r);
		assertNull(r.getHouseholdNo());
		assertNull(r.getName());
		assertNull(r.getIdNumber());
	}

	@Test
	void parse_household_register1() throws IOException {
		HouseholdRegisterResult r = parse(new HouseholdRegisterParser(null), load("household_register1"));
		assertNotNull(r);
		assertEquals("000007670", r.getHouseholdNo());
		assertEquals("王燕", r.getName());
		assertEquals("独生女", r.getRelationship());
		assertEquals("女", r.getGender());
		assertEquals("四川省", r.getBirthPlace());
		assertEquals("汉族", r.getEthnicity());
		assertEquals("四川省", r.getNativePlace());
		assertEquals("1994年7月27日", r.getBirthDate());
		assertEquals("112102199407273123", r.getIdNumber());
		assertEquals("170厘米", r.getHeight());
		assertEquals("初中毕业", r.getEducation());
		assertEquals("无", r.getWorkplace());
		assertEquals("由久居", r.getMoveToCityDate());
		assertEquals("1994年07月27日因出生迁来", r.getMoveToAddress());
		assertEquals("2017年7月27日", r.getRegistrationDate());
	}

	@Test
	void parse_household_register2() throws IOException {
		HouseholdRegisterResult r = parse(new HouseholdRegisterParser(null), load("household_register2"));
		assertNotNull(r);
		assertEquals("505225329", r.getHouseholdNo());
		assertEquals("天天", r.getName());
		assertEquals("户主", r.getRelationship());
		assertEquals("男", r.getGender());
		assertEquals("四川省", r.getBirthPlace());
		assertEquals("汉族", r.getEthnicity());
		assertEquals("四川省", r.getNativePlace());
		// 出生日期被 OCR 切成 2 框（"1961" + "10月21"），需合并
		assertEquals("1961年10月21日", r.getBirthDate());
		assertEquals("116602196110216858", r.getIdNumber());
		// 身高未识别出来（OCR 漏识别）
		assertNull(r.getHeight());
		assertEquals("小学", r.getEducation());
		// 服务处所 OCR 漏识别，仅剩"职"字fragment
		// 工作单位 OCR 漏识别
	}

	@Test
	void parse_household_register3() throws IOException {
		HouseholdRegisterResult r = parse(new HouseholdRegisterParser(null), load("household_register3"));
		assertNotNull(r);
		// 户号：OCR 漏识别（合并框）。正则兜底应取到 110889200111 是部分匹配。
		// 实际没有 7-12 位数字框，因此户号可能为 null
		assertEquals("洋洋", r.getName());
		// 与户主关系:label 已定位但右侧无值（"女"是 gender 值，非 relationship）
		assertNull(r.getRelationship());
		assertEquals("女", r.getGender());
		assertEquals("北京市", r.getBirthPlace());
		// 民族:OCR 漏识别"汉"字（"汉"与"族"被识别成两个框）
		assertEquals("汉族", r.getEthnicity());
		assertEquals("北京市", r.getNativePlace());
		assertEquals("2001年11月28日", r.getBirthDate());
		assertEquals("110889200111284922", r.getIdNumber());
		// 身高:OCR 漏识别
		assertNull(r.getHeight());
		// 文化程度:OCR 识别出"服务情况"（应为"在校生"或类似）
		// 服务处所:OCR 漏识别
		// 何时由何地迁来本市(县):OCR 漏识别值
		// 何时由何地迁往本址:合并框"2003年11月3日昌平区"
		assertEquals("2003年11月3日昌平区", r.getMoveToAddress());
		// 登记日期:合并框"2003年11月日"（缺"3" + "日"前的数字）
		assertEquals("2003年11月", r.getRegistrationDate());
	}

	@Test
	void parse_household_register4() throws IOException {
		HouseholdRegisterResult r = parse(new HouseholdRegisterParser(null), load("household_register4"));
		assertNotNull(r);
		// 户号没识别
		assertEquals("平平", r.getName());
		// 与户主关系:值为"户主"（此人即户主）
		assertEquals("户主", r.getRelationship());
		assertEquals("男", r.getGender());
		assertEquals("江西省", r.getBirthPlace());
		assertEquals("汉族", r.getEthnicity());
		assertEquals("江西省", r.getNativePlace());
		assertEquals("1985年08月25日", r.getBirthDate());
		// 身份证号合并框："310128198508253218身高"
		assertEquals("310128198508253218", r.getIdNumber());
		assertEquals("大学", r.getEducation());
		// 服务处所:OCR 漏识别
		// 何时由何地迁来本市(县):值是合并框"由江西省南昌市迁来"
		assertEquals("由江西省南昌市迁来", r.getMoveToCityDate());
		assertEquals("2000年07月17日", r.getRegistrationDate());
	}

	@Test
	void parse_household_register5() throws IOException {
		HouseholdRegisterResult r = parse(new HouseholdRegisterParser(null), load("household_register5"));
		assertNotNull(r);
		assertEquals("000061467", r.getHouseholdNo());
		assertEquals("苗苗", r.getName());
		assertEquals("户主", r.getRelationship());
		assertEquals("女", r.getGender());
		// 出生地:OCR 漏识别 label，按民族行兜底匹配"浙江省"
		assertEquals("浙江省", r.getBirthPlace());
		assertEquals("汉族", r.getEthnicity());
		assertEquals("浙江省", r.getNativePlace());
		assertEquals("1982年6月18日", r.getBirthDate());
		assertEquals("511006198206180400", r.getIdNumber());
		assertEquals("160", r.getHeight());
		assertEquals("大学", r.getEducation());
		assertEquals("成都市", r.getWorkplace());
		// 何时由何地迁来本市(县):合并框"迁来本市（县）2009年09月29日"剥出日期
		assertEquals("2009年09月29日", r.getMoveToCityDate());
		// 何时由何地迁来本址:合并框"因购房"
		assertEquals("因购房", r.getMoveToAddress());
		// 登记日期:合并框"2019年11月11" + "日"（分开）
		assertEquals("2019年11月11日", r.getRegistrationDate());
	}

	@Test
	void parse_fieldBoxes_registeredOnlyWhenValuePresent() throws IOException {
		// 回归：fieldBoxes 里登记的字段，其结果值必须非空。
		// 修复前「标签在、值没取到」时也会登记框 ⇒ 可视化会高亮一个空字段。
		HouseholdRegisterResult r = parse(new HouseholdRegisterParser(null), load("household_register1"));
		assertNotNull(r);
		assertFalse(r.getFieldBoxes().isEmpty(), "字段框应已回填");
		for (java.util.Map.Entry<String, List<int[][]>> entry : r.getFieldBoxes().entrySet()) {
			String field = entry.getKey();
			assertFalse(entry.getValue().isEmpty(), "fieldBoxes[" + field + "] 的框列表不应为空");
			assertNotNull(fieldValue(r, field), "fieldBoxes[" + field + "] 登记了一个空字段");
		}
	}

	/**
	 * 按字段名取结果值（fieldBoxes 的 key 与结果属性名一一对应）。
	 *
	 * @param r     解析结果
	 * @param field 字段名
	 * @return 字段值；取不到时返回 null
	 */
	private static String fieldValue(HouseholdRegisterResult r, String field) {
		try {
			java.lang.reflect.Method getter = HouseholdRegisterResult.class
				.getMethod("get" + Character.toUpperCase(field.charAt(0)) + field.substring(1));
			Object value = getter.invoke(r);
			return value == null ? null : String.valueOf(value);
		} catch (ReflectiveOperationException e) {
			return null;
		}
	}
}
