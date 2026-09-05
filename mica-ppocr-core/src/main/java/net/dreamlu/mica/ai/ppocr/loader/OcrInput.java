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
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Objects;

/**
 * OCR 输入抽象：把"用户传进来的东西"统一成带类型标签的载荷，
 * 由 {@link InputLoader} SPI 自行认领（图片 / PDF / 未来扩展的 TIFF / Word）。
 *
 * <p>支持三种来源：
 * <ul>
 *   <li>字节数组：典型场景 Spring Boot {@code MultipartFile.getBytes()}；</li>
 *   <li>文件路径：本地文件或 classpath: 前缀（统一通过 {@link OcrResources} 解析）；</li>
 *   <li>输入流：不持有所有权，由 InputLoader 自行决定是否缓存为字节；</li>
 * </ul>
 *
 * <p>kind 只是"用户声明"或"调用上下文判断"，不强制等于 loader 的实际能力。
 * Loader 在 {@link InputLoader#canLoad(OcrInput)} 中应自行复核（魔数嗅探、文件头等）。
 *
 * <h3>线程安全</h3>
 * 不可变对象，可跨线程共享。
 */
@Getter
@ToString
@Accessors(fluent = true)
public final class OcrInput {

	/**
	 * 输入类型标签：仅作语义提示，Loader 需自行 sniff 真实格式。
	 */
	public enum Kind {
		/**
		 * 单张图片（PNG / JPG / BMP 等 OpenCV 直接支持的格式）。
		 */
		IMAGE,
		/**
		 * PDF 文档（可能单页 / 多页；Loader 需自行决定是否双通道分流）。
		 */
		PDF
	}

	/**
	 * 来源类型。
	 */
	public enum Source {
		/**
		 * 字节数组（用户在外部持有，可能在 Loader 内被复制 / 消费）。
		 */
		BYTES,
		/**
		 * 文件系统路径或 classpath: 前缀路径。
		 */
		PATH,
		/**
		 * 输入流：Loader 需自行决定读取策略。
		 */
		STREAM
	}

	private final Kind kind;
	private final Source source;
	/**
	 * 字节数组（仅 {@link Source#BYTES} 时非空）。
	 */
	private final byte[] bytes;
	/**
	 * 路径字符串（仅 {@link Source#PATH} 时非空；含 classpath: 前缀）。
	 */
	private final String path;
	/**
	 * 输入流（仅 {@link Source#STREAM} 时非空；Loader 需自行关闭）。
	 */
	private final InputStream stream;

	private OcrInput(Kind kind, Source source, byte[] bytes, String path, InputStream stream) {
		this.kind = kind;
		this.source = source;
		this.bytes = bytes;
		this.path = path;
		this.stream = stream;
	}

	/**
	 * 构造图片输入（字节）。
	 *
	 * @param bytes 图片字节
	 * @return OcrInput 实例
	 */
	public static OcrInput image(byte[] bytes) {
		if (bytes == null || bytes.length == 0) {
			throw new IllegalArgumentException("bytes must not be empty");
		}
		return new OcrInput(Kind.IMAGE, Source.BYTES, bytes.clone(), null, null);
	}

	/**
	 * 构造图片输入（路径）。
	 *
	 * @param path 路径字符串（绝对 / 相对 / classpath: 前缀）
	 * @return OcrInput 实例
	 */
	public static OcrInput image(String path) {
		if (path == null || path.isEmpty()) {
			throw new IllegalArgumentException("path must not be empty");
		}
		return new OcrInput(Kind.IMAGE, Source.PATH, null, path, null);
	}

	/**
	 * 构造图片输入（Path）。
	 *
	 * @param path 文件路径
	 * @return OcrInput 实例
	 */
	public static OcrInput image(Path path) {
		Objects.requireNonNull(path, "path must not be null");
		return image(path.toString());
	}

	/**
	 * 构造图片输入（输入流）。
	 *
	 * @param stream 输入流
	 * @return OcrInput 实例
	 */
	public static OcrInput image(InputStream stream) {
		if (stream == null) {
			throw new IllegalArgumentException("stream must not be null");
		}
		return new OcrInput(Kind.IMAGE, Source.STREAM, null, null, stream);
	}

	/**
	 * 构造 PDF 输入（字节）。
	 *
	 * @param bytes PDF 字节
	 * @return OcrInput 实例
	 */
	public static OcrInput pdf(byte[] bytes) {
		if (bytes == null || bytes.length == 0) {
			throw new IllegalArgumentException("bytes must not be empty");
		}
		return new OcrInput(Kind.PDF, Source.BYTES, bytes.clone(), null, null);
	}

	/**
	 * 构造 PDF 输入（路径）。
	 *
	 * @param path 路径字符串
	 * @return OcrInput 实例
	 */
	public static OcrInput pdf(String path) {
		if (path == null || path.isEmpty()) {
			throw new IllegalArgumentException("path must not be empty");
		}
		return new OcrInput(Kind.PDF, Source.PATH, null, path, null);
	}

	/**
	 * 构造 PDF 输入（Path）。
	 *
	 * @param path 文件路径
	 * @return OcrInput 实例
	 */
	public static OcrInput pdf(Path path) {
		Objects.requireNonNull(path, "path must not be null");
		return pdf(path.toString());
	}

	/**
	 * 构造 PDF 输入（输入流）。
	 *
	 * @param stream 输入流
	 * @return OcrInput 实例
	 */
	public static OcrInput pdf(InputStream stream) {
		if (stream == null) {
			throw new IllegalArgumentException("stream must not be null");
		}
		return new OcrInput(Kind.PDF, Source.STREAM, null, null, stream);
	}
}
