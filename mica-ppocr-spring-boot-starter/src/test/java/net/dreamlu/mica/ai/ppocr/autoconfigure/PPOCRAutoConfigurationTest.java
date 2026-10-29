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

package net.dreamlu.mica.ai.ppocr.autoconfigure;

import net.dreamlu.mica.ai.ppocr.utils.CollUtil;

import net.dreamlu.mica.ai.ppocr.config.PPOcrV6Config;
import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Engine;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PPOCRAutoConfiguration} 自动装配单元测试。
 *
 * <p>使用 {@link ApplicationContextRunner} 模拟 Spring 容器启动，
 * 验证自动配置的条件注解、属性绑定和校验逻辑。
 *
 * <p>注意：{@link net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Engine} 的创建
 * 需要真实 ONNX 模型文件，此处测试仅验证配置层；引擎创建失败属于预期行为。
 */
class PPOCRAutoConfigurationTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(PPOCRAutoConfiguration.class));

	@Test
	void shouldFailWhenRequiredPropertiesMissing() {
		runner.run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure())
				.isNotNull()
				.hasRootCauseInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("det-model-path");
		});
	}

	@Test
	void shouldNotCreateBeansWhenDisabled() {
		runner
			.withPropertyValues("mica.ai.ppocr.enabled=false")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).doesNotHaveBean(PPOcrV6Config.class);
			});
	}

	@Test
	void shouldCreateConfigBeanWithAllProperties() {
		runner
			.withPropertyValues(
				"mica.ai.ppocr.det-model-path=/models/det.onnx",
				"mica.ai.ppocr.rec-model-path=/models/rec.onnx",
				"mica.ai.ppocr.rec-char-dict-path=/models/dict.txt",
				"mica.ai.ppocr.det-limit-side-len=128",
				"mica.ai.ppocr.det-thresh=0.5",
				"mica.ai.ppocr.prefer-accelerator=true"
			)
			.run(context -> {
				// 配置绑定成功，引擎因模型文件不存在而失败，属于预期行为；
				// 验证失败根因是文件不存在（而非配置缺失），间接证明配置绑定已通过
				assertThat(context).hasFailed();
				Throwable failure = context.getStartupFailure();
				assertThat(failure)
					.hasRootCauseInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("file not found");
			});
	}

	@Test
	void shouldFailOnEngineCreationWhenModelFileMissing() {
		runner
			.withPropertyValues(
				"mica.ai.ppocr.det-model-path=/models/det.onnx",
				"mica.ai.ppocr.rec-model-path=/models/rec.onnx",
				"mica.ai.ppocr.rec-char-dict-path=/models/dict.txt"
			)
			.run(context -> {
				assertThat(context).hasFailed();
				// 所有必需配置已提供，失败原因是引擎创建时模型文件不存在
				assertThat(context.getStartupFailure())
					.hasRootCauseInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("file not found");
			});
	}

	/**
	 * 验证 yml 写 {@code classpath:} 前缀时，{@link PPOcrV6Config} 能正确透传该字符串到 engine，
	 * 而不会被 Spring/Properties 绑定做路径解析。
	 *
	 * <p>本测试先用 yml 给一个占位文件路径（绕开 {@code requireNonBlank} 校验），
	 * 再用 {@link PPOCRPropertiesCustomizer} 替换为 {@code classpath:} 路径，
	 * 断言 engine 在 classpath 资源不存在时报告 "classpath resource not found"，
	 * 而非文件系统的 "file not found"，证明 classpath: 前缀被透传给了 engine。
	 */
	@Test
	void shouldAcceptClasspathPrefixAndPropagateToConfig() {
		runner
			.withPropertyValues(
				"mica.ai.ppocr.det-model-path=/placeholder/det.onnx",
				"mica.ai.ppocr.rec-model-path=/placeholder/rec.onnx",
				"mica.ai.ppocr.rec-char-dict-path=/placeholder/dict.txt"
			)
			.withBean(PPOCRPropertiesCustomizer.class, () -> builder -> builder
				.detModelPath("classpath:models/det.onnx")
				.recModelPath("classpath:models/rec.onnx")
				.recCharDictPath("classpath:models/dict.txt"))
			.run(context -> {
				assertThat(context).hasFailed();
				Throwable root = context.getStartupFailure();
				while (root.getCause() != null) {
					root = root.getCause();
				}
				assertThat(root)
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("classpath resource not found");
			});
	}

	/**
	 * 端到端：使用文件系统路径配置时整个 ApplicationContext 能正常启动并产出 PPOcrV6Engine。
	 * 用于验证 Spring Boot 配置绑定 + 自动装配 + 引擎创建的完整链路通畅，
	 * 也作为 ModelResourceLoader 文件通道的 smoke test。
	 *
	 * <p>classpath: 通道端到端覆盖见 {@link net.dreamlu.mica.ai.ppocr.utils.ModelResourceLoaderTest}。
	 */
	@Test
	void shouldStartWithRealFilePathModel() {
		Path root = findRepositoryRoot();
		Path modelDir = root.resolve("models/ppocr-v6/tiny");
		Assumptions.assumeTrue(Files.isDirectory(modelDir), "requires tiny models on disk");

		runner
			.withPropertyValues(
				"mica.ai.ppocr.det-model-path=" + modelDir.resolve("det.onnx"),
				"mica.ai.ppocr.rec-model-path=" + modelDir.resolve("rec.onnx"),
				"mica.ai.ppocr.rec-char-dict-path=" + modelDir.resolve("dict.txt")
			)
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(PPOcrV6Engine.class);
				PPOcrV6Engine engine = context.getBean(PPOcrV6Engine.class);
				engine.close();
			});
	}

	/**
	 * 端到端：<b>预热失败不得让容器启动失败</b>。
	 *
	 * <p>失败注入：用 {@code doc_ori} 模型冒充 rec 模型 —— ONNX Session 能正常创建，
	 * 但预热推理时 rec 的输入 shape（3×48×W）与之不匹配 ⇒ 预热必然失败。
	 * 预热是「尽力而为」的优化手段，失败只记 WARN，引擎照常可用、容器照常启动。
	 *
	 * <p>预热本身的语义（跑通 det+rec、失败不影响可用性）由 core 模块
	 * {@code PPOcrV6EngineWarmUpTest} 覆盖。
	 */
	@Test
	void shouldStartEvenWhenWarmUpFails() {
		Path root = findRepositoryRoot();
		Path modelDir = root.resolve("models/ppocr-v6");
		Path docOri = modelDir.resolve("doc_ori/doc_ori.onnx");
		Assumptions.assumeTrue(Files.isDirectory(modelDir.resolve("tiny")), "requires tiny models on disk");
		Assumptions.assumeTrue(Files.isRegularFile(docOri), "requires doc_ori model on disk");

		runner
			.withPropertyValues(
				"mica.ai.ppocr.det-model-path=" + modelDir.resolve("tiny/det.onnx"),
				// 故意用 doc_ori 冒充 rec：Session 可建，但推理 shape 不匹配 ⇒ 预热必失败
				"mica.ai.ppocr.rec-model-path=" + docOri,
				"mica.ai.ppocr.rec-char-dict-path=" + modelDir.resolve("tiny/dict.txt")
			)
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(PPOcrV6Engine.class);
				PPOcrV6Engine engine = context.getBean(PPOcrV6Engine.class);
				assertThat(engine.isClosed()).isFalse();
				engine.close();
			});
	}

	private static Path findRepositoryRoot() {
		String multiModuleDir = System.getProperty("maven.multiModuleProjectDirectory");
		if (multiModuleDir != null) {
			return CollUtil.pathOf(multiModuleDir);
		}
		Path current = CollUtil.pathOf("").toAbsolutePath();
		while (current != null && !Files.isDirectory(current.resolve("models/ppocr-v6/tiny"))) {
			current = current.getParent();
		}
		if (current == null) {
			throw new IllegalStateException("repository root with test models not found");
		}
		return current;
	}
}