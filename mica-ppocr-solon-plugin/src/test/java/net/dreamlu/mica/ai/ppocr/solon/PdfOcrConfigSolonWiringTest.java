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

import net.dreamlu.mica.ai.ppocr.config.PPOcrV6Config;
import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Engine;
import net.dreamlu.mica.ai.ppocr.pdf.PdfOcrConfig;
import net.dreamlu.mica.ai.ppocr.utils.CollUtil;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.noear.solon.SimpleSolonApp;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Solon 端 {@link PdfOcrConfig} 接线测试。
 *
 * <p>验证三件事：
 * <ol>
 *   <li>Solon 能把 {@code mica.ai.ppocr.pdf.*} 绑定进嵌套的 {@link PPOCRProperties.Pdf}</li>
 *   <li>该配置经 {@link PPOcrV6Config} 传到了引擎（{@link PPOcrV6Engine#getPdfOcrConfig()}）</li>
 *   <li>{@code pdf.enabled=false} 时引擎回落到 {@link PdfOcrConfig#defaults()}</li>
 * </ol>
 */
class PdfOcrConfigSolonWiringTest {

	@Test
	void solonBindsNestedPdfProperties() throws Throwable {
		Path modelDir = tinyModelDir();
		Assumptions.assumeTrue(modelDir != null, "tiny 模型缺失，跳过集成测试");

		SimpleSolonApp app = new SimpleSolonApp(PdfOcrConfigSolonWiringTest.class);
		app.cfg().put("mica.ai.ppocr.det-model-path", modelDir.resolve("det.onnx").toString());
		app.cfg().put("mica.ai.ppocr.rec-model-path", modelDir.resolve("rec.onnx").toString());
		app.cfg().put("mica.ai.ppocr.rec-char-dict-path", modelDir.resolve("dict.txt").toString());
		app.cfg().put("mica.ai.ppocr.pdf.render-dpi", "300");
		app.cfg().put("mica.ai.ppocr.pdf.min-text-chars", "50");
		app.cfg().put("mica.ai.ppocr.pdf.min-readable-ratio", "0.8");
		app.cfg().put("mica.ai.ppocr.pdf.force-ocr", "true");
		app.start(null);

		PPOCRProperties properties = app.context().getBean(PPOCRProperties.class);
		assertEquals(300, properties.getPdf().getRenderDpi(), "Solon 应绑定嵌套 pdf.render-dpi");
		assertEquals(50, properties.getPdf().getMinTextChars());
		assertEquals(0.8, properties.getPdf().getMinReadableRatio(), 1e-9);
		assertTrue(properties.getPdf().isForceOcr());

		PdfOcrConfig cfg = properties.getPdfOcrConfig();
		assertEquals(300, cfg.getRenderDpi());
		assertEquals(50, cfg.getMinTextChars());
		assertEquals(0.8, cfg.getMinReadableRatio(), 1e-9);
		assertTrue(cfg.isForceOcr());

		app.context().getBean(PPOcrV6Engine.class).close();
	}

	@Test
	void pdfConfigReachesEngineConfig() throws Throwable {
		Path modelDir = tinyModelDir();
		Assumptions.assumeTrue(modelDir != null, "tiny 模型缺失，跳过集成测试");

		SimpleSolonApp app = new SimpleSolonApp(PdfOcrConfigSolonWiringTest.class);
		app.cfg().put("mica.ai.ppocr.det-model-path", modelDir.resolve("det.onnx").toString());
		app.cfg().put("mica.ai.ppocr.rec-model-path", modelDir.resolve("rec.onnx").toString());
		app.cfg().put("mica.ai.ppocr.rec-char-dict-path", modelDir.resolve("dict.txt").toString());
		app.cfg().put("mica.ai.ppocr.pdf.render-dpi", "300");
		app.cfg().put("mica.ai.ppocr.pdf.force-ocr", "true");
		app.start(null);

		PPOcrV6Config config = app.context().getBean(PPOcrV6Config.class);
		assertEquals(300, config.getPdfOcrConfig().getRenderDpi());
		assertTrue(config.getPdfOcrConfig().isForceOcr());

		PPOcrV6Engine engine = app.context().getBean(PPOcrV6Engine.class);
		assertEquals(300, engine.getPdfOcrConfig().getRenderDpi());
		assertTrue(engine.getPdfOcrConfig().isForceOcr());
		engine.close();
	}

	@Test
	void pdfDisabledFallsBackToDefaults() throws Throwable {
		Path modelDir = tinyModelDir();
		Assumptions.assumeTrue(modelDir != null, "tiny 模型缺失，跳过集成测试");

		SimpleSolonApp app = new SimpleSolonApp(PdfOcrConfigSolonWiringTest.class);
		app.cfg().put("mica.ai.ppocr.det-model-path", modelDir.resolve("det.onnx").toString());
		app.cfg().put("mica.ai.ppocr.rec-model-path", modelDir.resolve("rec.onnx").toString());
		app.cfg().put("mica.ai.ppocr.rec-char-dict-path", modelDir.resolve("dict.txt").toString());
		app.cfg().put("mica.ai.ppocr.pdf.enabled", "false");
		// 即使 yml 里写了 pdf 参数，enabled=false 时也应一律回落到 defaults
		app.cfg().put("mica.ai.ppocr.pdf.render-dpi", "300");
		app.start(null);

		// enabled=false 时 Bean 仍存在，但取默认值
		PdfOcrConfig bean = app.context().getBean(PdfOcrConfig.class);
		assertEquals(PdfOcrConfig.defaults().getRenderDpi(), bean.getRenderDpi());

		PPOcrV6Engine engine = app.context().getBean(PPOcrV6Engine.class);
		assertEquals(PdfOcrConfig.defaults().getRenderDpi(), engine.getPdfOcrConfig().getRenderDpi());
		assertEquals(PdfOcrConfig.defaults().getMinTextChars(), engine.getPdfOcrConfig().getMinTextChars());
		engine.close();
	}

	@Test
	void userDefinedBeanReachesEngine() throws Throwable {
		Path modelDir = tinyModelDir();
		Assumptions.assumeTrue(modelDir != null, "tiny 模型缺失，跳过集成测试");

		SimpleSolonApp app = new SimpleSolonApp(PdfOcrConfigSolonWiringTest.class);
		app.cfg().put("mica.ai.ppocr.det-model-path", modelDir.resolve("det.onnx").toString());
		app.cfg().put("mica.ai.ppocr.rec-model-path", modelDir.resolve("rec.onnx").toString());
		app.cfg().put("mica.ai.ppocr.rec-char-dict-path", modelDir.resolve("dict.txt").toString());
		// 业务方自定义 PdfOcrConfig Bean：必须真正改变引擎行为（而不是被静默忽略）
		app.context().wrapAndPut(PdfOcrConfig.class, PdfOcrConfig.builder()
			.renderDpi(999)
			.minTextChars(77)
			.forceOcr(true)
			.build());
		app.start(null);

		PPOcrV6Engine engine = app.context().getBean(PPOcrV6Engine.class);
		assertEquals(999, engine.getPdfOcrConfig().getRenderDpi());
		assertEquals(77, engine.getPdfOcrConfig().getMinTextChars());
		assertTrue(engine.getPdfOcrConfig().isForceOcr());
		engine.close();
	}

	@Test
	void pdfAutoConfigurationExcludedFallsBackToYmlValues() throws Throwable {
		Path modelDir = tinyModelDir();
		Assumptions.assumeTrue(modelDir != null, "tiny 模型缺失，跳过集成测试");

		SimpleSolonApp app = new SimpleSolonApp(PdfOcrConfigSolonWiringTest.class);
		app.cfg().put("mica.ai.ppocr.det-model-path", modelDir.resolve("det.onnx").toString());
		app.cfg().put("mica.ai.ppocr.rec-model-path", modelDir.resolve("rec.onnx").toString());
		app.cfg().put("mica.ai.ppocr.rec-char-dict-path", modelDir.resolve("dict.txt").toString());
		app.cfg().put("mica.ai.ppocr.pdf.render-dpi", "321");
		// 用户排除了 PdfAutoConfiguration → 容器里没有 PdfOcrConfig Bean，
		// 引擎必须回落 yml 派生值而不是启动失败
		app.context().beanExclude(PdfAutoConfiguration.class);
		app.start(null);

		PPOcrV6Engine engine = app.context().getBean(PPOcrV6Engine.class);
		assertEquals(321, engine.getPdfOcrConfig().getRenderDpi());
		engine.close();
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
