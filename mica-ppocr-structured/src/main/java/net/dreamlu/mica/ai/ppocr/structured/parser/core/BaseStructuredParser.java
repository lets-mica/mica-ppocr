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
import net.dreamlu.mica.ai.ppocr.postprocessor.DbDetParams;
import net.dreamlu.mica.ai.ppocr.utils.CollUtil;

import java.io.File;
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
 *       一站式调用，内部走 {@code engine.run(...)} 后转 {@link #parseResults(List)}；
 *       每个 {@code parse} 还配套一个 {@code (..., DbDetParams)} 重载，
 *       用于按调用临时覆盖 DB 后处理参数（见 {@link DbDetParams}）。</li>
 * </ul>
 *
 * <p>10 个 {@code parse(...)} 重载已实现为 {@code final}，避免子类误覆盖而绕过 engine 调用。
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

	/**
	 * 本解析器声明的默认 DB 检测参数（钩子）。
	 *
	 * <p>返回 {@code null}（默认实现）表示沿用引擎构造期的 DB 参数，
	 * 此时 {@code parse(...)} 行为与本钩子引入前完全一致。
	 *
	 * <p>子类可覆盖此钩子，为自己的版面类型声明专属的检测参数。
	 * 典型场景：<b>海报 / 分享图</b>类版面里存在「稀疏大字号数字 + 背景水印」，
	 * DB 会把数字与水印连成一个整体轮廓，导致框内文字像素占比偏低、
	 * 被 {@code detBoxThresh} 过滤掉（详见 {@code PddLuckyBagParser}）。
	 *
	 * <p>该钩子仅影响"一站式 {@code parse(...)}"内部的 OCR 推理参数，
	 * 不修改引擎共享状态，多线程调用安全。
	 *
	 * @return 默认 DB 参数；{@code null} 表示使用引擎默认
	 */
	protected DbDetParams detParams() {
		return null;
	}

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
		return parse(imagePath, detParams());
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
		return parse(imgBytes, detParams());
	}

	/**
	 * 一站式结构化解析：检测 → 排序 → 裁剪 → 识别 → 解析。
	 *
	 * <p>内部读取全部流为 byte[] 后调用 {@code engine.run(byte[])}。
	 * 流由调用方负责关闭（{@code CollUtil.readAllBytes(InputStream)} 会读到 EOF 但不 close）。
	 *
	 * <p>流读取失败时包为 {@link UncheckedIOException} 抛出，调用方免 try-catch。
	 *
	 * @param in 图片或 PDF 输入流
	 * @return 结构化结果
	 */
	public final R parse(InputStream in) {
		if (in == null) {
			throw new IllegalArgumentException("InputStream must not be null");
		}
		return parse(in, detParams());
	}

	/**
	 * 一站式结构化解析（按调用覆盖 DB 参数）：检测 → 排序 → 裁剪 → 识别 → 解析。
	 *
	 * <p>{@code detParams} 为 {@code null} 时使用引擎默认参数；非 null 时本次调用临时构造后处理，
	 * 不修改引擎共享状态，线程安全。详见 {@link PPOcrV6Engine#run(String, DbDetParams)}。
	 *
	 * @param imagePath 图片文件路径（PNG / JPG / BMP 等任意 OpenCV 支持的格式）
	 * @param detParams DB 参数；{@code null} 表示使用引擎默认
	 * @return 结构化结果
	 * @throws IllegalArgumentException 图片路径为空
	 */
	public final R parse(String imagePath, DbDetParams detParams) {
		if (imagePath == null || imagePath.isEmpty()) {
			throw new IllegalArgumentException("imagePath must not be empty");
		}
		return parse(CollUtil.pathOf(imagePath), detParams);
	}

	/**
	 * 一站式结构化解析（按调用覆盖 DB 参数）：检测 → 排序 → 裁剪 → 识别 → 解析。
	 *
	 * @param imageFile 图片文件
	 * @param detParams DB 参数；{@code null} 表示使用引擎默认
	 * @return 结构化结果
	 * @throws IllegalArgumentException 文件为 null
	 */
	public final R parse(File imageFile, DbDetParams detParams) {
		if (imageFile == null) {
			throw new IllegalArgumentException("imageFile must not be null");
		}
		return parse(imageFile.toPath(), detParams);
	}

	/**
	 * 一站式结构化解析（按调用覆盖 DB 参数）：检测 → 排序 → 裁剪 → 识别 → 解析。
	 *
	 * <p>兼容非默认文件系统（如 ZIP / JIMFS / 内存 FS）：优先走 native 文件读取，
	 * 不支持的 FileSystem 自动退回 {@code Files.readAllBytes}。
	 *
	 * <p>若文件为 PDF（{@code %PDF-} 魔数），自动按 PDF 双通道处理并平铺所有页结果。
	 *
	 * @param imagePath 图片或 PDF 路径
	 * @param detParams DB 参数；{@code null} 表示使用引擎默认
	 * @return 结构化结果
	 * @throws UncheckedIOException 读取字节时发生 IO 异常
	 */
	public final R parse(Path imagePath, DbDetParams detParams) {
		return parseResults(engine.run(imagePath, effectiveDetParams(detParams)));
	}

	/**
	 * 一站式结构化解析（按调用覆盖 DB 参数）：检测 → 排序 → 裁剪 → 识别 → 解析。
	 *
	 * <p>典型场景：Spring Boot 上传 {@code MultipartFile.getBytes()}。
	 *
	 * <p>若字节流为 PDF（{@code %PDF-} 魔数），自动按 PDF 双通道处理并平铺所有页结果。
	 *
	 * <p>PDF 解析失败时由 engine 内部包为 {@link UncheckedIOException} 抛出，
	 * 调用方无需强制 try-catch。
	 *
	 * @param imgBytes  图片或 PDF 字节
	 * @param detParams DB 参数；{@code null} 表示使用引擎默认
	 * @return 结构化结果
	 */
	public final R parse(byte[] imgBytes, DbDetParams detParams) {
		return parseResults(engine.run(imgBytes, effectiveDetParams(detParams)));
	}

	/**
	 * 一站式结构化解析（按调用覆盖 DB 参数）：检测 → 排序 → 裁剪 → 识别 → 解析。
	 *
	 * <p>内部读取全部流为 byte[] 后调用 {@code engine.run(byte[], DbDetParams)}。
	 * 流由调用方负责关闭（{@code CollUtil.readAllBytes(InputStream)} 会读到 EOF 但不 close）。
	 *
	 * <p>流读取失败时包为 {@link UncheckedIOException} 抛出，调用方免 try-catch。
	 *
	 * @param in        图片或 PDF 输入流
	 * @param detParams DB 参数；{@code null} 表示使用引擎默认
	 * @return 结构化结果
	 */
	public final R parse(InputStream in, DbDetParams detParams) {
		if (in == null) {
			throw new IllegalArgumentException("InputStream must not be null");
		}
		return parseResults(engine.run(in, effectiveDetParams(detParams)));
	}

	/**
	 * 解析本次调用实际生效的 DB 参数。
	 *
	 * <p>显式传入的参数优先；传入 {@code null} 时回落到 {@link #detParams()} 钩子，
	 * 钩子未覆盖时即为 {@code null}（使用引擎构造期的默认 DB 参数）。
	 *
	 * @param override 调用方显式传入的 DB 参数，可为 null
	 * @return 本次调用生效的 DB 参数，可能为 null
	 */
	private DbDetParams effectiveDetParams(DbDetParams override) {
		return override != null ? override : detParams();
	}
}
