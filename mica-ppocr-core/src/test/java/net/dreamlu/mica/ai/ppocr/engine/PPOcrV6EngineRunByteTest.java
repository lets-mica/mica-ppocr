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

import net.dreamlu.mica.ai.ppocr.config.PPOcrV6Config;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link PPOcrV6Engine#run(byte[])} 行为契约测试（无需模型，纯路径断言）。
 *
 * <p>校验：
 * <ul>
 *     <li>空字节抛 {@link IllegalArgumentException}；</li>
 *     <li>PDF 解析失败时抛 {@link UncheckedIOException}（unchecked，调用方免 try-catch）；</li>
 *     <li>API 签名本身不声明 {@code throws IOException}。</li>
 * </ul>
 */
class PPOcrV6EngineRunByteTest {

	@Test
	void runByteArraySignatureDoesNotDeclareIOException() throws NoSuchMethodException {
		Method m = PPOcrV6Engine.class.getMethod("run", byte[].class);
		for (Class<?> ex : m.getExceptionTypes()) {
			assertEquals(false, java.io.IOException.class.isAssignableFrom(ex),
				"run(byte[]) 不应声明 throws IOException，但找到: " + ex.getName());
		}
	}

	@Test
	void rejectsEmptyBytes() {
		PPOcrV6Engine engine = newEngineOrSkip();
		try {
			assertThrows(IllegalArgumentException.class, () -> engine.run(new byte[0]));
		} finally {
			engine.close();
		}
	}

	@Test
	void pdfParseFailureThrowsUncheckedIOException() {
		PPOcrV6Engine engine = newEngineOrSkip();
		try {
			// 合法魔数 + 完全无效的 PDF 体：嗅探通过后 PDFBox 解析失败，
			// engine 必须把 checked IOException 包为 UncheckedIOException 抛出。
			byte[] brokenPdf = "%PDF-1.7\nthis-is-not-a-valid-pdf-body".getBytes(StandardCharsets.ISO_8859_1);
			assertThrows(UncheckedIOException.class, () -> engine.run(brokenPdf));
		} finally {
			engine.close();
		}
	}

	private static PPOcrV6Engine newEngineOrSkip() {
		String multiModuleDir = System.getProperty("maven.multiModuleProjectDirectory");
		Path modelDir = null;
		if (multiModuleDir != null) {
			modelDir = java.nio.file.Paths.get(multiModuleDir, "models", "ppocr-v6", "tiny");
		}
		if (modelDir == null || !Files.isRegularFile(modelDir.resolve("det.onnx"))) {
			Assumptions.assumeTrue(false, "tiny 模型缺失，跳过契约测试");
			throw new IllegalStateException("unreachable");
		}
		PPOcrV6Config config = PPOcrV6Config.builder()
			.detModelPath(modelDir.resolve("det.onnx").toString())
			.recModelPath(modelDir.resolve("rec.onnx").toString())
			.recCharDictPath(modelDir.resolve("dict.txt").toString())
			.build();
		return new PPOcrV6Engine(config);
	}
}
