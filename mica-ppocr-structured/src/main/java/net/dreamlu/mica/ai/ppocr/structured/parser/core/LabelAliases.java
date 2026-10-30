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

import net.dreamlu.mica.ai.ppocr.utils.CollUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 字段标签别名表：OCR 认错标签的多种写法 → 规范标签。
 *
 * <p><b>解决什么问题</b>：证件上的标签是印刷体，OCR 常见三类走样 ——
 * <ul>
 *   <li><b>多字</b>："公民身份号码" 被认成 "公民身份证号码"；</li>
 *   <li><b>带空格</b>："姓名" 被认成 "姓 名"；</li>
 *   <li><b>换说法</b>："初次领证日期" 被认成 "初次领证年月日"。</li>
 * </ul>
 * {@link LabelMatcher} 的三级回退（完整等于 &gt; 以标签开头 &gt; 标签包含文本）
 * <b>只能覆盖"子串"方向</b>：标签必须包含识别文本才行。上面第 1、3 类走不通，
 * 于是标签定位整体失效 —— 后面所有字段跟着塌。
 *
 * <p><b>用法</b>：{@link LabelMatcher#findLabelBox} 在三级回退<b>全部落空</b>后，
 * 才用本表兜底。因此本表<b>只增不减</b>：规范标签能查到时行为完全不变。
 *
 * <p><b>收录原则</b>：只收「同一版式上不会与其它字段标签混淆」的写法。
 * 像 "VALID"/"THRU" 这类英文标签<b>不收</b> —— 银行卡上的 "VALID THRU"
 * 已被三级回退的 fragment 分支覆盖，收进去反而容易把 "THRU" 当成标签框。
 */
public final class LabelAliases {

	/**
	 * 别名表是否启用（默认启用）。关闭后 {@link LabelMatcher} 的标签定位退回到
	 * 纯三级回退行为，便于线上出问题时的快速止血。
	 */
	private static volatile boolean enabled = true;

	/**
	 * 别名表。整体替换而非就地修改，读侧无锁。
	 */
	private static volatile Map<String, List<String>> aliasTable = buildAliases();

	/**
	 * 工具类，禁止实例化。
	 */
	private LabelAliases() {
	}

	/**
	 * 构建别名表。
	 *
	 * @return 别名表
	 */
	private static Map<String, List<String>> buildAliases() {
		Map<String, List<String>> m = new LinkedHashMap<>();

		// ---------- 身份证 ----------
		m.put("公民身份号码", CollUtil.listOf(
			"公民身份证号码", "公民身份证号", "公民身份号", "身份证号码", "身份证号", "身份号码"));
		m.put("姓名", CollUtil.listOf("姓 名"));
		m.put("性别", CollUtil.listOf("性 别"));
		m.put("民族", CollUtil.listOf("民 族"));
		m.put("住址", CollUtil.listOf("住 址"));
		m.put("出生", CollUtil.listOf("出 生"));
		m.put("签发机关", CollUtil.listOf("签 发 机 关"));
		m.put("有效期限", CollUtil.listOf("有 效 期 限"));

		// ---------- 机动车驾驶证 ----------
		m.put("证号", CollUtil.listOf("证 号", "证件号码", "身份证号码", "身份证号", "驾驶证号"));
		m.put("国籍", CollUtil.listOf("国 籍"));
		m.put("准驾车型", CollUtil.listOf("准 驾 车 型"));
		m.put("出生日期", CollUtil.listOf("出生年月日", "出生 年 月 日", "出生日期 Birth Date"));
		m.put("初次领证日期", CollUtil.listOf("初次领证年月日", "初次领证 年 月 日", "初次领证日 期"));
		m.put("有效期限", CollUtil.listOf("有效期限 Valid", "有 效 期 限"));

		// ---------- 机动车行驶证 ----------
		m.put("号牌号码", CollUtil.listOf("号 牌 号 码"));
		m.put("车辆识别代号", CollUtil.listOf("车 辆 识 别 代 号", "车辆识别代码", "车辆识别代号 VIN"));

		// ---------- 营业执照 ----------
		m.put("统一社会信用代码", CollUtil.listOf(
			"统一社会信用代", "统一社会信用代码 USCC", "社会信用代码", "信用代码"));
		m.put("名称", CollUtil.listOf("名 称"));
		m.put("类型", CollUtil.listOf("类 型"));
		m.put("法定代表人", CollUtil.listOf("法 定 代 表 人", "法定代表人 Legal"));
		m.put("负责人", CollUtil.listOf("负 责 人"));
		m.put("注册资本", CollUtil.listOf("注 册 资 本"));
		m.put("成立日期", CollUtil.listOf("成 立 日 期"));
		m.put("营业期限", CollUtil.listOf("营 业 期 限"));
		m.put("住所", CollUtil.listOf("住 所", "营业场所"));
		m.put("经营范围", CollUtil.listOf("经 营 范 围"));
		m.put("注册号", CollUtil.listOf("注 册 号"));

		return Collections.unmodifiableMap(m);
	}

	/**
	 * 取某规范标签的别名列表。
	 *
	 * @param label 规范标签
	 * @return 别名列表；无别名时返回空列表
	 */
	public static List<String> aliasesOf(String label) {
		if (label == null) {
			return CollUtil.listOf();
		}
		List<String> list = aliasTable.get(label);
		return list == null ? CollUtil.listOf() : list;
	}

	/**
	 * 别名表是否启用。
	 *
	 * @return true 表示启用
	 */
	public static boolean isEnabled() {
		return enabled;
	}

	/**
	 * 开关别名表（线上止血用）。
	 *
	 * @param value true 启用，false 关闭
	 */
	public static void setEnabled(boolean value) {
		enabled = value;
	}

	/**
	 * 注册新的别名（供业务方按自己的版式扩展）。
	 *
	 * <p>⚠️ 别名会参与标签定位，请确保它<b>不会与本证件其它字段标签混淆</b>。
	 *
	 * @param label      规范标签
	 * @param altAliases 别名列表
	 */
	public static void register(String label, String... altAliases) {
		if (label == null || altAliases == null || altAliases.length == 0) {
			return;
		}
		synchronized (LabelAliases.class) {
			Map<String, List<String>> copy = new LinkedHashMap<>(aliasTable);
			List<String> existing = copy.get(label);
			List<String> merged = new ArrayList<>(existing == null ? CollUtil.<String>listOf() : existing);
			for (String a : altAliases) {
				if (a != null && !a.isEmpty() && !merged.contains(a)) {
					merged.add(a);
				}
			}
			copy.put(label, Collections.unmodifiableList(merged));
			aliasTable = Collections.unmodifiableMap(copy);
		}
	}
}
