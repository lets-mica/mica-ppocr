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

import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Engine;
import net.dreamlu.mica.ai.ppocr.pdf.PdfOcrConfig;
import net.dreamlu.mica.ai.ppocr.structured.parser.bankcard.BankCardParser;
import net.dreamlu.mica.ai.ppocr.structured.parser.vehicle.VehicleLicenseParser;
import net.dreamlu.mica.ai.ppocr.utils.CollUtil;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.noear.solon.SimpleSolonApp;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Solon 端 {@link StructuredParserAutoConfiguration} 单元测试。
 *
 * <p>核心约定：{@code mica.ai.ppocr.enabled=false} 时<b>整个 Starter 不注入任何 Bean</b>
 * （见 README「开关：设为 false 时整个 Starter 不注入任何 Bean」）。
 *
 * <p>与 Spring 端保持行为一致：解析器 Bean 依赖 {@link PPOcrV6Engine} 注入，
 * 引擎 Bean 因主配置被关闭而不存在时，若解析器配置未受同一开关约束，
 * 容器将直接启动失败。
 */
class StructuredParserAutoConfigurationTest {

	/**
	 * 关闭 Starter 时，容器必须能正常启动，且不注册任何 PP-OCR 相关 Bean。
	 */
	@Test
	void disabledStarterShouldStartWithoutAnyPpocrBean() throws Throwable {
		SimpleSolonApp app = new SimpleSolonApp(StructuredParserAutoConfigurationTest.class);
		app.cfg().put("mica.ai.ppocr.enabled", "false");
		app.start(null);

		assertNull(app.context().getBean(PPOcrV6Engine.class));
		assertNull(app.context().getBean(PPOcrTemplate.class));
		assertNull(app.context().getBean(VehicleLicenseParser.class));
		assertNull(app.context().getBean(BankCardParser.class));
		assertNull(app.context().getBean(PdfOcrConfig.class));
	}

	/**
	 * 启用 Starter（默认）时，10 个内置解析器与模板必须照常注册 ——
	 * 防止闸门加得过宽，把正常场景一起挡掉。
	 */
	@Test
	void enabledStarterShouldRegisterParsersAndTemplate() throws Throwable {
		Path modelDir = tinyModelDir();
		Assumptions.assumeTrue(modelDir != null, "tiny 模型缺失，跳过集成测试");

		SimpleSolonApp app = new SimpleSolonApp(StructuredParserAutoConfigurationTest.class);
		app.cfg().put("mica.ai.ppocr.det-model-path", modelDir.resolve("det.onnx").toString());
		app.cfg().put("mica.ai.ppocr.rec-model-path", modelDir.resolve("rec.onnx").toString());
		app.cfg().put("mica.ai.ppocr.rec-char-dict-path", modelDir.resolve("dict.txt").toString());
		app.start(null);

		assertNotNull(app.context().getBean(PPOcrV6Engine.class));
		assertNotNull(app.context().getBean(PPOcrTemplate.class));
		assertNotNull(app.context().getBean(VehicleLicenseParser.class));
		assertNotNull(app.context().getBean(BankCardParser.class));
		assertNotNull(app.context().getBean(PdfOcrConfig.class));

		app.context().getBean(PPOcrV6Engine.class).close();
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
