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

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ModelResourceLoader 单元测试。
 */
class ModelResourceLoaderTest {

	@Test
	void isClasspath_recognizesPrefix() {
		assertTrue(ModelResourceLoader.isClasspath("classpath:foo/bar.onnx"));
		assertTrue(ModelResourceLoader.isClasspath("classpath:/foo/bar.onnx"));
		assertFalse(ModelResourceLoader.isClasspath("/abs/foo/bar.onnx"));
		assertFalse(ModelResourceLoader.isClasspath("./foo/bar.onnx"));
		assertFalse(ModelResourceLoader.isClasspath(null));
		assertFalse(ModelResourceLoader.isClasspath(""));
	}

	@Test
	void resolveFilePath_existingFile_returnsAbsolutePath() throws IOException {
		Path temp = Files.createTempFile("model-resolver", ".onnx");
		try {
			Path resolved = ModelResourceLoader.resolveFilePath(temp.toString());
			assertTrue(resolved.isAbsolute(), "返回的路径必须是绝对路径");
			assertTrue(Files.isRegularFile(resolved));
		} finally {
			Files.deleteIfExists(temp);
		}
	}

	@Test
	void resolveFilePath_notFound_throwsWithFileNotFoundMessage() {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
			() -> ModelResourceLoader.resolveFilePath("/non/existent/path/model.onnx"));
		assertTrue(ex.getMessage().contains("file not found"),
			"报错消息应保持与 load() 一致的口径，实际：" + ex.getMessage());
	}

	@Test
	void resolveFilePath_classpathResource_throws() {
		// classpath 资源在 jar 内，没有文件系统路径 —— 不能交给 ORT 的路径版 API
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
			() -> ModelResourceLoader.resolveFilePath("classpath:models/det.onnx"));
		assertTrue(ex.getMessage().contains("classpath"),
			"报错消息应指明是 classpath 资源，实际：" + ex.getMessage());
	}

	@Test
	void resolveFilePath_nullOrEmpty_throws() {
		assertThrows(IllegalArgumentException.class, () -> ModelResourceLoader.resolveFilePath(null));
		assertThrows(IllegalArgumentException.class, () -> ModelResourceLoader.resolveFilePath(""));
	}

	@Test
	void load_classpathResource_returnsBytes() {
		// resources/test-utils/loader-test.txt 在 classpath 中准备
		byte[] bytes = ModelResourceLoader.load("classpath:test-utils/loader-test.txt");
		assertNotNull(bytes);
		assertTrue(bytes.length > 0, "classpath resource should have content");
		assertEquals("hello", new String(bytes));
	}

	@Test
	void load_classpathResourceWithLeadingSlash_normalizes() {
		byte[] bytes = ModelResourceLoader.load("classpath:/test-utils/loader-test.txt");
		assertNotNull(bytes);
		assertEquals("hello", new String(bytes));
	}

	@Test
	void load_classpathResourceNotFound_throws() {
		assertThrows(IllegalArgumentException.class,
			() -> ModelResourceLoader.load("classpath:non-existent-resource.bin"));
	}

	@Test
	void load_fileSystemPath_returnsBytes() throws IOException {
		Path temp = Files.createTempFile("model-res-", ".bin");
		try {
			byte[] content = {0x01, 0x02, 0x03, 0x04};
			Files.write(temp, content, StandardOpenOption.WRITE);
			byte[] loaded = ModelResourceLoader.load(temp.toString());
			assertArrayEquals(content, loaded);
		} finally {
			Files.deleteIfExists(temp);
		}
	}

	@Test
	void load_fileSystemNotFound_throws() {
		assertThrows(IllegalArgumentException.class,
			() -> ModelResourceLoader.load("/non/existent/path/file.bin"));
	}

	@Test
	void load_nullOrEmpty_throws() {
		assertThrows(IllegalArgumentException.class, () -> ModelResourceLoader.load(null));
		assertThrows(IllegalArgumentException.class, () -> ModelResourceLoader.load(""));
	}
}