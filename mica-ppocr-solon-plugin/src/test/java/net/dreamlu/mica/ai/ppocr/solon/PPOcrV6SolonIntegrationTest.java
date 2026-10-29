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

import net.dreamlu.mica.ai.ppocr.utils.CollUtil;

import net.dreamlu.mica.ai.ppocr.config.PPOcrV6Config;
import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Engine;
import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Result;
import net.dreamlu.mica.ai.ppocr.structured.parser.bankcard.BankCardParser;
import net.dreamlu.mica.ai.ppocr.structured.parser.business.BusinessLicenseParser;
import net.dreamlu.mica.ai.ppocr.structured.parser.driver.DriverLicenseParser;
import net.dreamlu.mica.ai.ppocr.structured.parser.idcard.IdCardParser;
import net.dreamlu.mica.ai.ppocr.structured.parser.vehicle.VehicleLicenseParser;
import net.dreamlu.mica.ai.ppocr.structured.parser.vehicle.VehicleLicenseResult;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.noear.solon.SimpleSolonApp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Solon 插件端到端集成测试。
 *
 * <p>启动真实 Solon 容器（自动加载 {@code META-INF/solon/solon.mica.ai.ppocr.properties}
 * 注册的 {@link net.dreamlu.mica.ai.ppocr.solon.integration.PpocrPlugin}），
 * 用真实 tiny 模型 + 真实测试图片跑完整 OCR 流水线，验证：
 * <ol>
 *   <li>插件自动装配：{@link PPOcrV6Engine} / {@link PPOcrTemplate} / 5 个结构化解析器全部注入；</li>
 *   <li>配置绑定：{@code mica.ai.ppocr.*} 属性正确落到 {@link PPOcrV6Config}；</li>
 *   <li>端到端推理：{@code engine.run()} 与 {@code ppocrTemplate.parseVehicleLicense()} 真实可用。</li>
 * </ol>
 *
 * <p>依赖仓库根目录 {@code models/ppocr-v6/tiny/} 模型与 {@code test_images/} 图片，
 * 缺失时通过 {@link Assumptions} 跳过（模型不随仓库分发）。
 */
class PPOcrV6SolonIntegrationTest {

	@Test
	void shouldAssembleAllBeansFromPlugin() throws Throwable {
		Path modelDir = tinyModelDir();
		Assumptions.assumeTrue(modelDir != null, "tiny 模型缺失，跳过集成测试");

		SimpleSolonApp app = new SimpleSolonApp(PPOcrV6SolonIntegrationTest.class);
		app.cfg().put("mica.ai.ppocr.det-model-path", modelDir.resolve("det.onnx").toString());
		app.cfg().put("mica.ai.ppocr.rec-model-path", modelDir.resolve("rec.onnx").toString());
		app.cfg().put("mica.ai.ppocr.rec-char-dict-path", modelDir.resolve("dict.txt").toString());
		try {
			app.start(null);

			PPOcrV6Config config = app.context().getBean(PPOcrV6Config.class);
			assertNotNull(config, "PPOcrV6Config bean 应由插件自动装配");
			// 默认配置应绑定到 yml 默认值（CPU 单线程，保证 bit-exact）
			assertFalse(config.isPreferAccelerator());
			assertEquals(1, config.getIntraOpNumThreads());

			PPOcrV6Engine engine = app.context().getBean(PPOcrV6Engine.class);
			assertNotNull(engine, "PPOcrV6Engine bean 应由插件自动装配");

			PPOcrTemplate template = app.context().getBean(PPOcrTemplate.class);
			assertNotNull(template, "PPOcrTemplate bean 应由结构化自动配置装配");

			assertNotNull(app.context().getBean(VehicleLicenseParser.class));
			assertNotNull(app.context().getBean(IdCardParser.class));
			assertNotNull(app.context().getBean(BankCardParser.class));
			assertNotNull(app.context().getBean(DriverLicenseParser.class));
			assertNotNull(app.context().getBean(BusinessLicenseParser.class));
		} finally {
			closeEngine(app);
			app.stop();
		}
	}

	@Test
	void shouldRunFullOcrPipeline() throws Throwable {
		Path modelDir = tinyModelDir();
		Path image = repositoryRoot().resolve("test_images/vehicle/vehicle1.png");
		Assumptions.assumeTrue(modelDir != null, "tiny 模型缺失，跳过集成测试");
		Assumptions.assumeTrue(Files.isRegularFile(image), "测试图片缺失，跳过集成测试");

		SimpleSolonApp app = new SimpleSolonApp(PPOcrV6SolonIntegrationTest.class);
		app.cfg().put("mica.ai.ppocr.det-model-path", modelDir.resolve("det.onnx").toString());
		app.cfg().put("mica.ai.ppocr.rec-model-path", modelDir.resolve("rec.onnx").toString());
		app.cfg().put("mica.ai.ppocr.rec-char-dict-path", modelDir.resolve("dict.txt").toString());
		try {
			app.start(null);

			PPOcrV6Engine engine = app.context().getBean(PPOcrV6Engine.class);
			List<PPOcrV6Result> results = engine.run(image.toString());
			assertFalse(results.isEmpty(), "行驶证图片应识别出至少一个文本框");
			for (PPOcrV6Result r : results) {
				assertTrue(r.score() > 0, "识别置信度应大于 0");
				assertNotNull(r.box(), "文本框坐标不应为 null");
				assertEquals(4, r.box().length, "文本框应为四边形");
			}
		} finally {
			closeEngine(app);
			app.stop();
		}
	}

	@Test
	void shouldParseStructuredDocumentThroughTemplate() throws Throwable {
		Path modelDir = tinyModelDir();
		Path image = repositoryRoot().resolve("test_images/vehicle/vehicle1.png");
		Assumptions.assumeTrue(modelDir != null, "tiny 模型缺失，跳过集成测试");
		Assumptions.assumeTrue(Files.isRegularFile(image), "测试图片缺失，跳过集成测试");

		SimpleSolonApp app = new SimpleSolonApp(PPOcrV6SolonIntegrationTest.class);
		app.cfg().put("mica.ai.ppocr.det-model-path", modelDir.resolve("det.onnx").toString());
		app.cfg().put("mica.ai.ppocr.rec-model-path", modelDir.resolve("rec.onnx").toString());
		app.cfg().put("mica.ai.ppocr.rec-char-dict-path", modelDir.resolve("dict.txt").toString());
		try {
			app.start(null);

			PPOcrTemplate template = app.context().getBean(PPOcrTemplate.class);
			VehicleLicenseResult result = template.vehicleLicense().parse(image.toString());
			assertNotNull(result, "行驶证结构化解析应返回结果对象");
		} finally {
			closeEngine(app);
			app.stop();
		}
	}

	/**
	 * 端到端：<b>预热失败不得让容器启动失败</b>。
	 *
	 * <p>失败注入：用 {@code doc_ori} 模型冒充 rec 模型 —— ONNX Session 能正常创建，
	 * 但预热推理时 rec 的输入 shape（3×48×W）与之不匹配 ⇒ 预热必然失败。
	 * 预热是「尽力而为」的优化手段，失败只记 WARN，引擎照常可用、容器照常启动。
	 */
	@Test
	void shouldStartEvenWhenWarmUpFails() throws Throwable {
		Path modelDir = tinyModelDir();
		Path docOri = repositoryRoot().resolve("models/ppocr-v6/doc_ori/doc_ori.onnx");
		Assumptions.assumeTrue(modelDir != null, "tiny 模型缺失，跳过集成测试");
		Assumptions.assumeTrue(Files.isRegularFile(docOri), "doc_ori 模型缺失，跳过集成测试");

		SimpleSolonApp app = new SimpleSolonApp(PPOcrV6SolonIntegrationTest.class);
		app.cfg().put("mica.ai.ppocr.det-model-path", modelDir.resolve("det.onnx").toString());
		// 故意用 doc_ori 冒充 rec：Session 可建，但推理 shape 不匹配 ⇒ 预热必失败
		app.cfg().put("mica.ai.ppocr.rec-model-path", docOri.toString());
		app.cfg().put("mica.ai.ppocr.rec-char-dict-path", modelDir.resolve("dict.txt").toString());
		try {
			app.start(null);

			PPOcrV6Engine engine = app.context().getBean(PPOcrV6Engine.class);
			assertNotNull(engine, "PPOcrV6Engine bean 应由插件自动装配");
			assertFalse(engine.isClosed(), "预热失败不应关闭引擎");
		} finally {
			closeEngine(app);
			app.stop();
		}
	}

	/**
	 * Solon 容器不负责关闭 {@link PPOcrV6Engine} 的 ONNX 会话，
	 * 测试结束后显式关闭，避免 native 资源泄漏。
	 */
	private static void closeEngine(SimpleSolonApp app) {
		PPOcrV6Engine engine = app.context().getBean(PPOcrV6Engine.class);
		if (engine != null) {
			engine.close();
		}
	}

	/**
	 * 定位仓库根目录下的 tiny 模型目录。
	 *
	 * @return tiny 模型目录（det/rec/dict 齐全），缺失时返回 null
	 */
	private static Path tinyModelDir() {
		Path dir = repositoryRoot().resolve("models/ppocr-v6/tiny");
		if (Files.isRegularFile(dir.resolve("det.onnx"))
			&& Files.isRegularFile(dir.resolve("rec.onnx"))
			&& Files.isRegularFile(dir.resolve("dict.txt"))) {
			return dir;
		}
		return null;
	}

	/**
	 * 定位仓库根目录（含 models/ 与 test_images/ 的目录）。
	 * 优先使用 Maven 注入的 multiModuleProjectDirectory，回退为向上逐级查找。
	 */
	private static Path repositoryRoot() {
		String multiModuleDir = System.getProperty("maven.multiModuleProjectDirectory");
		if (multiModuleDir != null && Files.isDirectory(CollUtil.pathOf(multiModuleDir).resolve("models"))) {
			return CollUtil.pathOf(multiModuleDir);
		}
		Path current = CollUtil.pathOf("").toAbsolutePath();
		while (current != null && !Files.isDirectory(current.resolve("models"))) {
			current = current.getParent();
		}
		if (current == null) {
			throw new IllegalStateException("repository root not found");
		}
		return current;
	}
}
