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

import lombok.experimental.UtilityClass;
import net.dreamlu.mica.ai.ppocr.utils.CollUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 资源解析：把 {@link OcrInput} 的多种来源（字节 / 路径 / 流）统一为字节数组。
 *
 * <p>与 {@link net.dreamlu.mica.ai.ppocr.utils.ModelResourceLoader} 共享
 * {@code classpath:} 前缀语义，区别：本类只关心 "读成字节"，不做路径合法性校验；
 * 上层 {@link InputLoader} 在 {@link InputLoader#canLoad(OcrInput)} 阶段自行 sniff。
 *
 * <h3>classpath: 前缀</h3>
 * 资源解析走根 ClassLoader，与 ModelResourceLoader 保持一致（Spring Boot Fat Jar 下
 * LaunchedURLClassLoader 能正常访问 BOOT-INF/classes/ 与 BOOT-INF/lib/*.jar）。
 *
 * <p>无状态工具类，所有方法线程安全。
 */
@UtilityClass
public class OcrResources {

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
	 * 把 {@link OcrInput} 解析为字节数组。
	 *
	 * <p>输入流路径：调用方需自行管理流的关闭；本方法只读取，不持有。
	 * 字节路径：返回内部副本，调用方修改不影响 OcrInput。
	 *
	 * @param input 输入
	 * @return 字节内容
	 * @throws IOException           读取失败
	 * @throws IllegalArgumentException 路径为空 / 资源不存在
	 */
	public static byte[] toBytes(OcrInput input) throws IOException {
		if (input == null) {
			throw new IllegalArgumentException("input must not be null");
		}
		switch (input.source()) {
			case BYTES:
				return input.bytes().clone();
			case PATH:
				return readPath(input.path());
			case STREAM:
				try (InputStream in = input.stream()) {
					return CollUtil.readAllBytes(in);
				}
			default:
				throw new IllegalStateException("unknown source: " + input.source());
		}
	}

	private static byte[] readPath(String path) {
		if (isClasspath(path)) {
			return readClasspath(path);
		}
		return readFileSystem(path);
	}

	private static byte[] readClasspath(String path) {
		String resourcePath = path.substring(CLASSPATH_PREFIX.length());
		if (resourcePath.startsWith("/")) {
			resourcePath = resourcePath.substring(1);
		}
		ClassLoader cl = OcrResources.class.getClassLoader();
		try (InputStream in = cl.getResourceAsStream(resourcePath)) {
			if (in == null) {
				throw new IllegalArgumentException("classpath resource not found: " + path);
			}
			return CollUtil.readAllBytes(in);
		} catch (IOException e) {
			throw new RuntimeException("failed to read classpath resource: " + path, e);
		}
	}

	private static byte[] readFileSystem(String path) {
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
