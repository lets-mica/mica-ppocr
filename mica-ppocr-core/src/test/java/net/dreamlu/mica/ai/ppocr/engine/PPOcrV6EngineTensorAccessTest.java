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

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtLoggingLevel;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 引擎读取 ONNX Runtime 输出张量的<b>零拷贝路径</b>测试。
 *
 * <p>{@link PPOcrV6Engine#toFloatArray(FloatBuffer, int)} 取代了原先的
 * {@code outTensor.getFloatBuffer().get(new float[n])} —— 后者会把整块张量数据
 * （rec 每批 6.9 MB / det 原图分辨率下 26 MB）再拷贝一遍。
 *
 * <p>本测试锁两件事：
 * <ol>
 *   <li><b>零拷贝没被退化</b>：底层数组长度恰好匹配时，返回的就是 buffer 的底层数组
 *       （{@code assertSame}）。这一条是刻意锁实现细节的 —— 改回 {@code get(float[])}
 *       时不会有任何报错，只是悄悄变慢，属于典型的静默回归。</li>
 *   <li><b>数值逐位不变</b>：返回的数据与源数据逐位相同（{@code assertArrayEquals}），
 *       确保识别文本与置信度不受影响。</li>
 * </ol>
 */
class PPOcrV6EngineTensorAccessTest {

	@Test
	void toFloatArray_returnsUnderlyingArrayWithoutCopy() {
		OrtEnvironment env = OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR);
		float[] source = new float[]{1.5f, -2.25f, 0f, 3.75f, -0.5f};
		OnnxTensor tensor = null;
		try {
			tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(source), new long[]{1, source.length});
			FloatBuffer buf = tensor.getFloatBuffer();

			float[] actual = PPOcrV6Engine.toFloatArray(buf, source.length);

			// 零拷贝：返回的就是 buffer 的底层数组
			assertSame(buf.array(), actual, "底层数组长度匹配时应零拷贝返回，不得再拷贝一份");
			// 数值逐位不变
			assertArrayEquals(source, actual);
		} catch (Exception e) {
			throw new AssertionError("构造 ONNX 张量失败", e);
		} finally {
			closeQuietly(tensor);
		}
	}

	@Test
	void toFloatArray_valuesAreBitIdenticalToSource() {
		OrtEnvironment env = OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR);
		// 含 NaN / Infinity / -0.0 等边界值：逐位比较必须用 floatToIntBits，
		// assertArrayEquals 对 NaN 的比较语义与直觉不同，这里显式按位比对
		float[] source = {
			Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -0.0f, 0.0f,
			Float.MIN_VALUE, Float.MAX_VALUE, 1e-45f, 3.4028235e38f, -1.4012985e-45f
		};
		OnnxTensor tensor = null;
		try {
			tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(source), new long[]{1, source.length});
			float[] actual = PPOcrV6Engine.toFloatArray(tensor.getFloatBuffer(), source.length);
			assertEquals(source.length, actual.length);
			for (int i = 0; i < source.length; i++) {
				assertEquals(Float.floatToIntBits(source[i]), Float.floatToIntBits(actual[i]),
					"第 " + i + " 个元素位模式不一致");
			}
		} catch (Exception e) {
			throw new AssertionError("构造 ONNX 张量失败", e);
		} finally {
			closeQuietly(tensor);
		}
	}

	@Test
	void toFloatArray_truncatesWhenBufferLongerThanRequested() {
		FloatBuffer buf = FloatBuffer.wrap(new float[]{1f, 2f, 3f, 4f, 5f});
		float[] actual = PPOcrV6Engine.toFloatArray(buf, 3);
		assertEquals(3, actual.length);
		assertArrayEquals(new float[]{1f, 2f, 3f}, actual);
		assertNotSame(buf.array(), actual, "长度不匹配时必须拷贝，不能返回超长的底层数组");
	}

	@Test
	void toFloatArray_fallsBackToCopyForDirectBuffer() {
		// direct buffer 没有可访问的底层数组（hasArray() == false），必须走拷贝兜底。
		// ⚠ FloatBuffer.allocateDirect 是 Java 11+ 的 API，本仓库要求 Java 8 字节码，
		//   故用 ByteBuffer.allocateDirect + asFloatBuffer 构造。
		ByteBuffer bb = ByteBuffer.allocateDirect(4 * 4);
		bb.order(ByteOrder.nativeOrder());
		FloatBuffer direct = bb.asFloatBuffer();
		direct.put(new float[]{7f, 8f, 9f, 10f});
		direct.rewind();
		assertFalse(direct.hasArray(), "前置条件：direct buffer 不应暴露底层数组");
		float[] actual = PPOcrV6Engine.toFloatArray(direct, 4);
		assertArrayEquals(new float[]{7f, 8f, 9f, 10f}, actual);
	}

	@Test
	void toFloatArray_handlesLargeTensorLikeRecOutput() {
		OrtEnvironment env = OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR);
		// 模拟 rec 输出规模：6 × 40 × 7180 = 1,723,200 个 float（6.9 MB）
		int b = 6, t = 40, c = 7180;
		int n = b * t * c;
		float[] source = new float[n];
		Random rnd = new Random(42);
		for (int i = 0; i < n; i++) {
			source[i] = rnd.nextFloat() * 1000f - 500f;
		}
		OnnxTensor tensor = null;
		try {
			tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(source), new long[]{b, t, c});
			// ⚠ getFloatBuffer() 每次调用都会重新拷贝并返回新的 HeapFloatBuffer，
			//   因此这里必须只取一次，后续断言都基于同一个 buffer
			FloatBuffer buf = tensor.getFloatBuffer();
			float[] actual = PPOcrV6Engine.toFloatArray(buf, n);
			assertEquals(n, actual.length);
			assertSame(buf.array(), actual);
			// 抽样比对：逐位一致（floatToIntBits，NaN 也按位比较）
			for (int i : new int[]{0, 1, n / 3, n / 2, n - 1}) {
				assertEquals(Float.floatToIntBits(source[i]), Float.floatToIntBits(actual[i]));
			}
		} catch (Exception e) {
			throw new AssertionError("构造 ONNX 张量失败", e);
		} finally {
			closeQuietly(tensor);
		}
	}

	private static void closeQuietly(OnnxTensor tensor) {
		if (tensor == null) {
			return;
		}
		try {
			tensor.close();
		} catch (Exception ignore) {
			// 关闭异常不影响断言结果
		}
	}
}
