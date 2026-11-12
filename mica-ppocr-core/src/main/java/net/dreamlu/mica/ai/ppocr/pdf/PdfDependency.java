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

package net.dreamlu.mica.ai.ppocr.pdf;

/**
 * PDFBox 依赖可用性探测。
 *
 * <p><b>为什么必须单独成类</b>：本类<b>刻意不引用任何 PDFBox 类型</b>（只用类名字符串探测），
 * 因此无论 PDFBox 是否在 classpath 上，本类都能被正常加载、链接、校验。
 * 这是「PDF 依赖可选」能成立的前提 —— 若把探测逻辑与 PDFBox 类型写在同一个类里，
 * 该类链接（link）阶段的字节码校验就会连带触发 PDFBox 类的加载，
 * 缺依赖时会在探测逻辑跑起来之前就先抛 {@link NoClassDefFoundError}，
 * 调用方看到的是一个完全无法定位问题的底层错误。
 *
 * <p><b>用法</b>：{@link #isAvailable()} 返回 {@code false} 时，引擎收到 PDF 输入会抛
 * {@link IllegalStateException} 并在消息里指明要补哪个依赖，
 * 而不是让调用方去追 {@code NoClassDefFoundError}。
 *
 * <p>无状态，线程安全。
 *
 * @author L.cm
 */
public final class PdfDependency {

	/**
	 * 探测标记类：PDFBox 3.x 的统一加载入口。
	 *
	 * <p>PDFBox 2.x 没有这个类（2.x 用 {@code PDDocument.load} 静态方法）。
	 * 本项目按 3.x API 编译，用 3.x 专属类做标记，可以避免
	 * 「classpath 上是 2.x，探测通过但运行时 API 不兼容」的误判。
	 */
	private static final String PROBE_CLASS = "org.apache.pdfbox.Loader";

	/**
	 * 缺依赖时的报错文案。
	 *
	 * <p>同时点明「只做图片 OCR 不需要该依赖」，避免用户误以为这是必需依赖。
	 */
	public static final String MISSING_MESSAGE =
			"PDF 通道不可用：未检测到 PDFBox 依赖。请引入 org.apache.pdfbox:pdfbox:3.0.8 后重试"
				+ "（仅做图片 OCR 的场景无需引入该依赖）";

	/**
	 * 测试钩子：非 {@code null} 时直接作为可用性结论返回。
	 *
	 * <p>仅供包内单测模拟「PDFBox 不在 classpath」场景；生产路径恒为 {@code null}。
	 */
	static volatile Boolean forcedAvailable;

	private PdfDependency() {
	}

	/**
	 * PDFBox 依赖是否可用。
	 *
	 * @return true 表示 PDFBox 可加载，PDF 双通道可用
	 */
	public static boolean isAvailable() {
		Boolean forced = forcedAvailable;
		if (forced != null) {
			return forced;
		}
		return detect();
	}

	/**
	 * 探测 PDFBox 是否可加载。
	 *
	 * <p>用 {@code initialize=false} 只做类加载与链接探测，不执行 PDFBox 的静态初始化，
	 * 避免探测动作本身产生副作用。
	 *
	 * @return true 表示探测到 PDFBox
	 */
	private static boolean detect() {
		try {
			Class.forName(PROBE_CLASS, false, PdfDependency.class.getClassLoader());
			return true;
		} catch (Throwable ignore) {
			// ClassNotFoundException（未引入）与 LinkageError（版本不兼容）都视为"不可用"
			return false;
		}
	}
}
