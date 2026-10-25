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
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * PDF OCR 通道配置自动配置。
 *
 * <p>把 {@code mica.ai.ppocr.pdf.*} 绑定为 {@link PdfOcrConfig} Bean。
 *
 * <p><b>本 Bean 即引擎使用的配置</b>：{@link PPOCRAutoConfiguration} 直接把它注入
 * {@link net.dreamlu.mica.ai.ppocr.config.PPOcrV6Config}，再由
 * {@link net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Engine} 的 PDF 入口
 * （{@code run(byte[])} / {@code run(Path)} 嗅探到 {@code %PDF-} 时）消费，
 * 影响<b>文本层可用性判定</b>与<b>渲染通道 DPI</b>；图片通道不受影响。
 *
 * <p>因此本 Bean <b>不是只读副本</b>：业务方可以自定义它（{@code @ConditionalOnMissingBean}
 * 让自定义 Bean 生效），自定义值会直接改变引擎行为 —— 不再出现
 * 「容器里是自定义值、引擎却仍用默认值」的静默失效。
 *
 * <p>{@code mica.ai.ppocr.pdf.enabled=false} 时，本 Bean 仍然存在，但取
 * {@link PdfOcrConfig#defaults()}（忽略 yml 中的 pdf 参数）。
 */
@Configuration(proxyBeanMethods = false)
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
		return properties.getPdfOcrConfig();
	}
}
