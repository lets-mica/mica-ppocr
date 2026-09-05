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

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * OCR 输入页：一个独立的"可识别单位"。
 *
 * <p>统一数据载体：图片场景下 {@code index=0}，单张图即单页；PDF 场景下
 * {@code index=0..N-1}，每页一条。字节内容经过 {@link InputLoader} 解析后
 * 必为"可直接喂给 OCR 引擎"的形态（图片字节 / 渲染后的位图 / 文本层产物）。
 *
 * <p>扩展元数据通过 {@link #meta} 传递：{@code viaOcr}（是否走 OCR 通道）、
 * {@code sourceDpi}（PDF 渲染 DPI）、{@code pageLabel}（人类可读页码）等。
 * 引擎不解析 meta，结构化解析层按需读取。
 *
 * <h3>不可变性</h3>
 * 字节数组在构造时被复制，{@code meta} 在构造时为只读视图。
 */
@Getter
@ToString
@EqualsAndHashCode
@Accessors(fluent = true)
public final class Page {

	/**
	 * 页序号，从 0 开始。图片场景固定为 0。
	 */
	private final int index;
	/**
	 * 该页的可识别内容（图片字节或渲染后的位图）。
	 */
	private final byte[] bytes;
	/**
	 * 扩展元数据（如 {@code viaOcr}、{@code sourceDpi} 等）；只读视图。
	 */
	private final Map<String, Object> meta;

	/**
	 * 构造页数据。
	 *
	 * @param index 页序号（≥0）
	 * @param bytes 可识别内容
	 * @param meta  扩展元数据（可为 null）
	 */
	public Page(int index, byte[] bytes, Map<String, Object> meta) {
		if (index < 0) {
			throw new IllegalArgumentException("index must be >= 0, got " + index);
		}
		if (bytes == null || bytes.length == 0) {
			throw new IllegalArgumentException("bytes must not be empty");
		}
		this.index = index;
		this.bytes = bytes.clone();
		this.meta = meta == null
			? Collections.emptyMap()
			: Collections.unmodifiableMap(new HashMap<>(meta));
	}

	/**
	 * 构造页数据（无元数据）。
	 *
	 * @param index 页序号
	 * @param bytes 可识别内容
	 */
	public Page(int index, byte[] bytes) {
		this(index, bytes, null);
	}

	/**
	 * 便捷读取：是否走 OCR 通道（仅 PDF 渲染通道下为 true；文本层 / 图片为 false）。
	 *
	 * @return true / false；meta 中无 {@code viaOcr} 时返回 false
	 */
	public boolean viaOcr() {
		Object v = meta.get("viaOcr");
		return v instanceof Boolean && (Boolean) v;
	}
}
