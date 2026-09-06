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

package net.dreamlu.mica.ai.ppocr.postprocessor;

import lombok.Value;
import lombok.experimental.Accessors;

/**
 * 按调用覆盖的 DB 后处理参数。
 *
 * <p>用于解决{@link DbPostProcessor}字段在引擎构造期固定后无法按调用动态调整的问题
 * （如证件反光/弱对比场景需要临时放宽 det 阈值）。线程安全：不可变值对象，
 * 引擎内部按调用临时构造 {@link DbPostProcessor}，不修改任何共享状态。
 *
 * <p>用法见 {@link net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Engine#detectMat} 的双参重载。
 *
 * <p>默认推荐值与 {@code PPOcrV6Config.detThresh/detBoxThresh/detUnclipRatio} 默认值一致
 * （0.3 / 0.6 / 1.5），便于使用方从引擎默认配置直接取值再按需调整。
 */
@Value
@Accessors(fluent = true)
public class DbDetParams {

	/**
	 * DB 二值化阈值（0~1）。
	 *
	 * <p>调小可召回更多弱文本，调大可减少误检。默认 0.3。
	 */
	float thresh;

	/**
	 * 文本框平均分数阈值（0~1）。
	 *
	 * <p>低于此分数的框会被丢弃。默认 0.6。
	 */
	float boxThresh;

	/**
	 * 文本框扩张系数。
	 *
	 * <p>通过 {@code Offset.unclipDistance} 把框外扩。调大让框更宽松覆盖完整字符，
	 * 调小收紧以贴合字形。默认 1.5。
	 */
	float unclipRatio;

	private DbDetParams(float thresh, float boxThresh, float unclipRatio) {
		this.thresh = thresh;
		this.boxThresh = boxThresh;
		this.unclipRatio = unclipRatio;
	}

	/**
	 * 构造 DB 参数。
	 *
	 * @param thresh      二值化阈值
	 * @param boxThresh   文本框分数阈值
	 * @param unclipRatio 扩张系数
	 * @return 不可变参数对象
	 */
	public static DbDetParams of(float thresh, float boxThresh, float unclipRatio) {
		return new DbDetParams(thresh, boxThresh, unclipRatio);
	}
}
