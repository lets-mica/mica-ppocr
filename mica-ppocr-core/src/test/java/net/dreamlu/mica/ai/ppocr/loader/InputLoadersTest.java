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

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link InputLoaders} + {@link ImageInputLoader} 单元测试。
 */
class InputLoadersTest {

	@Test
	void loaders_areDiscovered() {
		List<InputLoader> loaders = InputLoaders.loaders();
		assertFalse(loaders.isEmpty(), "at least ImageInputLoader should be discovered via SPI");
		// ImageInputLoader 必定在内
		boolean hasImage = loaders.stream().anyMatch(l -> l instanceof ImageInputLoader);
		assertTrue(hasImage, "ImageInputLoader should be registered in META-INF/services");
	}

	@Test
	void imageInputLoader_handlesImageBytes() {
		ImageInputLoader loader = new ImageInputLoader();
		// 1x1 PNG（最小有效图片字节）
		byte[] png = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00};
		assertTrue(loader.canLoad(OcrInput.image(png)));
	}

	@Test
	void imageInputLoader_rejectsPdfBytes() {
		ImageInputLoader loader = new ImageInputLoader();
		byte[] pdfBytes = "%PDF-1.7\n%âãÏÓ\n".getBytes(StandardCharsets.ISO_8859_1);
		assertFalse(loader.canLoad(OcrInput.image(pdfBytes)),
			"ImageInputLoader should reject PDF magic even if user labels it IMAGE");
	}

	@Test
	void find_returnsImageLoaderForImageInput() {
		// 1x1 PNG bytes
		byte[] png = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00};
		OcrInput imageInput = OcrInput.image(png);
		InputLoader loader = InputLoaders.find(imageInput);
		assertNotNull(loader);
		assertTrue(loader instanceof ImageInputLoader);
	}

	@Test
	void ocrInput_pdfInput_rejectedByImageInputLoader() {
		// kind=PDF + 字节是 PDF magic：ImageInputLoader 应拒绝
		byte[] pdfBytes = "%PDF-1.7\n".getBytes(StandardCharsets.ISO_8859_1);
		OcrInput pdfInput = OcrInput.pdf(pdfBytes);
		InputLoader loader = InputLoaders.find(pdfInput);
		// 当前 core 单独测试时 MicaPdf 不会被 ServiceLoader 命中，所以应该返回 null
		// （找不到 loader 是预期行为；真实使用场景下 mica-ppocr-pdf 在 classpath）
		assertSame(null, loader, "without mica-ppocr-pdf on classpath, PDF input has no loader");
	}

	@Test
	void priority_ordering() {
		List<InputLoader> loaders = InputLoaders.loaders();
		for (int i = 1; i < loaders.size(); i++) {
			int prev = loaders.get(i - 1).priority();
			int curr = loaders.get(i).priority();
			assertTrue(prev <= curr,
				"loaders should be sorted by priority asc, but got " + prev + " before " + curr);
		}
	}
}
