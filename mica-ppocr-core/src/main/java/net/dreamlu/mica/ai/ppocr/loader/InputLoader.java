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

import java.io.IOException;
import java.util.List;

/**
 * OCR 输入加载器 SPI：把 {@link OcrInput}（字节 / 路径 / 流）解析为引擎可直接处理的
 * {@link Page} 列表。
 *
 * <h3>使用流程</h3>
 * <ol>
 *   <li>{@link #canLoad(OcrInput)}：在引擎入口处做轻量判定（魔数嗅探、kind 匹配）。</li>
 *   <li>{@link #load(OcrInput, LoaderContext)}：被引擎选中后真正执行加载，可能涉及
 *       PDF 双通道、TIFF 多页、Word 排版还原等场景，资源消耗较大。</li>
 * </ol>
 *
 * <h3>注册方式</h3>
 * Java 原生 SPI：实现类放在 classpath 下
 * {@code META-INF/services/net.dreamlu.mica.ai.ppocr.loader.InputLoader}，
 * 引擎用 {@link java.util.ServiceLoader} 加载。多个实现按声明顺序匹配。
 *
 * <h3>实现契约</h3>
 * <ul>
 *   <li>{@code canLoad} 应只做轻量嗅探，不要触发重资源加载（PDF 全量解析、IO 等）。</li>
 *   <li>{@code load} 抛出 {@link IOException} 时引擎会原样透传给调用方。</li>
 *   <li>返回的 {@link Page} 字节必须是可直接喂给
 *       {@code PPOcrV6Engine.run(byte[])} 的格式（PNG / JPG / BMP 等 OpenCV
 *       直接支持的位图格式）；不要返回 PDF 字节 / SVG / 文本 JSON 等中间态。</li>
 *   <li>建议实现类为 public、有 public 无参构造（ServiceLoader 反射要求）。</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * 实现应为无状态，引擎以单例持有。
 */
public interface InputLoader {

	/**
	 * 能否处理该输入。
	 *
	 * <p>只做轻量嗅探（如 {@code %PDF-} 魔数、{@code 89504E47} PNG 头），禁止触发
	 * 完整文件 IO 或 PDF 全量解析。返回 false 时引擎继续匹配下一个 loader。
	 *
	 * @param input 输入
	 * @return true 表示该 loader 可处理
	 */
	boolean canLoad(OcrInput input);

	/**
	 * 加载输入为页列表。
	 *
	 * <p>在 {@link #canLoad(OcrInput)} 返回 true 后由引擎调用。
	 *
	 * @param input   输入
	 * @param context 加载上下文（含引擎引用、配置等）
	 * @return 页列表（至少 1 条）
	 * @throws IOException 解析失败
	 */
	List<Page> load(OcrInput input, LoaderContext context) throws IOException;

	/**
	 * 优先级（数值越小越优先）。仅用于引擎内部的 loader 排序，{@code canLoad} 失败
	 * 的 loader 仍会被跳过。
	 *
	 * <p>默认 0。PDF / TIFF 这类重资源格式建议返回 -10 以避免误抢图片。
	 *
	 * @return 优先级
	 */
	default int priority() {
		return 0;
	}
}
