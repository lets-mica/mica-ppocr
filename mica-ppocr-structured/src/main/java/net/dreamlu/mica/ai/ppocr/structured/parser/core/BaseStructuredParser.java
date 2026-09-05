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

import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Engine;
import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Result;
import net.dreamlu.mica.ai.ppocr.utils.CollUtil;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;

/**
 * 结构化解析器基类：把 OCR 识别出的散落文字框，组织成业务字段对象。
 *
 * <p>持有 {@link PPOcrV6Engine}，对外暴露两类方法：
 * <ul>
 *   <li>{@link #parseResults(List)} —— 纯字段解析，由子类实现；</li>
 *   <li>{@link #parse(String)} / {@link #parse(File)} / {@link #parse(Path)} /
 *       {@link #parse(byte[])} / {@link #parse(InputStream)} —— "OCR 推理 + 结构化解析"
 *       一站式调用，内部走 {@code engine.run(...)} 后转 {@link #parseResults(List)}。</li>
 * </ul>
 *
 * <p>5 个 {@code parse(...)} 重载已实现为 {@code final}，避免子类误覆盖而绕过 engine 调用。
 *
 * <p>典型实现：
 * <pre>
 * public final class IdCardParser extends BaseStructuredParser&lt;IdCardResult&gt; {
 *     public IdCardParser(PPOcrV6Engine engine) {
 *         super(engine);
 *     }
 *
 *     &#64;Override
 *     public IdCardResult parseResults(List&lt;PPOcrV6Result&gt; results) {
 *         IdCardResult r = new IdCardResult();
 *         r.setName(LabelMatcher.matchValue(results, "姓名"));
 *         return r;
 *     }
 * }
 * </pre>
 *
 * <p>Spring / Solon 场景下由容器注入 engine；非容器场景可通过 {@code PPOcrTemplate}
 * 的 {@code vehicleLicense()} / {@code idCard()} 等方法获取已绑定 engine 的实例。
 *
 * @param <R> 业务结果类型
 */
public abstract class BaseStructuredParser<R> {

	/**
	 * PP-OCRv6 推理引擎，用于 {@code parse(...)} 系列一站式方法中的 OCR 推理。
	 *
	 * <p>可为空：仅在 {@link #parseResults(List)} 单独使用场景（如单元测试 mock results）下可为 null；
	 * 调用 {@code parse(Path/byte[]/InputStream/...)} 一站式方法时必须非空（方法内部断言）。
	 */
	protected final PPOcrV6Engine engine;

	/**
	 * 构造解析器，注入推理引擎。
	 *
	 * @param engine PP-OCRv6 推理引擎；可为 null（仅当不调用 {@code parse(...)} 一站式方法时）
	 */
	protected BaseStructuredParser(PPOcrV6Engine engine) {
		this.engine = engine;
	}

	/**
	 * 从 OCR 结果中解析出业务字段对象。
	 *
	 * @param results OCR 结果列表
	 * @return 结构化结果；解析失败或输入为空时返回的字段值允许为 null
	 */
	public abstract R parseResults(List<PPOcrV6Result> results);

	// ==================================================================
	// 一站式："OCR 推理 + 结构化解析"
	// 全部为 final，避免子类绕过 engine 注入。
	// ==================================================================

	/**
	 * 一站式结构化解析：检测 → 排序 → 裁剪 → 识别 → 解析。
	 *
	 * @param imagePath 图片文件路径（PNG / JPG / BMP 等任意 OpenCV 支持的格式）
	 * @return 结构化结果
	 * @throws IllegalArgumentException 图片路径为空
	 */
	public final R parse(String imagePath) {
		if (imagePath == null || imagePath.isEmpty()) {
			throw new IllegalArgumentException("imagePath must not be empty");
		}
		return parse(CollUtil.pathOf(imagePath));
	}

	/**
	 * 一站式结构化解析：检测 → 排序 → 裁剪 → 识别 → 解析。
	 *
	 * @param imageFile 图片文件
	 * @return 结构化结果
	 * @throws IllegalArgumentException 文件为 null
	 */
	public final R parse(File imageFile) {
		if (imageFile == null) {
			throw new IllegalArgumentException("imageFile must not be null");
		}
		return parse(imageFile.toPath());
	}

	/**
	 * 一站式结构化解析：检测 → 排序 → 裁剪 → 识别 → 解析。
	 *
	 * <p>兼容非默认文件系统（如 ZIP / JIMFS / 内存 FS）：优先走 native 文件读取，
	 * 不支持的 FileSystem 自动退回 {@code Files.readAllBytes}。
	 *
	 * <p>若文件为 PDF（{@code %PDF-} 魔数），自动按 PDF 双通道处理并平铺所有页结果。
	 *
	 * @param imagePath 图片或 PDF 路径
	 * @return 结构化结果
	 * @throws UncheckedIOException 读取字节时发生 IO 异常
	 */
	public final R parse(Path imagePath) {
		return parseResults(engine.run(imagePath));
	}

	/**
	 * 一站式结构化解析：检测 → 排序 → 裁剪 → 识别 → 解析。
	 *
	 * <p>典型场景：Spring Boot 上传 {@code MultipartFile.getBytes()}。
	 *
	 * <p>若字节流为 PDF（{@code %PDF-} 魔数），自动按 PDF 双通道处理并平铺所有页结果。
	 *
	 * <p>PDF 解析失败时由 engine 内部包为 {@link UncheckedIOException} 抛出，
	 * 调用方无需强制 try-catch。
	 *
	 * @param imgBytes 图片或 PDF 字节
	 * @return 结构化结果
	 */
	public final R parse(byte[] imgBytes) {
		return parseResults(engine.run(imgBytes));
	}

	/**
	 * 一站式结构化解析：检测 → 排序 → 裁剪 → 识别 → 解析。
	 *
	 * <p>内部读取全部流为 byte[] 后调用 {@code engine.run(byte[])}。
	 * 流由调用方负责关闭（{@code CollUtil.readAllBytes(InputStream)} 会读到 EOF 但不 close）。
	 *
	 * @param in 图片或 PDF 输入流
	 * @return 结构化结果
	 * @throws IOException 读取流失败
	 */
	public final R parse(InputStream in) throws IOException {
		if (in == null) {
			throw new IllegalArgumentException("InputStream must not be null");
		}
		return parseResults(engine.run(CollUtil.readAllBytes(in)));
	}
}
