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

import net.dreamlu.mica.ai.ppocr.utils.PdfMagicDetector;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * 图片输入加载器：core 内置默认实现，把 {@link OcrInput} 中的图片字节 / 路径 / 流
 * 解析为单条 {@link Page}（{@code index=0}）。
 *
 * <p>故意不嗅探图片魔数（PNG / JPG / BMP ...）——OpenCV 的 imdecode
 * 自身能正确识别所有支持的位图格式，错误时由引擎 {@code run(byte[])} 抛
 * 明确异常（"Failed to decode image ..."）。这里只对 PDF 做"拒绝"判定：
 * 若嗅探到 PDF 魔数，明确告诉调用方走 {@link net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Engine#runPdf(byte[])}
 * 而不是默默把 PDF 字节当图片解码。
 *
 * <p>优先级 -100（兜底），保证未来扩展的 TIFF / Word 等专用 loader 优先匹配。
 *
 * <h3>线程安全</h3>
 * 无状态，可单例共享。
 */
public class ImageInputLoader implements InputLoader {

	@Override
	public boolean canLoad(OcrInput input) {
		if (input == null) {
			return false;
		}
		// PDF 不在图片通道处理范围：嗅探到 PDF 字节就拒绝，
		// 引导调用方走 engine.runPdf(...) 入口。
		if (input.source() == OcrInput.Source.BYTES && PdfMagicDetector.isPdf(input.bytes())) {
			return false;
		}
		return input.kind() == OcrInput.Kind.IMAGE;
	}

	@Override
	public List<Page> load(OcrInput input, LoaderContext context) throws IOException {
		// 图片：单页，把字节原样透传（引擎 run(byte[]) 会用 OpenCV 解码）
		byte[] bytes = OcrResources.toBytes(input);
		return Collections.singletonList(new Page(0, bytes));
	}

	@Override
	public int priority() {
		return -100;
	}
}
