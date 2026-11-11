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

package net.dreamlu.mica.ai.ppocr.solon;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link PPOCRProperties} 单元测试：验证默认值与 getter/setter。
 */
class PPOCRPropertiesTest {

	@Test
	void shouldHaveDefaultValues() {
		PPOCRProperties props = new PPOCRProperties();
		assertTrue(props.isEnabled());
		assertEquals(64, props.getDetLimitSideLen());
		assertEquals("min", props.getDetLimitType());
		assertEquals(1920, props.getDetMaxSideLimit());
		assertEquals(0.3f, props.getDetThresh());
		assertEquals(0.6f, props.getDetBoxThresh());
		assertEquals(1.5f, props.getDetUnclipRatio());
		assertArrayEquals(new int[]{3, 48, 320}, props.getRecImageShape());
		assertEquals(6, props.getRecBatchSize());
		assertFalse(props.isPreferAccelerator());
		assertEquals(1, props.getIntraOpNumThreads());
		assertEquals(1, props.getInterOpNumThreads());
		assertFalse(props.isEnableCpuMemArena());
		assertFalse(props.isEnableMemoryPattern());
	}

	@Test
	void shouldSetAndGetRequiredPaths() {
		PPOCRProperties props = new PPOCRProperties();
		props.setDetModelPath("models/det.onnx");
		props.setRecModelPath("models/rec.onnx");
		props.setRecCharDictPath("models/dict.txt");
		assertEquals("models/det.onnx", props.getDetModelPath());
		assertEquals("models/rec.onnx", props.getRecModelPath());
		assertEquals("models/dict.txt", props.getRecCharDictPath());
	}

	@Test
	void shouldToggleEnabled() {
		PPOCRProperties props = new PPOCRProperties();
		assertTrue(props.isEnabled());
		props.setEnabled(false);
		assertFalse(props.isEnabled());
	}

	@Test
	void shouldSetCustomTunables() {
		PPOCRProperties props = new PPOCRProperties();
		props.setDetLimitSideLen(128);
		props.setDetLimitType("max");
		props.setDetThresh(0.5f);
		props.setDetBoxThresh(0.8f);
		props.setDetUnclipRatio(2.0f);
		props.setRecBatchSize(12);
		props.setPreferAccelerator(true);
		props.setIntraOpNumThreads(4);
		props.setInterOpNumThreads(2);
		props.setEnableCpuMemArena(true);
		props.setEnableMemoryPattern(true);

		assertEquals(128, props.getDetLimitSideLen());
		assertEquals("max", props.getDetLimitType());
		assertEquals(0.5f, props.getDetThresh());
		assertEquals(0.8f, props.getDetBoxThresh());
		assertEquals(2.0f, props.getDetUnclipRatio());
		assertEquals(12, props.getRecBatchSize());
		assertTrue(props.isPreferAccelerator());
		assertEquals(4, props.getIntraOpNumThreads());
		assertEquals(2, props.getInterOpNumThreads());
		assertTrue(props.isEnableCpuMemArena());
		assertTrue(props.isEnableMemoryPattern());
	}

	@Test
	void shouldSetRecImageShape() {
		PPOCRProperties props = new PPOCRProperties();
		props.setRecImageShape(new int[]{3, 64, 640});
		assertArrayEquals(new int[]{3, 64, 640}, props.getRecImageShape());
	}
}
