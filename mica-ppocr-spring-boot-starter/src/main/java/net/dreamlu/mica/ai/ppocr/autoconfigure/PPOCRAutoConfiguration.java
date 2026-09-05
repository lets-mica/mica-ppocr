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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * PP-OCR 自动配置。
 *
 * <p>启用条件：mica.ai.ppocr.enabled=true（默认）。
 * 启用后必填项（det-model-path / rec-model-path / rec-char-dict-path）缺失将启动失败。
 *
 * <p>在装配 {@link PPOcrV6Config} 之前会按 Spring 顺序应用所有 {@link PPOCRPropertiesCustomizer}，
 * 供业务方在 yml 之外做旁路覆盖（环境变量 / 配置中心 / 路径解析等）。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(PPOcrV6Engine.class)
@EnableConfigurationProperties(PPOCRProperties.class)
@ConditionalOnProperty(prefix = "mica.ai.ppocr", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PPOCRAutoConfiguration {

	private static void requireNonBlank(String value, String name) {
		if (!StringUtils.hasText(value)) {
			throw new IllegalArgumentException(
				"mica-ppocr 启用失败：[" + name + "] 必须配置（可在 application.yml 中设置 mica.ai.ppocr.enabled=false 关闭该 Starter）");
		}
	}

	/**
	 * 组装 PPOcrV6Config。
	 *
	 * <p>字段拷贝逻辑下沉到 {@link PPOcrV6Config#from(PPOcrV6Config.Source)}，
	 * 本方法只负责必填校验与 customizer 注入。
	 *
	 * @param properties  yml 配置属性
	 * @param customizers PPOCRPropertiesCustomizer 集合
	 * @return PPOcrV6Config 实例
	 */
	@Bean
	public PPOcrV6Config ppocrV6Config(PPOCRProperties properties,
									   ObjectProvider<PPOCRPropertiesCustomizer> customizers) {
		requireNonBlank(properties.getDetModelPath(), "mica.ai.ppocr.det-model-path");
		requireNonBlank(properties.getRecModelPath(), "mica.ai.ppocr.rec-model-path");
		requireNonBlank(properties.getRecCharDictPath(), "mica.ai.ppocr.rec-char-dict-path");
		PPOcrV6Config.PPOcrV6ConfigBuilder builder = PPOcrV6Config.from(properties).toBuilder();
		// 容器顺序应用 customizer（依赖 @Order / Ordered 即可控）
		customizers.orderedStream().forEach(customizer -> customizer.customize(builder));
		return builder.build();
	}

	/**
	 * 注册 PP-OCR 推理引擎。
	 *
	 * @param ppOcrV6Config PP-OCR 配置
	 * @return PPOcrV6Engine 实例
	 */
	@Bean
	@ConditionalOnMissingBean
	public PPOcrV6Engine ppocrV6Engine(PPOcrV6Config ppOcrV6Config) {
		return new PPOcrV6Engine(ppOcrV6Config);
	}
}
