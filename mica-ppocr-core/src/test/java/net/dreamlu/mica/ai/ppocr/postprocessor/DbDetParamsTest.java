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

package net.dreamlu.mica.ai.ppocr.postprocessor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@link DbDetParams} 单元测试：验证不可变值对象语义。
 */
class DbDetParamsTest {

	@Test
	void ofStoresAllThreeParams() {
		DbDetParams p = DbDetParams.of(0.3f, 0.6f, 1.5f);

		assertEquals(0.3f, p.thresh(), 0.0f);
		assertEquals(0.6f, p.boxThresh(), 0.0f);
		assertEquals(1.5f, p.unclipRatio(), 0.0f);
	}

	@Test
	void acceptsArbitraryValuesIncludingEdges() {
		// 0.0 / 1.0 / 边界值都应原样存储（不在构造期校验范围，由 DbPostProcessor 决定）
		DbDetParams zero = DbDetParams.of(0f, 0f, 0f);
		DbDetParams one = DbDetParams.of(1f, 1f, 1f);

		assertEquals(0f, zero.thresh(), 0.0f);
		assertEquals(0f, zero.boxThresh(), 0.0f);
		assertEquals(0f, zero.unclipRatio(), 0.0f);
		assertEquals(1f, one.thresh(), 0.0f);
		assertEquals(1f, one.boxThresh(), 0.0f);
		assertEquals(1f, one.unclipRatio(), 0.0f);
	}

	@Test
	void equalsAndHashCodeAreValueBased() {
		DbDetParams a = DbDetParams.of(0.3f, 0.6f, 1.5f);
		DbDetParams b = DbDetParams.of(0.3f, 0.6f, 1.5f);
		DbDetParams c = DbDetParams.of(0.4f, 0.6f, 1.5f);

		assertEquals(a, b);
		assertEquals(a.hashCode(), b.hashCode());
		assertNotEquals(a, c);
	}

	@Test
	void eachOfReturnsIndependentInstance() {
		// Lombok @Value 在构造期创建新对象，无单例语义；调用方拿到的是不同引用
		DbDetParams a = DbDetParams.of(0.3f, 0.6f, 1.5f);
		DbDetParams b = DbDetParams.of(0.3f, 0.6f, 1.5f);

		assertNotNull(a);
		assertNotNull(b);
		assertNotEquals(System.identityHashCode(a), System.identityHashCode(b));
	}
}
