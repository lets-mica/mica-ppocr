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

import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Engine;
import net.dreamlu.mica.ai.ppocr.pdf.PdfOcrConfig;
import net.dreamlu.mica.ai.ppocr.structured.parser.bankcard.BankCardParser;
import net.dreamlu.mica.ai.ppocr.structured.parser.vehicle.VehicleLicenseParser;
import net.dreamlu.mica.ai.ppocr.utils.CollUtil;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link StructuredParserAutoConfiguration} 单元测试。
 *
 * <p>核心约定：{@code mica.ai.ppocr.enabled=false} 时<b>整个 Starter 不注入任何 Bean</b>
 * （见 README「开关：设为 false 时整个 Starter 不注入任何 Bean」）。
 *
 * <p>回归背景：{@link StructuredParserAutoConfiguration} 曾缺少该闸门，而它的 10 个解析器 Bean
 * 都以 {@link PPOcrV6Engine} 作为构造参数注入 —— 引擎 Bean 因主配置被关闭而不存在时，
 * 容器 refresh 直接失败（{@code NoSuchBeanDefinitionException}），
 * 业务方无法通过关闭开关来「优雅地」不启用 OCR 能力。
 */
class StructuredParserAutoConfigurationTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(
			PPOCRAutoConfiguration.class,
			PdfAutoConfiguration.class,
			StructuredParserAutoConfiguration.class));

	/**
	 * 关闭 Starter 时，容器必须能正常启动，且不注册任何 PP-OCR 相关 Bean。
	 */
	@Test
	void disabledStarterShouldStartWithoutAnyPpocrBean() {
		runner
			.withPropertyValues("mica.ai.ppocr.enabled=false")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).doesNotHaveBean(PPOcrV6Engine.class);
				assertThat(context).doesNotHaveBean(PPOcrTemplate.class);
				assertThat(context).doesNotHaveBean(VehicleLicenseParser.class);
				assertThat(context).doesNotHaveBean(BankCardParser.class);
				assertThat(context).doesNotHaveBean(PdfOcrConfig.class);
			});
	}

	/**
	 * 关闭 Starter 时，<b>不应</b>因为解析器依赖引擎而让容器启动失败 ——
	 * 这是本测试存在的核心原因（回归前此处是 {@code NoSuchBeanDefinitionException}）。
	 */
	@Test
	void disabledStarterShouldNotFailOnParserEngineDependency() {
		runner
			.withPropertyValues("mica.ai.ppocr.enabled=false")
			.run(context -> assertThat(context).hasNotFailed());
	}

	/**
	 * 启用 Starter（默认）时，10 个内置解析器与模板必须照常注册 ——
	 * 防止闸门加得过宽，把正常场景一起挡掉。
	 */
	@Test
	void enabledStarterShouldRegisterParsersAndTemplate() {
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
				assertThat(context).hasSingleBean(PPOcrTemplate.class);
				assertThat(context).hasSingleBean(VehicleLicenseParser.class);
				assertThat(context).hasSingleBean(BankCardParser.class);
				assertThat(context).hasSingleBean(PdfOcrConfig.class);
				context.getBean(PPOcrV6Engine.class).close();
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
