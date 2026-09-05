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

import net.dreamlu.mica.ai.ppocr.pdf.PdfOcrConfig;
import net.dreamlu.mica.ai.ppocr.pdf.PdfOcrSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PdfAutoConfiguration} 单元测试。
 */
class PdfAutoConfigurationTest {

	@Test
	void pdfDisabledByProperty() {
		// mica.ai.ppocr.pdf.enabled=false 时 PDF bean 不应被创建
		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(PdfAutoConfiguration.class))
			.withPropertyValues("mica.ai.ppocr.pdf.enabled=false")
			.withBean(net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Engine.class, () -> null)
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).doesNotHaveBean(PdfOcrSupport.class);
				assertThat(context).doesNotHaveBean(PdfOcrConfig.class);
			});
	}

	@Test
	void pdfConfigBindingIsolated() {
		// 隔离 PDF 配置绑定：只注册 PdfAutoConfiguration（不依赖 engine 创建）
		// PPOcrV6Engine 注入为 null 占位 — @ConditionalOnBean 只检查类型是否存在
		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(PdfAutoConfiguration.class))
			.withBean(net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Engine.class, () -> null)
			.run(context -> {
				assertThat(context).hasBean("pdfOcrConfig");
				PdfOcrConfig cfg = context.getBean(PdfOcrConfig.class);
				// 默认值
				assertThat(cfg.getRenderDpi()).isEqualTo(200);
				assertThat(cfg.getMinTextChars()).isEqualTo(10);
			});
	}

	@Test
	void pdfConfigBindsCustomValues() {
		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(PdfAutoConfiguration.class))
			.withPropertyValues(
				"mica.ai.ppocr.pdf.render-dpi=300",
				"mica.ai.ppocr.pdf.min-text-chars=50",
				"mica.ai.ppocr.pdf.min-readable-ratio=0.8",
				"mica.ai.ppocr.pdf.force-ocr=true"
			)
			.withBean(net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Engine.class, () -> null)
			.run(context -> {
				assertThat(context).hasBean("pdfOcrConfig");
				PdfOcrConfig cfg = context.getBean(PdfOcrConfig.class);
				assertThat(cfg.getRenderDpi()).isEqualTo(300);
				assertThat(cfg.getMinTextChars()).isEqualTo(50);
				assertThat(cfg.getMinReadableRatio()).isEqualTo(0.8);
				assertThat(cfg.isForceOcr()).isTrue();
			});
	}

	@Test
	void pdfSupportBeanCreated() {
		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(PdfAutoConfiguration.class))
			.withBean(net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Engine.class, () -> null)
			.run(context -> {
				assertThat(context).hasBean("pdfOcrSupport");
				assertThat(context.getBean(PdfOcrSupport.class)).isNotNull();
			});
	}
}
