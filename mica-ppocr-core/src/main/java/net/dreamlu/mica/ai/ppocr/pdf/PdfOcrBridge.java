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

package net.dreamlu.mica.ai.ppocr.pdf;

import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Result;
import net.dreamlu.mica.ai.ppocr.postprocessor.DbDetParams;
import org.opencv.core.Mat;

import java.util.List;

/**
 * PDF 渲染通道的 OCR 回调：把渲染出来的页面位图交回引擎，走完整 OCR 链路。
 *
 * <p><b>为什么需要这个接口</b>：PDFBox 的渲染能力在 {@link PdfRuntime} 里，
 * 而完整的 OCR 链路（检测 → 排序 → 裁剪 → 识别）在引擎里。
 * 两者用本接口解耦后，引擎就不必在任何签名里出现 PDFBox 类型 ——
 * 这是「PDF 依赖可选、缺 PDFBox 时引擎照样能跑图片 OCR」的前提。
 *
 * <p>实现方只需把渲染出的页面图交给 {@code PPOcrV6Engine#runMat(Mat, DbDetParams)}。
 *
 * @author L.cm
 */
@FunctionalInterface
public interface PdfOcrBridge {

	/**
	 * 对渲染出的页面图执行完整 OCR 流程。
	 *
	 * @param bgrMat    BGR 格式页面图 (H, W, 3) uint8，由调用方负责 release
	 * @param detParams DB 参数；{@code null} 表示使用引擎默认
	 * @return 该页 OCR 结果（按阅读顺序排列）
	 */
	List<PPOcrV6Result> ocr(Mat bgrMat, DbDetParams detParams);
}
