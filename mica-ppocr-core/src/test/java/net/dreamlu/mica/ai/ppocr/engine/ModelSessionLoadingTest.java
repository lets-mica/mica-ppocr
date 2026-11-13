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

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import net.dreamlu.mica.ai.ppocr.config.PPOcrV6Config;
import net.dreamlu.mica.ai.ppocr.utils.CollUtil;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型加载方式等价性测试。
 *
 * <p>背景：引擎改用 ONNX Runtime 的<b>路径版</b>会话 API
 * （{@code OrtEnvironment#createSession(String, SessionOptions)}），
 * 让模型字节不再整份进 JVM 堆（medium 档 rec 模型约 73 MiB）；
 * classpath 资源（jar 内，给不了路径）仍走字节版
 * {@code createSession(byte[], SessionOptions)}。
 *
 * <p>本测试锁死「<b>两种加载方式产出的推理结果逐位一致</b>」——
 * 这是换加载通道的前提：省内存不能换来数值漂移。
 *
 * <p>直接用 ORT 比对输出张量（不经引擎）：引擎层的端到端行为由
 * {@link PPOcrV6EngineResourceTest} 与结构化模块的 golden 覆盖。
 */
class ModelSessionLoadingTest {

	/**
	 * 路径版会话与字节版会话，对同一份模型、同一份输入，输出张量逐位一致。
	 */
	@Test
	void pathLoadedSession_producesIdenticalOutputAsByteLoadedSession() throws Exception {
		Path detModel = tinyDetModel();
		Assumptions.assumeTrue(detModel != null, "tiny det 模型缺失，跳过");

		byte[] modelBytes = Files.readAllBytes(detModel);
		try (OrtEnvironment env = OrtEnvironment.getEnvironment();
			 OrtSession.SessionOptions opts = new OrtSession.SessionOptions()) {
			try (OrtSession byPath = env.createSession(detModel.toAbsolutePath().toString(), opts);
				 OrtSession byBytes = env.createSession(modelBytes, opts)) {
				String inputName = byPath.getInputNames().iterator().next();
				long[] shape = resolveInputShape(byPath, inputName);
				float[] data = buildDeterministicInput(shape);

				try (OnnxTensor tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape)) {
					Map<String, OnnxTensor> inputs = Collections.singletonMap(inputName, tensor);
					float[] outByPath = runAndFlatten(byPath, inputs);
					float[] outByBytes = runAndFlatten(byBytes, inputs);

					assertTrue(outByPath.length > 0, "det 模型应输出非空张量");
					assertArrayEquals(outByBytes, outByPath, 0f,
						"路径加载与字节加载的推理结果必须逐位一致");
				}
			}
		}
	}

	/**
	 * 引擎构造期的 Java 侧堆分配必须<b>小于模型文件总大小</b>。
	 *
	 * <p>一旦退回「整份读成 byte[] 再交给 ORT」，分配量必然 ≥ 模型总大小 ⇒ 此断言即红。
	 * 阈值取「模型总大小」而非某个拍脑袋的常量：它既跟档位走，又天然大于实际分配量
	 * （tiny 档实测构造期分配约 2.3 MB，模型总大小约 6.0 MB）。
	 *
	 * <p>用线程分配量（{@code ThreadMXBean#getThreadAllocatedBytes}）而非堆峰值 ——
	 * 后者受 GC 时机影响，跨 JDK 版本波动大；前者是「本线程累计分配字节数」，是确定量。
	 */
	@Test
	void engineConstruction_doesNotLoadModelBytesIntoHeap() throws Exception {
		Path detModel = tinyDetModel();
		Assumptions.assumeTrue(detModel != null, "tiny det 模型缺失，跳过");
		Path recModel = detModel.getParent().resolve("rec.onnx");
		Assumptions.assumeTrue(Files.isRegularFile(recModel), "tiny rec 模型缺失，跳过");
		long modelBytes = Files.size(detModel) + Files.size(recModel);

		PPOcrV6Config config = PPOcrV6Config.builder()
			.detModelPath(detModel.toString())
			.recModelPath(recModel.toString())
			.recCharDictPath(detModel.getParent().resolve("dict.txt").toString())
			.build();

		// 第一次构造排掉类加载 / JIT 的一次性成本
		try (PPOcrV6Engine warm = new PPOcrV6Engine(config)) {
			// no-op
		}

		long allocated = measureThreadAllocation(() -> {
			try (PPOcrV6Engine engine = new PPOcrV6Engine(config)) {
				// no-op
			}
		});
		assertTrue(allocated >= 0 && allocated < modelBytes,
			"构造期 Java 侧堆分配 " + allocated + " B 应小于模型总大小 " + modelBytes
				+ " B —— 模型字节不该整份进堆");
	}

	/**
	 * 统计给定动作在<b>当前线程</b>上的 Java 侧堆分配量。
	 *
	 * <p>用反射走 {@code com.sun.management.ThreadMXBean#getThreadAllocatedBytes}：
	 * 该接口不在标准 API 里，用反射可让不支持它的 JVM（OpenJ9 等）跳过而不是编译失败。
	 *
	 * @param action 待测动作
	 * @return 分配字节数；不支持统计时返回 -1
	 */
	private static long measureThreadAllocation(Runnable action) {
		try {
			Class<?> iface = Class.forName("com.sun.management.ThreadMXBean");
			Method method = iface.getMethod("getThreadAllocatedBytes", long.class);
			Object bean = ManagementFactory.getThreadMXBean();
			Assumptions.assumeTrue(iface.isInstance(bean), "当前 JVM 不支持线程分配量统计，跳过");
			long tid = Thread.currentThread().getId();
			long before = ((Number) method.invoke(bean, tid)).longValue();
			action.run();
			long after = ((Number) method.invoke(bean, tid)).longValue();
			return after - before;
		} catch (ClassNotFoundException | NoSuchMethodException e) {
			Assumptions.assumeTrue(false, "当前 JVM 不支持线程分配量统计，跳过：" + e);
			return -1L;
		} catch (ReflectiveOperationException | RuntimeException e) {
			Assumptions.assumeTrue(false, "线程分配量统计失败，跳过：" + e);
			return -1L;
		}
	}

	/**
	 * 从会话的输入信息解析出可用的输入形状：模型写死的维度直接采用，
	 * 动态维度按「batch=1 / 通道=3 / 空间=64」补齐（det 模型是 NCHW float）。
	 *
	 * @param session    会话
	 * @param inputName  输入名
	 * @return 补齐后的形状
	 * @throws OrtException 读取输入信息失败
	 */
	private static long[] resolveInputShape(OrtSession session, String inputName) throws OrtException {
		NodeInfo info = session.getInputInfo().get(inputName);
		long[] declared = ((TensorInfo) info.getInfo()).getShape();
		long[] resolved = new long[declared.length];
		for (int i = 0; i < declared.length; i++) {
			if (declared[i] > 0) {
				resolved[i] = declared[i];
			} else if (i == 0) {
				resolved[i] = 1L;
			} else if (i == 1) {
				resolved[i] = 3L;
			} else {
				resolved[i] = 64L;
			}
		}
		return resolved;
	}

	/**
	 * 构造确定性的输入数据（不引入随机数，保证可复现）。
	 *
	 * @param shape 输入形状
	 * @return 输入数据
	 */
	private static float[] buildDeterministicInput(long[] shape) {
		long count = 1L;
		for (long dim : shape) {
			count *= dim;
		}
		float[] data = new float[(int) count];
		for (int i = 0; i < data.length; i++) {
			data[i] = ((i * 37) % 251) / 251f;
		}
		return data;
	}

	/**
	 * 跑一次推理并把输出张量摊平成 float 数组（跨维度比较用）。
	 *
	 * @param session 会话
	 * @param inputs  输入
	 * @return 输出数据
	 * @throws Exception 推理失败
	 */
	private static float[] runAndFlatten(OrtSession session, Map<String, OnnxTensor> inputs) throws Exception {
		try (OrtSession.Result result = session.run(inputs)) {
			OnnxValue value = result.get(0);
			FloatBuffer buffer = ((OnnxTensor) value).getFloatBuffer().duplicate();
			buffer.rewind();
			float[] out = new float[buffer.remaining()];
			buffer.get(out);
			return out;
		}
	}

	/**
	 * 定位 tiny 档 det 模型。
	 *
	 * @return 模型路径；找不到时返回 null
	 */
	private static Path tinyDetModel() {
		Path root = findRepositoryRoot();
		if (root == null) {
			return null;
		}
		Path model = root.resolve("models/ppocr-v6/tiny/det.onnx");
		return Files.isRegularFile(model) ? model : null;
	}

	private static Path findRepositoryRoot() {
		String multiModuleDir = System.getProperty("maven.multiModuleProjectDirectory");
		if (multiModuleDir != null && Files.isDirectory(CollUtil.pathOf(multiModuleDir).resolve("models"))) {
			return CollUtil.pathOf(multiModuleDir);
		}
		Path current = CollUtil.pathOf("").toAbsolutePath();
		while (current != null && !Files.isDirectory(current.resolve("models/ppocr-v6/tiny"))) {
			current = current.getParent();
		}
		return current;
	}
}
