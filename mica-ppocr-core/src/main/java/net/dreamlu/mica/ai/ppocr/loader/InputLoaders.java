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

package net.dreamlu.mica.ai.ppocr.loader;

import lombok.experimental.UtilityClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * {@link InputLoader} 解析器：缓存 {@link ServiceLoader} 命中的所有实现，
 * 按 {@link InputLoader#priority()} 升序排序，供引擎挑选首个 {@code canLoad=true} 的。
 *
 * <h3>线程安全</h3>
 * {@link #loaders()} 返回的列表是不可变副本；引擎单例可安全跨线程持有。
 *
 * <h3>失败容忍</h3>
 * {@link ServiceConfigurationError}（如某实现类缺失）会按 ServiceLoader 默认行为抛出；
 * 这里在初始化时记录警告但不中断，方便测试 / 部分环境降级。
 */
@UtilityClass
public class InputLoaders {

	private static final Logger log = LoggerFactory.getLogger(InputLoaders.class);

	private static volatile List<InputLoader> cached;

	/**
	 * 加载 classpath 下所有 {@link InputLoader}，按 {@code priority} 升序排序。
	 *
	 * <p>结果会被全局缓存；用户自定义 loader 不会热加载进来。
	 *
	 * @return 加载器列表（不可变）
	 */
	public static List<InputLoader> loaders() {
		List<InputLoader> snapshot = cached;
		if (snapshot != null) {
			return snapshot;
		}
		synchronized (InputLoaders.class) {
			snapshot = cached;
			if (snapshot != null) {
				return snapshot;
			}
			List<InputLoader> found = new ArrayList<>();
			try {
				ServiceLoader<InputLoader> sl = ServiceLoader.load(InputLoader.class);
				for (InputLoader loader : sl) {
					found.add(loader);
				}
			} catch (ServiceConfigurationError e) {
				log.warn("加载 InputLoader SPI 失败: {}", e.getMessage());
			}
			found.sort(Comparator.comparingInt(InputLoader::priority));
			List<InputLoader> unmodifiable = java.util.Collections.unmodifiableList(found);
			cached = unmodifiable;
			return unmodifiable;
		}
	}

	/**
	 * 找到首个 {@link InputLoader#canLoad(OcrInput)} 返回 true 的加载器。
	 *
	 * @param input 输入
	 * @return 命中的 loader；找不到时返回 null
	 */
	public static InputLoader find(OcrInput input) {
		for (InputLoader loader : loaders()) {
			if (loader.canLoad(input)) {
				return loader;
			}
		}
		return null;
	}

	/**
	 * 重置缓存（仅测试用）。
	 */
	static void resetForTest() {
		synchronized (InputLoaders.class) {
			cached = null;
		}
	}
}
