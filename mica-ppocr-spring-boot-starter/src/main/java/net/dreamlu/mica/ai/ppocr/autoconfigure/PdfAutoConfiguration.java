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
import net.dreamlu.mica.ai.ppocr.pdf.PdfOcrSupport;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * PDF OCR 通道自动配置。
 *
 * <p>仅当 classpath 存在 {@link PdfOcrSupport}（即 {@code mica-ppocr-pdf} 已被引入）时才注册
 * 相关 Bean。{@code pdfbox} 自身在 {@code mica-ppocr-pdf} 内部以 {@code optional=true} 标，
 * 业务方拿不到 pdfbox 传递依赖。
 *
 * <p>启用条件：{@code mica.ai.ppocr.pdf.enabled=true}（默认）且存在 {@link PPOcrV6Engine}。
 * {@code PPOcrTemplate.pdf()} 入口在 PDF 模块缺失时返回 null。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(PdfOcrSupport.class)
@ConditionalOnBean(PPOcrV6Engine.class)
@AutoConfigureAfter(PPOCRAutoConfiguration.class)
@ConditionalOnProperty(prefix = "mica.ai.ppocr.pdf", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(PPOCRProperties.class)
public class PdfAutoConfiguration {

	/**
	 * PDF 双通道配置 Bean。
	 *
	 * @param properties 顶层配置（{@code mica.ai.ppocr.pdf.*} 子对象）
	 * @return PdfOcrConfig 实例
	 */
	@Bean
	@ConditionalOnMissingBean
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
	 * PDF 双通道入口门面 Bean（双通道分流：文本层 vs 渲染 + OCR）。
	 *
	 * @param engine      推理引擎
	 * @param pdfOcrConfig PDF 配置
	 * @return PdfOcrSupport 实例
	 */
	@Bean
	@ConditionalOnMissingBean
	public PdfOcrSupport pdfOcrSupport(PPOcrV6Engine engine, PdfOcrConfig pdfOcrConfig) {
		return new PdfOcrSupport(engine, pdfOcrConfig);
	}
}
