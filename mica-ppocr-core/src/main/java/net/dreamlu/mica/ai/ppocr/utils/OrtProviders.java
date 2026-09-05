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

package net.dreamlu.mica.ai.ppocr.utils;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtProvider;
import ai.onnxruntime.OrtSession.SessionOptions;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import net.dreamlu.mica.ai.ppocr.config.PPOcrV6Config;

import java.util.*;

/**
 * ONNX Runtime execution provider 自动选择 + 注册。
 *
 * <ul>
 *   <li>preferCpu=true → 强制使用 CPUExecutionProvider（保证跨平台 bit-exact 精度）</li>
 *   <li>preferCpu=false → 按 CoreML (macOS) > CUDA > CPU 自动选择</li>
 * </ul>
 *
 * <p>{@link #apply(SessionOptions, PPOcrV6Config)} 负责把 provider + 全部 SessionOptions
 * 通用配置（arena / memory pattern / exec mode / 线程数）一起应用到 {@link SessionOptions}，
 * 任何一步失败都只打 warn 并保留 ORT 默认值，调用方无需 try/catch。
 */
@Slf4j
@UtilityClass
public class OrtProviders {

	/**
	 * 把 checked OrtException 转成 warn 日志的 setter 包装。
	 * 抽象方法允许 throws OrtException，调用方写 lambda 不必显式 catch。
	 */
	@FunctionalInterface
	private interface OrtSetter {
		void apply(SessionOptions opts) throws OrtException;
	}

	/**
	 * provider 名称 → 注册到 SessionOptions 的动作（deviceId 固定传 0）。
	 *
	 * <p>抽象方法允许 throws OrtException，调用方在 try/catch 内统一处理。
	 */
	@FunctionalInterface
	private interface EpRegistrar {
		void register(SessionOptions opts) throws OrtException;
	}

	/**
	 * provider 名称 → 注册器。CPU 不需要额外注册，跳过；
	 * 新增 EP（如 TensorRT / DirectML）时只在这里加一行。
	 */
	private final Map<String, EpRegistrar> REGISTRARS = new HashMap<>();

	static {
		REGISTRARS.put("CUDAExecutionProvider", opts -> opts.addCUDA(0));
		REGISTRARS.put("CoreMLExecutionProvider", SessionOptions::addCoreML);
	}

	/**
	 * 解析并应用 ONNX Runtime provider + SessionOptions 全部配置。
	 *
	 * @param opts   会话配置（由调用方负责生命周期）
	 * @param config 引擎配置
	 */
	public static void apply(SessionOptions opts, PPOcrV6Config config) {
		String[] providers = resolve(!config.isPreferAccelerator());
		// 1. 通用配置：4 个 setter，任意失败保留 ORT 默认
		safeSet("CPU memory arena", opts, o -> o.setCPUArenaAllocator(config.isEnableCpuMemArena()));
		safeSet("memory pattern", opts, o -> o.setMemoryPatternOptimization(config.isEnableMemoryPattern()));
		safeSet("execution mode", opts, o -> o.setExecutionMode(config.getExecMode()));
		safeSet("thread count", opts, o -> {
			o.setIntraOpNumThreads(Math.max(1, config.getIntraOpNumThreads()));
			o.setInterOpNumThreads(Math.max(1, config.getInterOpNumThreads()));
		});
		// 2. 加速 provider 注册：缺 CUDA/CuDNN 库时回退 CPU，session 仍可创建
		EpRegistrar registrar = REGISTRARS.get(providers[0]);
		if (registrar != null) {
			try {
				registrar.register(opts);
				log.info("ONNX Runtime 已注册: {} (device=0)", providers[0]);
			} catch (OrtException e) {
				log.warn("注册 {} 失败，回退 CPU: {}", providers[0], e.getMessage());
			}
		}
	}

	/**
	 * 根据策略解析要启用的 ONNX Runtime provider 名称列表（仅解析，不注册）。
	 *
	 * @param preferCpu true 强制 CPU；false 自动选择加速器
	 * @return ONNX Runtime provider 名称列表
	 */
	public static String[] resolve(boolean preferCpu) {
		if (preferCpu) {
			log.info("ONNX Runtime provider: CPUExecutionProvider (forced)");
			return new String[]{"CPUExecutionProvider"};
		}
		EnumSet<OrtProvider> available;
		try {
			available = OrtEnvironment.getAvailableProviders();
		} catch (Exception e) {
			log.warn("无法枚举 ONNX Runtime providers, 回退到 CPU: {}", e.getMessage());
			return new String[]{"CPUExecutionProvider"};
		}
		List<String> availableNames = new ArrayList<>(available.size());
		for (OrtProvider p : available) {
			availableNames.add(p.getName());
		}
		for (String preferred : CollUtil.listOf("CoreMLExecutionProvider", "CUDAExecutionProvider")) {
			if (availableNames.contains(preferred)) {
				log.info("ONNX Runtime provider: {}", preferred);
				return new String[]{preferred};
			}
		}
		log.info("ONNX Runtime provider: CPUExecutionProvider (fallback)");
		return new String[]{"CPUExecutionProvider"};
	}

	private static void safeSet(String label, SessionOptions opts, OrtSetter setter) {
		try {
			setter.apply(opts);
		} catch (OrtException e) {
			log.warn("设置 {} 失败，使用默认值: {}", label, e.getMessage());
		}
	}
}
