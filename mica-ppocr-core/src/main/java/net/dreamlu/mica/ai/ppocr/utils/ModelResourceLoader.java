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

package net.dreamlu.mica.ai.ppocr.utils;

import lombok.experimental.UtilityClass;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 模型 / 字典资源加载器。
 *
 * <p>统一识别 {@code classpath:} 前缀，把 jar 内资源透明转换为 {@code byte[]}：
 * <ul>
 *   <li>{@code classpath:models/ppocr-v6/tiny/det.onnx} → 从根 ClassLoader 读取</li>
 *   <li>{@code /abs/path/det.onnx} 或 {@code ./det.onnx} → 走 {@link Files#readAllBytes(Path)}</li>
 * </ul>
 *
 * <p>兼容 Spring Boot Fat Jar：根 ClassLoader（即 {@link Class#getClassLoader()}）能正确
 * 看到 {@code BOOT-INF/classes/} 和 {@code BOOT-INF/lib/*.jar} 内的资源，
 * 第三方 nested jar 协议由 Spring 的 {@code LaunchedURLClassLoader} 内部处理。
 *
 * <p>注意：medium 档模型约 130MB，classpath 加载会短暂占用 JVM 堆；建议该档仍走文件系统路径。
 */
@UtilityClass
public class ModelResourceLoader {

	/**
	 * classpath 前缀。
	 */
	public static final String CLASSPATH_PREFIX = "classpath:";

	/**
	 * 是否为 classpath 路径。
	 *
	 * @param path 路径字符串
	 * @return true 如果以 {@code classpath:} 开头
	 */
	public static boolean isClasspath(String path) {
		return path != null && path.startsWith(CLASSPATH_PREFIX);
	}

	/**
	 * 加载资源为字节数组。
	 *
	 * <p>classpath 路径走根 ClassLoader 的 {@link ClassLoader#getResourceAsStream(String)}；
	 * 其他路径走 {@link Files#readAllBytes(Path)}。行为对调用方完全透明。
	 *
	 * @param path 资源路径（classpath: 前缀或文件系统路径）
	 * @return 资源字节内容
	 * @throws IllegalArgumentException 路径为空或资源不存在
	 * @throws RuntimeException         读取失败
	 */
	public static byte[] load(String path) {
		if (path == null || path.isEmpty()) {
			throw new IllegalArgumentException("model path must not be empty");
		}
		if (isClasspath(path)) {
			return loadFromClasspath(path);
		} else {
			return loadFromFileSystem(path);
		}
	}

	/**
	 * 把「文件系统路径」形式的资源路径解析为<b>绝对路径</b>。
	 *
	 * <p>供 ONNX Runtime 的<b>路径版</b>会话 API
	 * （{@code OrtEnvironment#createSession(String, SessionOptions)}）使用：
	 * 模型字节由 ORT 自己从文件读进 native，<b>Java 堆上不再出现与模型等大的 byte[]</b>
	 * （medium 档 rec 模型约 73 MiB，此前是启动期最大的一块瞬时堆）。
	 *
	 * <p>与 {@link #load(String)} 的分工：
	 * <ul>
	 *   <li>{@link #load(String)} 返回字节 —— jar 内资源（{@code classpath:} 前缀）只能用这条；</li>
	 *   <li>{@link #resolveFilePath(String)} 只处理真实文件系统路径，jar 内资源直接抛异常。</li>
	 * </ul>
	 *
	 * @param path 资源路径（非 {@code classpath:} 前缀）
	 * @return 绝对路径
	 * @throws IllegalArgumentException 路径为空、是 classpath 资源或文件不存在
	 */
	public static Path resolveFilePath(String path) {
		if (path == null || path.isEmpty()) {
			throw new IllegalArgumentException("model path must not be empty");
		}
		if (isClasspath(path)) {
			throw new IllegalArgumentException("classpath resource has no filesystem path: " + path);
		}
		Path p = CollUtil.pathOf(path);
		if (!Files.isRegularFile(p)) {
			throw new IllegalArgumentException("file not found: " + path);
		}
		return p.toAbsolutePath();
	}

	/**
	 * 从 classpath 读取资源为字节数组。
	 *
	 * <p>用 ModelResourceLoader 自身的 ClassLoader（根加载器）解析资源，
	 * 在 Spring Boot Fat Jar 的 {@code LaunchedURLClassLoader} 下能正常访问
	 * {@code BOOT-INF/classes/} 与 {@code BOOT-INF/lib/*.jar} 内的资源。
	 *
	 * @param path 带 {@code classpath:} 前缀的资源路径
	 * @return 资源字节内容
	 * @throws IllegalArgumentException 资源不存在
	 * @throws RuntimeException         读取失败
	 */
	private static byte[] loadFromClasspath(String path) {
		String resourcePath = path.substring(CLASSPATH_PREFIX.length());
		if (resourcePath.startsWith("/")) {
			resourcePath = resourcePath.substring(1);
		}
		ClassLoader cl = ModelResourceLoader.class.getClassLoader();
		try (InputStream in = cl.getResourceAsStream(resourcePath)) {
			if (in == null) {
				throw new IllegalArgumentException("classpath resource not found: " + path);
			}
			return CollUtil.readAllBytes(in);
		} catch (IOException e) {
			throw new RuntimeException("failed to read classpath resource: " + path, e);
		}
	}

	/**
	 * 从文件系统读取文件为字节数组。
	 *
	 * @param path 文件系统绝对路径或相对路径
	 * @return 文件字节内容
	 * @throws IllegalArgumentException 文件不存在或不是常规文件
	 * @throws RuntimeException         读取失败
	 */
	private static byte[] loadFromFileSystem(String path) {
		Path p = CollUtil.pathOf(path);
		if (!Files.isRegularFile(p)) {
			throw new IllegalArgumentException("file not found: " + path);
		}
		try {
			return Files.readAllBytes(p);
		} catch (IOException e) {
			throw new RuntimeException("failed to read file: " + path, e);
		}
	}
}
