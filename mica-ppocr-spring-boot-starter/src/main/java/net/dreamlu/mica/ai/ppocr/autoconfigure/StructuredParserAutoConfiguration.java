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
import net.dreamlu.mica.ai.ppocr.structured.parser.bankcard.BankCardParser;
import net.dreamlu.mica.ai.ppocr.structured.parser.business.BusinessLicenseParser;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.BaseStructuredParser;
import net.dreamlu.mica.ai.ppocr.structured.parser.driver.DriverLicenseParser;
import net.dreamlu.mica.ai.ppocr.structured.parser.household.HouseholdRegisterParser;
import net.dreamlu.mica.ai.ppocr.structured.parser.idcard.IdCardParser;
import net.dreamlu.mica.ai.ppocr.structured.parser.invoice.InvoiceParser;
import net.dreamlu.mica.ai.ppocr.structured.parser.pdd.PddLuckyBagParser;
import net.dreamlu.mica.ai.ppocr.structured.parser.taxi.TaxiReceiptParser;
import net.dreamlu.mica.ai.ppocr.structured.parser.train.TrainTicketParser;
import net.dreamlu.mica.ai.ppocr.structured.parser.vehicle.VehicleLicenseParser;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 结构化解析器自动配置。
 *
 * <p>当 classpath 存在 {@link BaseStructuredParser}（即 {@code mica-ppocr-structured} 在依赖链中）时，
 * 自动注册 10 个内置解析器（行驶证 / 身份证 / 银行卡 / 驾照 / 营业执照 / 发票 / 火车票 / 出租车票 / 户口本 / 拼多多福袋）
 * 和 {@link PPOcrTemplate} 模板。
 *
 * <p>每个解析器都是独立 {@code @Bean}，配合 {@link ConditionalOnMissingBean}，
 * 业务方可精确 override 单个解析器实现。
 *
 * <p><b>启用条件与 {@link PPOCRAutoConfiguration} 一致</b>（{@code mica.ai.ppocr.enabled=true}，默认 true）：
 * 关闭 Starter 时本配置类整体不生效。否则 10 个解析器 Bean 会因找不到
 * {@link PPOcrV6Engine}（主配置已被关闭，引擎 Bean 不存在）而让容器 refresh 直接失败 ——
 * 业务方就无法用「关闭开关」的方式优雅地不启用 OCR 能力。
 *
 * <p>使用示例：
 * <pre>
 * &#64;Autowired
 * private PPOcrTemplate ppocr;
 *
 * &#64;PostMapping("/vehicle")
 * public VehicleLicenseResult recognize(&#64;RequestParam("file") MultipartFile file) throws IOException {
 *     return ppocr.vehicleLicense().parse(file.getBytes());
 * }
 * </pre>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(BaseStructuredParser.class)
@ConditionalOnProperty(prefix = "mica.ai.ppocr", name = "enabled", havingValue = "true", matchIfMissing = true)
@AutoConfigureAfter(PPOCRAutoConfiguration.class)
public class StructuredParserAutoConfiguration {

	/**
	 * 注册行驶证解析器。
	 *
	 * @param engine PP-OCRv6 推理引擎
	 * @return 行驶证解析器实例
	 */
	@Bean
	@ConditionalOnMissingBean
	public VehicleLicenseParser vehicleLicenseParser(PPOcrV6Engine engine) {
		return new VehicleLicenseParser(engine);
	}

	/**
	 * 注册身份证解析器（正反面自动判定）。
	 *
	 * @param engine PP-OCRv6 推理引擎
	 * @return 身份证解析器实例
	 */
	@Bean
	@ConditionalOnMissingBean
	public IdCardParser idCardParser(PPOcrV6Engine engine) {
		return new IdCardParser(engine);
	}

	/**
	 * 注册银行卡解析器。
	 *
	 * @param engine PP-OCRv6 推理引擎
	 * @return 银行卡解析器实例
	 */
	@Bean
	@ConditionalOnMissingBean
	public BankCardParser bankCardParser(PPOcrV6Engine engine) {
		return new BankCardParser(engine);
	}

	/**
	 * 注册驾驶证解析器。
	 *
	 * @param engine PP-OCRv6 推理引擎
	 * @return 驾驶证解析器实例
	 */
	@Bean
	@ConditionalOnMissingBean
	public DriverLicenseParser driverLicenseParser(PPOcrV6Engine engine) {
		return new DriverLicenseParser(engine);
	}

	/**
	 * 注册营业执照解析器。
	 *
	 * @param engine PP-OCRv6 推理引擎
	 * @return 营业执照解析器实例
	 */
	@Bean
	@ConditionalOnMissingBean
	public BusinessLicenseParser businessLicenseParser(PPOcrV6Engine engine) {
		return new BusinessLicenseParser(engine);
	}

	/**
	 * 注册发票统一入口解析器（分发器：自动判别新版电子发票 / 老版增值税发票）。
	 *
	 * <p>子解析器（{@code VatInvoiceParser} / {@code ElectronicInvoiceParser}）在构造时
	 * 内部初始化，不作为独立 bean 暴露。
	 *
	 * @param engine PP-OCRv6 推理引擎
	 * @return 发票分发器实例
	 */
	@Bean
	@ConditionalOnMissingBean
	public InvoiceParser invoiceParser(PPOcrV6Engine engine) {
		return new InvoiceParser(engine);
	}

	/**
	 * 注册火车票解析器。
	 *
	 * @param engine PP-OCRv6 推理引擎
	 * @return 火车票解析器实例
	 */
	@Bean
	@ConditionalOnMissingBean
	public TrainTicketParser trainTicketParser(PPOcrV6Engine engine) {
		return new TrainTicketParser(engine);
	}

	/**
	 * 注册出租车票解析器。
	 *
	 * @param engine PP-OCRv6 推理引擎
	 * @return 出租车票解析器实例
	 */
	@Bean
	@ConditionalOnMissingBean
	public TaxiReceiptParser taxiReceiptParser(PPOcrV6Engine engine) {
		return new TaxiReceiptParser(engine);
	}

	/**
	 * 注册户口本解析器。
	 *
	 * @param engine PP-OCRv6 推理引擎
	 * @return 户口本解析器实例
	 */
	@Bean
	@ConditionalOnMissingBean
	public HouseholdRegisterParser householdRegisterParser(PPOcrV6Engine engine) {
		return new HouseholdRegisterParser(engine);
	}

	/**
	 * 注册拼多多福袋解析器。
	 *
	 * @param engine PP-OCRv6 推理引擎
	 * @return 拼多多福袋解析器实例
	 */
	@Bean
	@ConditionalOnMissingBean
	public PddLuckyBagParser pddLuckyBagParser(PPOcrV6Engine engine) {
		return new PddLuckyBagParser(engine);
	}

	/**
	 * 注册 PP-OCR 结构化识别模板。
	 *
	 * <p>仅当容器中存在 {@link PPOcrV6Engine} 时才创建，避免在未配置模型路径时启动失败。
	 * 模板构造时通过 {@link ApplicationContext} 收集容器中全部 {@link BaseStructuredParser}
	 * bean 并按类型建索引，供 {@code get(Class)} 通用查表与各解析器 getter 使用。
	 *
	 * @param context 应用程序上下文
	 * @param engine  PP-OCRv6 推理引擎
	 * @return 结构化识别模板
	 */
	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnBean(PPOcrV6Engine.class)
	public PPOcrTemplate ppocrTemplate(ApplicationContext context, PPOcrV6Engine engine) {
		return new PPOcrTemplate(context, engine);
	}
}
