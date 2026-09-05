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

package net.dreamlu.mica.ai.ppocr.pdf;

import net.dreamlu.mica.ai.ppocr.utils.PdfMagicDetector;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PDF 魔数嗅探 + 双通道配置默认值测试。
 */
class PdfMagicDetectorTest {

	@Test
	void detectsStandardMagic() {
		assertTrue(PdfMagicDetector.isPdf("%PDF-1.7\n%âãÏÓ\n".getBytes(StandardCharsets.ISO_8859_1)));
		assertTrue(PdfMagicDetector.isPdf("%PDF-1.4".getBytes(StandardCharsets.ISO_8859_1)));
	}

	@Test
	void detectsMagicAfterJunk() {
		// header 前垃圾数据（1024 字节窗口内）
		byte[] withJunk = new byte[100 + "%PDF-1.7".length()];
		new java.util.Random(42).nextBytes(withJunk);
		byte[] magic = "%PDF-1.7".getBytes(StandardCharsets.ISO_8859_1);
		System.arraycopy(magic, 0, withJunk, 100, magic.length);
		assertTrue(PdfMagicDetector.isPdf(withJunk));
	}

	@Test
	void rejectsNonPdf() {
		assertFalse(PdfMagicDetector.isPdf(null));
		assertFalse(PdfMagicDetector.isPdf(new byte[0]));
		assertFalse(PdfMagicDetector.isPdf(new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A})); // PNG
		assertFalse(PdfMagicDetector.isPdf("PDF-1.7".getBytes(StandardCharsets.ISO_8859_1))); // 缺 %
	}

	@Test
	void defaultConfigMatchesExpected() {
		PdfOcrConfig defaults = PdfOcrConfig.defaults();
		assertTrue(defaults.getRenderDpi() > 0);
		assertTrue(defaults.getMinTextChars() > 0);
		assertTrue(defaults.getMinReadableRatio() > 0.0);
		assertTrue(defaults.getMinReadableRatio() <= 1.0);
		assertFalse(defaults.isForceOcr());
	}
}
