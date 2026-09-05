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

package net.dreamlu.mica.ai.ppocr.loader;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import lombok.experimental.Accessors;
import net.dreamlu.mica.ai.ppocr.config.PPOcrV6Config;
import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Engine;

/**
 * 加载上下文：把引擎 / 配置 / 用户自定义参数透传给 {@link InputLoader}。
 *
 * <p>对 PDF loader 来说，最关键的就是 {@link #engine}（用于渲染通道兜底），
 * 以及将来扩展的 DPI / 文本层阈值等参数（通过 {@link #custom} Map 传递）。
 *
 * <p>不可变对象，引擎单例持有。
 */
@Getter
@ToString
@Accessors(fluent = true)
@RequiredArgsConstructor
public final class LoaderContext {

	/**
	 * 推理引擎：loader 需要把"无文本层"或"扫描件"的页走 OCR 通道时调用。
	 */
	private final PPOcrV6Engine engine;
	/**
	 * 引擎配置（线程安全 / DPI 等基础参数）。
	 */
	private final PPOcrV6Config engineConfig;
	/**
	 * 用户自定义参数（如 PDF 的 renderDpi / minTextChars / minReadableRatio）。
	 */
	private final java.util.Map<String, Object> custom;
}
