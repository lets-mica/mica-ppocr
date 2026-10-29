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

package net.dreamlu.mica.ai.ppocr.engine;

import net.dreamlu.mica.ai.ppocr.config.PPOcrV6Config;
import org.opencv.core.Core;

import java.util.List;

/**
 * 独立 JVM 探针 —— 验证「{@link PPOcrV6Engine} 构造期完成 OpenCV 原生库加载」。
 *
 * <p><b>为什么必须独立 JVM</b>：本仓库 surefire 配了 {@code forkCount=0}，全部测试共享同一个 JVM。
 * 而其它测试在 {@code @BeforeAll} 里显式调 {@code OpenCV.loadLocally()}，
 * 一旦它们先跑，本 JVM 的 native 就已加载 ⇒ 「引擎是否自己加载」永远无法被观察到
 * （断言恒为真 = 没有牙齿）。
 * 只有在一个<b>全新 JVM</b> 里，才能确定性地验证「不手工 load 也能用」。
 *
 * <p><b>探测原理</b>：调用一个<b>真正打到 native</b> 的方法 —— {@code Core.getVersionString()}
 * （内部是 {@code native getVersionString_0()}）。native 未加载时抛 {@link UnsatisfiedLinkError}。
 * ⚠️ 注意<b>不能</b>用「触碰 {@code org.opencv.core.Core} 类」当探测手段：openpnp 的包装类把
 * {@code getVersion()} 实现成了返回常量 {@code "4.9.0"} 的纯 Java 方法，
 * 静态初始化块里没有任何 native 调用 ⇒ 触碰该类永远不会失败，断言会恒为真。
 * 于是：
 * <ol>
 *     <li>构造引擎<b>前</b>调 {@code Core.getVersionString()} → 必须失败（证明 native 确实还没加载）</li>
 *     <li>构造引擎（全程<b>不</b>显式调 {@code OpenCV.loadLocally()}）</li>
 *     <li>构造引擎<b>后</b>调 {@code Core.getVersionString()} → 必须成功（证明是引擎加载的）</li>
 *     <li>真跑一次推理，确认整条链路不依赖手工 load</li>
 * </ol>
 *
 * <p>用法：{@code java -cp <classpath> net.dreamlu.mica.ai.ppocr.engine.OpenCvNativeProbe
 * <det.onnx> <rec.onnx> <dict.txt> [image]}
 */
public final class OpenCvNativeProbe {

	/**
	 * 子进程成功时打印的标记。
	 */
	public static final String OK_MARKER = "OPENCV_NATIVE_LOADED_BY_ENGINE";

	/**
	 * 退出码：参数非法。
	 */
	private static final int EXIT_BAD_ARGS = 2;

	/**
	 * 退出码：探针前提不成立（子 JVM 启动时 native 已加载）。
	 */
	private static final int EXIT_PRECONDITION = 3;

	/**
	 * 退出码：构造引擎后 native 仍不可用。
	 */
	private static final int EXIT_NOT_LOADED = 4;

	/**
	 * 退出码：推理结果为空。
	 */
	private static final int EXIT_EMPTY_RESULT = 5;

	private OpenCvNativeProbe() {
	}

	/**
	 * 探针入口。
	 *
	 * @param args det.onnx / rec.onnx / dict.txt 路径，可选第 4 个参数为待识别图片
	 */
	public static void main(String[] args) {
		if (args.length < 3) {
			System.err.println("usage: OpenCvNativeProbe <det.onnx> <rec.onnx> <dict.txt> [image]");
			System.exit(EXIT_BAD_ARGS);
		}
		// ① 前置断言：子 JVM 启动时 OpenCV native 尚未加载
		if (isOpenCvNativeUsable()) {
			System.err.println("探针前提不成立：JVM 启动时 OpenCV native 已加载，无法验证引擎是否自行加载");
			System.exit(EXIT_PRECONDITION);
		}
		PPOcrV6Config config = PPOcrV6Config.builder()
			.detModelPath(args[0])
			.recModelPath(args[1])
			.recCharDictPath(args[2])
			.build();
		// ② 构造引擎（全程不显式调 OpenCV.loadLocally()）
		try (PPOcrV6Engine engine = new PPOcrV6Engine(config)) {
			// ③ 构造后 native 必须已可用
			if (!isOpenCvNativeUsable()) {
				System.err.println("构造引擎后 OpenCV native 仍不可用");
				System.exit(EXIT_NOT_LOADED);
			}
			// ④ 真跑一次推理，确认整条链路（检测 → 裁剪 → 识别）不依赖手工 load
			if (args.length >= 4) {
				List<PPOcrV6Result> results = engine.run(args[3]);
				if (results.isEmpty()) {
					System.err.println("推理结果为空");
					System.exit(EXIT_EMPTY_RESULT);
				}
			}
		}
		System.out.println(OK_MARKER);
	}

	/**
	 * 探测 OpenCV native 是否已可用。
	 *
	 * <p>{@code Core.getVersionString()} 会真正调到 native 方法
	 * （{@code getVersionString_0()}），native 未加载时抛 {@link UnsatisfiedLinkError}。
	 *
	 * @return native 已加载则返回 true
	 */
	private static boolean isOpenCvNativeUsable() {
		try {
			String version = Core.getVersionString();
			return version != null && !version.isEmpty();
		} catch (Throwable t) {
			return false;
		}
	}
}
