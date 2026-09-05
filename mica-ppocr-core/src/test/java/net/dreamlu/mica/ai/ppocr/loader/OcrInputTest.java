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

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link OcrInput} 单元测试。
 */
class OcrInputTest {

	@Test
	void imageBytes_constructsAndDefensivelyCopies() {
		byte[] src = {1, 2, 3};
		OcrInput input = OcrInput.image(src);
		assertEquals(OcrInput.Kind.IMAGE, input.kind());
		assertEquals(OcrInput.Source.BYTES, input.source());
		// 修改原数组不影响 input
		src[0] = 99;
		assertEquals(1, input.bytes()[0]);
	}

	@Test
	void imageBytes_emptyRejected() {
		assertThrows(IllegalArgumentException.class, () -> OcrInput.image((byte[]) null));
		assertThrows(IllegalArgumentException.class, () -> OcrInput.image(new byte[0]));
	}

	@Test
	void imagePath_rejectsEmpty() {
		assertThrows(IllegalArgumentException.class, () -> OcrInput.image((String) null));
		assertThrows(IllegalArgumentException.class, () -> OcrInput.image(""));
	}

	@Test
	void imagePath_acceptsPathObject() {
		OcrInput input = OcrInput.image(Paths.get("a/b/c.png"));
		assertEquals(OcrInput.Source.PATH, input.source());
		// 平台无关：断言 Path 转 String 后等于 Path.toString()，
		// 避免硬编码 Windows 的 "\\" 或 Linux 的 "/"
		assertEquals(Paths.get("a/b/c.png").toString(), input.path());
	}

	@Test
	void imageStream_rejectsNull() {
		assertThrows(IllegalArgumentException.class, () -> OcrInput.image((java.io.InputStream) null));
	}

	@Test
	void ocrResources_toBytesRoundTripsAllSources() throws IOException {
		byte[] original = {1, 2, 3, 4};
		// BYTES
		assertArrayEquals(original, OcrResources.toBytes(OcrInput.image(original)));
		// 修改原数组不应影响已构造 input（defensive copy）
		OcrInput bytesInput = OcrInput.image(original);
		original[0] = 99;
		byte[] resolved = OcrResources.toBytes(bytesInput);
		assertArrayEquals(new byte[]{1, 2, 3, 4}, resolved);
		// STREAM
		OcrInput streamInput = OcrInput.image(new ByteArrayInputStream(new byte[]{5, 6, 7, 8}));
		assertArrayEquals(new byte[]{5, 6, 7, 8}, OcrResources.toBytes(streamInput));
	}

	@Test
	void ocrResources_classpathPrefixDetected() {
		assertEquals(true, OcrResources.isClasspath("classpath:foo/bar.txt"));
		assertEquals(false, OcrResources.isClasspath("/abs/foo.txt"));
		assertEquals(false, OcrResources.isClasspath(null));
	}

	@Test
	void page_defensivelyCopiesBytes() {
		byte[] src = {1, 2, 3};
		Page page = new Page(0, src);
		assertNotSame(src, page.bytes());
		assertEquals(1, page.bytes()[0]);
	}

	@Test
	void page_rejectsEmptyBytes() {
		assertThrows(IllegalArgumentException.class, () -> new Page(0, new byte[0]));
		assertThrows(IllegalArgumentException.class, () -> new Page(0, (byte[]) null));
	}

	@Test
	void page_rejectsNegativeIndex() {
		assertThrows(IllegalArgumentException.class, () -> new Page(-1, new byte[]{1}));
	}

	@Test
	void page_metaIsReadOnly() {
		java.util.Map<String, Object> meta = new java.util.HashMap<>();
		meta.put("viaOcr", true);
		Page page = new Page(0, new byte[]{1}, meta);
		assertEquals(true, page.viaOcr());
		// meta 应为只读视图
		assertThrows(UnsupportedOperationException.class,
			() -> page.meta().put("k", "v"));
	}
}
