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
import net.dreamlu.mica.ai.ppocr.pdf.PdfOcrSupport;
import org.noear.solon.annotation.Bean;
import org.noear.solon.annotation.Condition;
import org.noear.solon.annotation.Configuration;

/**
 * PDF OCR 通道自动配置（Solon 版）。
 *
 * <p>启用条件：classpath 存在 {@link PdfOcrSupport}（即 {@code mica-ppocr-pdf} 已被引入）
 * 且 {@code mica.ai.ppocr.pdf.enabled=true}（默认）且存在 {@link PPOcrV6Engine}。
 *
 * <p>{@code PPOcrTemplate.pdf()} 在 PDF 模块缺失时返回 null。
 */
@Configuration
@Condition(
	onClass = PdfOcrSupport.class,
	onBean = PPOcrV6Engine.class,
	onExpression = "${mica.ai.ppocr.pdf.enabled:true} == true"
)
public class PdfAutoConfiguration {

	/**
	 * PDF 双通道配置 Bean。
	 *
	 * @param properties 顶层配置
	 * @return PdfOcrConfig 实例
	 */
	@Bean
	@Condition(onMissingBean = PdfOcrConfig.class)
	public PdfOcrConfig pdfOcrConfig(PPOCRProperties properties) {
		PPOCRProperties.Pdf p = properties.getPdf();
		return PdfOcrConfig.builder()
			.renderDpi(p.getRenderDpi())
			.minTextChars(p.getMinTextChars())
			.minReadableRatio(p.getMinReadableRatio())
			.forceOcr(p.isForceOcr())
			.build();
	}

	/**
	 * PDF 双通道入口门面 Bean。
	 *
	 * @param engine       推理引擎
	 * @param pdfOcrConfig PDF 配置
	 * @return PdfOcrSupport 实例
	 */
	@Bean
	@Condition(onMissingBean = PdfOcrSupport.class)
	public PdfOcrSupport pdfOcrSupport(PPOcrV6Engine engine, PdfOcrConfig pdfOcrConfig) {
		return new PdfOcrSupport(engine, pdfOcrConfig);
	}
}
