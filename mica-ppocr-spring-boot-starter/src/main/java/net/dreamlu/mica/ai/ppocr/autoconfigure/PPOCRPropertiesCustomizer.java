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

/**
 * PP-OCR 配置自定义器。
 *
 * <p>在 {@link PPOCRAutoConfiguration} 构建 {@link PPOcrV6Config} 之前按 Spring 容器顺序依次调用，
 * 允许业务方对配置进行旁路覆盖（例如从环境变量 / Nacos / 自定义路径解析规则动态设置模型路径）。
 *
 * <p>使用示例：
 * <pre>
 * &#64;Component
 * public class TierCustomizer implements PPOCRPropertiesCustomizer {
 *     &#64;Override
 *     public void customize(PPOcrV6Config.PPOcrV6ConfigBuilder builder) {
 *         String tier = System.getenv("PPOCR_TIER");
 *         if ("small".equals(tier)) {
 *             builder.detModelPath("models/ppocr-v6/small/det.onnx")
 *                    .recModelPath("models/ppocr-v6/small/rec.onnx")
 *                    .recCharDictPath("models/ppocr-v6/small/dict.txt");
 *         }
 *     }
 * }
 * </pre>
 *
 * <p>实现按 Spring 容器顺序生效；多个 customizer 时建议使用 {@code @Order} 显式控制。
 *
 * <p>⚠️ <b>执行顺序说明</b>：自动配置是「先校验必填项 → 再应用 customizer」，
 * 因此 {@code det-model-path} / {@code rec-model-path} / {@code rec-char-dict-path}
 * 必须先在 yml 里填上可读的占位值，否则启动时就在校验阶段失败，customizer 不会执行。
 */
@FunctionalInterface
public interface PPOCRPropertiesCustomizer {

	/**
	 * 自定义 PP-OCR 配置。
	 *
	 * @param builder 即将用于构建 {@link PPOcrV6Config} 的 builder，可修改任意字段
	 */
	void customize(PPOcrV6Config.PPOcrV6ConfigBuilder builder);
}
