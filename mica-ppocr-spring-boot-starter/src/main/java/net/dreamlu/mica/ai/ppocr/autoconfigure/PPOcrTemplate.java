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
import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Result;
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
import net.dreamlu.mica.ai.ppocr.utils.CollUtil;
import org.springframework.context.ApplicationContext;

import java.io.File;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.*;

/**
 * PP-OCR 结构化识别模板。
 *
 * <p>持有 {@link PPOcrV6Engine} 与若干 {@link BaseStructuredParser} 实现，对外提供：
 * <ul>
 *   <li>{@link #run(String)} / {@link #run(File)} / {@link #run(Path)} / {@link #run(byte[])} /
 *       {@link #run(InputStream)} —— 纯 OCR 识别，返回散落文字框列表；</li>
 *   <li>{@link #vehicleLicense()} / {@link #idCard()} / {@link #bankCard()} /
 *       {@link #driverLicense()} / {@link #businessLicense()} / {@link #invoice()} /
 *       {@link #trainTicket()} / {@link #taxiReceipt()} / {@link #householdRegister()} /
 *       {@link #pddLuckyBag()} ——
 *       获取 10 类内置解析器，每个解析器已绑定 engine，自带 5 种入参的 {@code parse(...)} 重载；</li>
 *   <li>{@link #get(Class)} —— 通用查表入口，自定义解析器或不想加 getter 时使用。</li>
 * </ul>
 *
 * <p>内部按 {@code parser.getClass()} 建索引，相同类型重复注册以首次为准（{@link LinkedHashMap} 保序）。
 *
 * <p>典型用法（Spring Boot 上传）：
 * <pre>
 * &#64;Autowired
 * private PPOcrTemplate ppocr;
 *
 * &#64;PostMapping("/vehicle")
 * public VehicleLicenseResult recognize(&#64;RequestParam("file") MultipartFile file) throws IOException {
 *     return ppocr.vehicleLicense().parse(file.getBytes());
 * }
 *
 * // 自定义解析器或不想加 getter 的场景
 * MyCustomResult r = ppocr.get(MyCustomParser.class).parse(bytes);
 * </pre>
 *
 * <p>本类不接管 {@link PPOcrV6Engine} 的生命周期：
 * Spring 场景下由容器管理 engine 的关闭；非 Spring 场景由调用方自行关闭 engine。
 */
public final class PPOcrTemplate {

	private final ApplicationContext context;
	private final PPOcrV6Engine engine;
	private final Map<Class<?>, BaseStructuredParser<?>> parsers;

	/**
	 * 构造模板，传入已初始化的推理引擎与若干结构化解析器。
	 *
	 * <p>相同类型的解析器重复注册时，仅保留首次出现的实例。
	 *
	 * @param context Spring 应用上下文（不为 null）
	 * @param engine  PP-OCRv6 推理引擎（不为 null）
	 * @throws IllegalArgumentException engine 为 null、parsers 为 null 或空、元素为 null
	 */
	public PPOcrTemplate(ApplicationContext context, PPOcrV6Engine engine) {
		this.context = Objects.requireNonNull(context, "ApplicationContext must not be null");
		this.engine = Objects.requireNonNull(engine, "PPOcrV6Engine must not be null");
		Map<Class<?>, BaseStructuredParser<?>> map = new LinkedHashMap<>();
		for (BaseStructuredParser<?> parser : context.getBeansOfType(BaseStructuredParser.class).values()) {
			if (parser == null) {
				throw new IllegalArgumentException("parser must not be null");
			} else {
				// 相同类型重复注册，以首次为准
				map.putIfAbsent(parser.getClass(), parser);
			}
		}
		this.parsers = Collections.unmodifiableMap(map);
	}

	// ==================================================================
	// 纯 OCR（非结构化）：返回散落文字框列表
	// ==================================================================

	/**
	 * 纯 OCR 识别：检测 → 排序 → 裁剪 → 识别。
	 *
	 * @param imagePath 图片路径
	 * @return 识别结果列表（按阅读顺序排列）
	 */
	public List<PPOcrV6Result> run(String imagePath) {
		if (imagePath == null || imagePath.isEmpty()) {
			throw new IllegalArgumentException("imagePath must not be empty");
		}
		return run(CollUtil.pathOf(imagePath));
	}

	/**
	 * 纯 OCR 识别：检测 → 排序 → 裁剪 → 识别。
	 *
	 * @param imageFile 图片文件
	 * @return 识别结果列表（按阅读顺序排列）
	 */
	public List<PPOcrV6Result> run(File imageFile) {
		if (imageFile == null) {
			throw new IllegalArgumentException("imageFile must not be null");
		}
		return run(imageFile.toPath());
	}

	/**
	 * 纯 OCR 识别：检测 → 排序 → 裁剪 → 识别。
	 *
	 * <p>兼容非默认文件系统（如 ZIP / JIMFS / 内存 FS）：优先走 native 文件读取，
	 * 不支持的 FileSystem 自动退回 {@code Files.readAllBytes}。
	 *
	 * @param imagePath 图片路径
	 * @return 识别结果列表（按阅读顺序排列）
	 * @throws UncheckedIOException 读取字节时发生 IO 异常
	 */
	public List<PPOcrV6Result> run(Path imagePath) {
		return engine.run(imagePath);
	}

	/**
	 * 纯 OCR 识别：检测 → 排序 → 裁剪 → 识别。
	 *
	 * <p>PDF 解析失败时由 engine 内部包为 {@link java.io.UncheckedIOException} 抛出。
	 *
	 * @param imgBytes 图片或 PDF 字节
	 * @return 识别结果列表（按阅读顺序排列，PDF 多页平铺）
	 */
	public List<PPOcrV6Result> run(byte[] imgBytes) {
		return engine.run(imgBytes);
	}

	/**
	 * 纯 OCR 识别：检测 → 排序 → 裁剪 → 识别。
	 *
	 * <p>内部读取全部流为 byte[] 后调用 {@code engine.run(byte[])}。
	 * 流由调用方负责关闭（{@code CollUtil.readAllBytes(InputStream)} 会读到 EOF 但不 close）。
	 *
	 * <p>流读取失败时包为 {@link java.io.UncheckedIOException} 抛出。
	 *
	 * @param in 图片或 PDF 输入流
	 * @return 识别结果列表（按阅读顺序排列，PDF 多页平铺）
	 */
	public List<PPOcrV6Result> run(InputStream in) {
		if (in == null) {
			throw new IllegalArgumentException("InputStream must not be null");
		}
		try {
			return engine.run(CollUtil.readAllBytes(in));
		} catch (java.io.IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
	}

	// ==================================================================
	// 结构化解析器：每个解析器自带 5 种入参的 parse(...) 重载
	// ==================================================================

	/**
	 * 通用查表入口：按 {@code Class} 取出已注册的解析器。
	 *
	 * <p>类型不匹配时直接抛 {@link ClassCastException}（由 {@link Class#cast(Object)} 触发）；
	 * 未注册时抛 {@link IllegalArgumentException}。
	 *
	 * @param type 解析器类型（不为 null）
	 * @param <T>  解析器类型参数
	 * @return 已绑定的解析器实例
	 * @throws IllegalArgumentException 未注册该类型
	 */
	public <T extends BaseStructuredParser<?>> T get(Class<T> type) {
		if (type == null) {
			throw new IllegalArgumentException("type must not be null");
		}
		BaseStructuredParser<?> parser = parsers.get(type);
		if (parser == null) {
			throw new IllegalArgumentException("No parser registered: " + type.getName());
		}
		return type.cast(parser);
	}

	/**
	 * 获取行驶证结构化解析器。
	 *
	 * @return 行驶证解析器实例（已绑定当前 engine）
	 */
	public VehicleLicenseParser vehicleLicense() {
		return get(VehicleLicenseParser.class);
	}

	/**
	 * 获取身份证结构化解析器（正反面自动判定）。
	 *
	 * @return 身份证解析器实例（已绑定当前 engine）
	 */
	public IdCardParser idCard() {
		return get(IdCardParser.class);
	}

	/**
	 * 获取银行卡结构化解析器。
	 *
	 * @return 银行卡解析器实例（已绑定当前 engine）
	 */
	public BankCardParser bankCard() {
		return get(BankCardParser.class);
	}

	/**
	 * 获取驾驶证结构化解析器。
	 *
	 * @return 驾驶证解析器实例（已绑定当前 engine）
	 */
	public DriverLicenseParser driverLicense() {
		return get(DriverLicenseParser.class);
	}

	/**
	 * 获取营业执照结构化解析器。
	 *
	 * @return 营业执照解析器实例（已绑定当前 engine）
	 */
	public BusinessLicenseParser businessLicense() {
		return get(BusinessLicenseParser.class);
	}

	/**
	 * 获取增值税发票结构化解析器。
	 *
	 * @return 发票解析器实例（已绑定当前 engine）
	 */
	public InvoiceParser invoice() {
		return get(InvoiceParser.class);
	}

	/**
	 * 获取火车票结构化解析器。
	 *
	 * @return 火车票解析器实例（已绑定当前 engine）
	 */
	public TrainTicketParser trainTicket() {
		return get(TrainTicketParser.class);
	}

	/**
	 * 获取出租车票结构化解析器。
	 *
	 * @return 出租车票解析器实例（已绑定当前 engine）
	 */
	public TaxiReceiptParser taxiReceipt() {
		return get(TaxiReceiptParser.class);
	}

	/**
	 * 获取户口本结构化解析器。
	 *
	 * @return 户口本解析器实例（已绑定当前 engine）
	 */
	public HouseholdRegisterParser householdRegister() {
		return get(HouseholdRegisterParser.class);
	}

	/**
	 * 获取拼多多福袋结构化解析器。
	 *
	 * @return 拼多多福袋解析器实例（已绑定当前 engine）
	 */
	public PddLuckyBagParser pddLuckyBag() {
		return get(PddLuckyBagParser.class);
	}
}
