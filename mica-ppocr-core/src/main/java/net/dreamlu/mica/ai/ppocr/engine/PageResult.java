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

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;
import net.dreamlu.mica.ai.ppocr.loader.Page;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * OCR 多页输入的 per-page 结果：页码 + 文本框列表 + 来源元数据。
 *
 * <p>作为 {@code PPOcrV6Engine.runPages(OcrInput)} 的元素，承接
 * {@link Page#meta()} 中的元数据（{@code viaOcr} / {@code sourceDpi} 等）。
 *
 * <p>与 mica-ppocr-pdf 模块的 {@code PdfPageResult} 等价：保持 core / 业务层零依赖 PDF。
 * 业务方用 {@code PPOcrTemplate.pdf().parse(file.getBytes())} 走 PDF loader，
 * 拿到 {@code List<PageResult>} 即可与原 PDF 通道完全兼容。
 */
@Getter
@ToString
@EqualsAndHashCode
@Accessors(fluent = true)
@AllArgsConstructor
public class PageResult {

	/**
	 * 页序号（与 {@link Page#index()} 一致，0 起）。
	 */
	private final int pageIndex;
	/**
	 * 该页文本框列表（与 {@code PPOcrV6Engine.run(...)} 元素同构）。
	 */
	private final List<PPOcrV6Result> results;
	/**
	 * 来源元数据（来自 {@link Page#meta()}）。可能为空。
	 */
	private final Map<String, Object> meta;

	/**
	 * 便捷构造器（无 meta）。
	 *
	 * @param pageIndex 页序号
	 * @param results   文本框列表
	 */
	public PageResult(int pageIndex, List<PPOcrV6Result> results) {
		this(pageIndex, results, Collections.emptyMap());
	}

	/**
	 * 是否走了 OCR 通道（PDF 渲染通道下为 true；图片 / 文本层通道为 false）。
	 *
	 * @return meta 缺失时返回 false
	 */
	public boolean viaOcr() {
		Object v = meta.get("viaOcr");
		return v instanceof Boolean && (Boolean) v;
	}
}
