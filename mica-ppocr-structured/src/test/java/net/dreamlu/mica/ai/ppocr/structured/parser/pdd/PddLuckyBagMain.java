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

package net.dreamlu.mica.ai.ppocr.structured.parser.pdd;

import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Engine;
import net.dreamlu.mica.ai.ppocr.postprocessor.DbDetParams;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.BaseTest;

/**
 * 拼多多福袋结构化解析调试入口。
 *
 * <p>替换 {@link #IMAGE_PATH} 为待调试的拼多多福袋图片。
 */
public class PddLuckyBagMain extends BaseTest<PddLuckyBagParser, PddLuckyBagResult> {

	private static final String IMAGE_PATH = "test_images/pdd/pdd1.jpg";
	private static final String VIS_PATH = "test_images/pdd/vis.png";

	public static void main(String[] args) {
		new PddLuckyBagMain().demo(IMAGE_PATH, VIS_PATH);
	}

	/**
	 * 福袋海报属于「大字号稀疏数字 + 背景水印」版面，
	 * 需使用 {@link PddLuckyBagParser#DET_PARAMS} 才能检出福袋码。
	 *
	 * @return 海报场景的 DB 检测参数
	 */
	@Override
	protected DbDetParams detParams() {
		return PddLuckyBagParser.DET_PARAMS;
	}

	@Override
	protected PddLuckyBagParser newParser(PPOcrV6Engine engine) {
		return new PddLuckyBagParser(engine);
	}

	@Override
	protected void printResult(PddLuckyBagResult r) {
		System.out.println("luckyBagCode: " + r.getLuckyBagCode());
		PddLuckyBagType type = r.getType();
		System.out.println("type:         " + type + " / " + (type == null ? "" : type.getDesc()));
	}
}
