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

import net.dreamlu.mica.ai.ppocr.pdf.PdfOcrConfig;
import org.noear.solon.annotation.Bean;
import org.noear.solon.annotation.Condition;
import org.noear.solon.annotation.Configuration;

/**
 * PDF OCR 通道配置自动配置（Solon 版）。
 *
 * <p>启用条件：{@code mica.ai.ppocr.pdf.enabled=true}（默认）。
 *
 * <p>业务方可在自定义服务里通过 {@code @Inject} 注入 {@link PdfOcrConfig}。
 *
 * <p>引擎侧：同一份 {@code mica.ai.ppocr.pdf.*} 配置经
 * {@link PPOCRProperties#getPdfOcrConfig()} → {@link net.dreamlu.mica.ai.ppocr.config.PPOcrV6Config}
 * 传给 {@link net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Engine}，
 * <b>由引擎的 PDF 入口实际消费</b>（本 Bean 与引擎用的是同一份配置值，
 * 但为两个独立不可变实例；本 Bean 供业务方直接注入读取）。
 *
 * <p>置为 false 时不注册本 Bean，且引擎改用 {@link PdfOcrConfig#defaults()}。
 */
@Configuration
@Condition(onExpression = "${mica.ai.ppocr.pdf.enabled:true} == true")
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
		return PdfOcrConfig.from(properties.getPdf());
	}
}
