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

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;
import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Result;

import java.util.List;

/**
 * PDF 单页 OCR 结果：页码 + 该页文本框列表 + 实际走的通道。
 *
 * <p>{@link #results()} 与 {@code PPOcrV6Engine.run(...)} 的返回元素同构，
 * 可直接喂给 mica-ppocr-structured 的 {@code parseResults(List)} 复用
 * 现有结构化解析层（发票 / 行驶证 / 身份证等）。
 */
@Getter
@ToString
@EqualsAndHashCode
@Accessors(fluent = true)
@AllArgsConstructor
public class PdfPageResult {

	/**
	 * 页码，从 0 开始。
	 */
	private final int pageIndex;
	/**
	 * 该页是否走了"渲染 + OCR"通道：
	 * false = 文本层坐标抽取（字符无损）；true = 渲染位图后 OCR（有模型误差）。
	 */
	private final boolean viaOcr;
	/**
	 * 该页文本框列表（与 {@code PPOcrV6Engine.run} 返回元素同构，可直接结构化解析）。
	 */
	private final List<PPOcrV6Result> results;
}
