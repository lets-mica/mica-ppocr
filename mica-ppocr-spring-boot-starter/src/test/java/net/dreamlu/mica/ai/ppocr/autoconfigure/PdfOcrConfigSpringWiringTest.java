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

import net.dreamlu.mica.ai.ppocr.config.PPOcrV6Config;
import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Engine;
import net.dreamlu.mica.ai.ppocr.pdf.PdfOcrConfig;
import net.dreamlu.mica.ai.ppocr.utils.CollUtil;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring 端 {@link PdfOcrConfig} 接线测试。
 *
 * <p>验证三件事：
 * <ol>
 *   <li>Spring 能把 {@code mica.ai.ppocr.pdf.*} 绑定进嵌套的 {@link PPOCRProperties.Pdf}</li>
 *   <li>该配置经 {@link PPOcrV6Config} 传到了引擎（{@link PPOcrV6Engine#getPdfOcrConfig()}）</li>
 *   <li>{@code pdf.enabled=false} 时引擎回落到 {@link PdfOcrConfig#defaults()}</li>
 * </ol>
 */
class PdfOcrConfigSpringWiringTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(PPOCRAutoConfiguration.class, PdfAutoConfiguration.class));

	@Test
	void springBindsNestedPdfProperties() {
		Path modelDir = tinyModelDir();
		Assumptions.assumeTrue(modelDir != null, "tiny 模型缺失，跳过集成测试");

		runner
			.withPropertyValues(
				"mica.ai.ppocr.det-model-path=" + modelDir.resolve("det.onnx"),
				"mica.ai.ppocr.rec-model-path=" + modelDir.resolve("rec.onnx"),
				"mica.ai.ppocr.rec-char-dict-path=" + modelDir.resolve("dict.txt"),
				"mica.ai.ppocr.pdf.render-dpi=300",
				"mica.ai.ppocr.pdf.min-text-chars=50",
				"mica.ai.ppocr.pdf.min-readable-ratio=0.8",
				"mica.ai.ppocr.pdf.force-ocr=true"
			)
			.run(context -> {
				assertThat(context).hasNotFailed();
				PPOCRProperties properties = context.getBean(PPOCRProperties.class);
				assertThat(properties.getPdf().getRenderDpi()).isEqualTo(300);
				assertThat(properties.getPdf().getMinTextChars()).isEqualTo(50);
				assertThat(properties.getPdf().getMinReadableRatio()).isEqualTo(0.8);
				assertThat(properties.getPdf().isForceOcr()).isTrue();

				PdfOcrConfig cfg = properties.getPdfOcrConfig();
				assertThat(cfg.getRenderDpi()).isEqualTo(300);
				assertThat(cfg.getMinTextChars()).isEqualTo(50);
				assertThat(cfg.getMinReadableRatio()).isEqualTo(0.8);
				assertThat(cfg.isForceOcr()).isTrue();

				context.getBean(PPOcrV6Engine.class).close();
			});
	}

	@Test
	void pdfConfigReachesEngineConfig() {
		Path modelDir = tinyModelDir();
		Assumptions.assumeTrue(modelDir != null, "tiny 模型缺失，跳过集成测试");

		runner
			.withPropertyValues(
				"mica.ai.ppocr.det-model-path=" + modelDir.resolve("det.onnx"),
				"mica.ai.ppocr.rec-model-path=" + modelDir.resolve("rec.onnx"),
				"mica.ai.ppocr.rec-char-dict-path=" + modelDir.resolve("dict.txt"),
				"mica.ai.ppocr.pdf.render-dpi=300",
				"mica.ai.ppocr.pdf.force-ocr=true"
			)
			.run(context -> {
				assertThat(context).hasNotFailed();
				PPOcrV6Config config = context.getBean(PPOcrV6Config.class);
				assertThat(config.getPdfOcrConfig().getRenderDpi()).isEqualTo(300);
				assertThat(config.getPdfOcrConfig().isForceOcr()).isTrue();

				PPOcrV6Engine engine = context.getBean(PPOcrV6Engine.class);
				assertThat(engine.getPdfOcrConfig().getRenderDpi()).isEqualTo(300);
				assertThat(engine.getPdfOcrConfig().isForceOcr()).isTrue();
				engine.close();
			});
	}

	@Test
	void pdfDisabledFallsBackToDefaults() {
		Path modelDir = tinyModelDir();
		Assumptions.assumeTrue(modelDir != null, "tiny 模型缺失，跳过集成测试");

		runner
			.withPropertyValues(
				"mica.ai.ppocr.det-model-path=" + modelDir.resolve("det.onnx"),
				"mica.ai.ppocr.rec-model-path=" + modelDir.resolve("rec.onnx"),
				"mica.ai.ppocr.rec-char-dict-path=" + modelDir.resolve("dict.txt"),
				"mica.ai.ppocr.pdf.enabled=false",
				// 即使 yml 里写了 pdf 参数，enabled=false 时也应一律回落到 defaults
				"mica.ai.ppocr.pdf.render-dpi=300"
			)
			.run(context -> {
				assertThat(context).hasNotFailed();
				// enabled=false 时 Bean 仍存在，但取默认值
				assertThat(context).hasSingleBean(PdfOcrConfig.class);
				assertThat(context.getBean(PdfOcrConfig.class).getRenderDpi())
					.isEqualTo(PdfOcrConfig.defaults().getRenderDpi());

				PPOcrV6Engine engine = context.getBean(PPOcrV6Engine.class);
				assertThat(engine.getPdfOcrConfig().getRenderDpi())
					.isEqualTo(PdfOcrConfig.defaults().getRenderDpi());
				assertThat(engine.getPdfOcrConfig().getMinTextChars())
					.isEqualTo(PdfOcrConfig.defaults().getMinTextChars());
				engine.close();
			});
	}

	@Test
	void userDefinedBeanReachesEngine() {
		Path modelDir = tinyModelDir();
		Assumptions.assumeTrue(modelDir != null, "tiny 模型缺失，跳过集成测试");

		runner
			.withPropertyValues(
				"mica.ai.ppocr.det-model-path=" + modelDir.resolve("det.onnx"),
				"mica.ai.ppocr.rec-model-path=" + modelDir.resolve("rec.onnx"),
				"mica.ai.ppocr.rec-char-dict-path=" + modelDir.resolve("dict.txt")
			)
			.withBean(PdfOcrConfig.class, () -> PdfOcrConfig.builder()
				.renderDpi(999)
				.minTextChars(77)
				.forceOcr(true)
				.build())
			.run(context -> {
				assertThat(context).hasNotFailed();
				// 业务方自定义的 Bean 必须真正改变引擎行为（而不是被静默忽略）
				PPOcrV6Engine engine = context.getBean(PPOcrV6Engine.class);
				assertThat(engine.getPdfOcrConfig().getRenderDpi()).isEqualTo(999);
				assertThat(engine.getPdfOcrConfig().getMinTextChars()).isEqualTo(77);
				assertThat(engine.getPdfOcrConfig().isForceOcr()).isTrue();
				engine.close();
			});
	}

	private static Path tinyModelDir() {
		Path root = findRepositoryRoot();
		if (root == null) {
			return null;
		}
		Path modelDir = root.resolve("models/ppocr-v6/tiny");
		boolean ok = Files.isRegularFile(modelDir.resolve("det.onnx"))
			&& Files.isRegularFile(modelDir.resolve("rec.onnx"))
			&& Files.isRegularFile(modelDir.resolve("dict.txt"));
		return ok ? modelDir : null;
	}

	private static Path findRepositoryRoot() {
		String multiModuleDir = System.getProperty("maven.multiModuleProjectDirectory");
		if (multiModuleDir != null && Files.isDirectory(CollUtil.pathOf(multiModuleDir).resolve("models"))) {
			return CollUtil.pathOf(multiModuleDir);
		}
		Path current = CollUtil.pathOf("").toAbsolutePath();
		while (current != null && !Files.isDirectory(current.resolve("models"))) {
			current = current.getParent();
		}
		return current;
	}
}
