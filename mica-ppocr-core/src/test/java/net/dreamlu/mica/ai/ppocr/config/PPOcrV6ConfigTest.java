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

package net.dreamlu.mica.ai.ppocr.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PPOcrV6Config 单元测试。
 */
class PPOcrV6ConfigTest {

	@Test
	void defaults_checkValues() {
		PPOcrV6Config config = PPOcrV6Config.defaults();
		assertEquals(64, config.getDetLimitSideLen());
		assertEquals("min", config.getDetLimitType());
		assertEquals(1920, config.getDetMaxSideLimit());
		assertEquals(0.3f, config.getDetThresh());
		assertEquals(0.6f, config.getDetBoxThresh());
		assertEquals(1.5f, config.getDetUnclipRatio());
		assertArrayEquals(new int[]{3, 48, 320}, config.getRecImageShape());
		assertEquals(6, config.getRecBatchSize());
		assertFalse(config.isPreferAccelerator());
		assertEquals(1, config.getIntraOpNumThreads());
		assertEquals(1, config.getInterOpNumThreads());
		assertNull(config.getDetModelPath());
		assertNull(config.getRecModelPath());
		assertNull(config.getRecCharDictPath());
	}

	@Test
	void builder_customValues() {
		PPOcrV6Config config = PPOcrV6Config.builder()
			.detModelPath("det.onnx")
			.recModelPath("rec.onnx")
			.recCharDictPath("dict.txt")
			.detLimitSideLen(96)
			.preferAccelerator(true)
			.intraOpNumThreads(4)
			.build();
		assertEquals("det.onnx", config.getDetModelPath());
		assertEquals("rec.onnx", config.getRecModelPath());
		assertEquals("dict.txt", config.getRecCharDictPath());
		assertEquals(96, config.getDetLimitSideLen());
		assertTrue(config.isPreferAccelerator());
		assertEquals(4, config.getIntraOpNumThreads());
	}
}
