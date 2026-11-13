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
import net.dreamlu.mica.ai.ppocr.utils.BufferedImageUtils;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.opencv.core.Mat;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * PDF 双通道运行时：<b>本项目内唯一直接触碰 PDFBox API 的类</b>。
 *
 * <p>职责：加载 PDF 文档 → 逐页分流（文本层坐标抽取 / 渲染兜底 OCR）→ 产出 per-page 结果。
 *
 * <p><b>为什么必须与引擎分开</b>：PDFBox 是<b>可选依赖</b>（只在需要处理 PDF 输入时引入）。
 * 若引擎的字节码里出现 {@code Loader} / {@code PDDocument} / {@code PDFRenderer}，
 * 类链接阶段的字节码校验就可能连带触发 PDFBox 类的加载 ——
 * 缺依赖时连「构造一个只跑图片的引擎」都会失败。
 * 因此引擎侧只通过 {@code PdfRuntimeHolder}（延迟到真正要用时才链接）间接调用本类。
 *
 * <p><b>调用前提</b>：{@link PdfDependency#isAvailable()} 必须为 {@code true}。
 *
 * <p>无状态可复用（内部 {@link PdfTextExtractor} 线程安全），建议单例使用。
 *
 * <p><b>坐标单位</b>：两条通道产出的框最终统一为<b>渲染图像素坐标</b> ——
 * 文本层的 point 坐标经 {@link PdfPageGeometry} 做「页面旋转 → DPI 缩放」换算，
 * 渲染通道的框本就是渲染位图的像素坐标。调用方拿到任一页的框，
 * 都能直接对照「同一页、同一 {@code renderDpi} 的渲染图」定位。
 *
 * @author L.cm
 */
public class PdfRuntime {

	/**
	 * PDF 文本层抽取器（无状态，线程安全，复用）。
	 */
	private final PdfTextExtractor textExtractor = new PdfTextExtractor();

	/**
	 * PDF 双通道解析（核心入口）。
	 *
	 * <p>按 {@link PdfOcrConfig} 配置：每页先尝试文本层坐标抽取，
	 * 文本质量不达标时降级到渲染 + OCR。
	 *
	 * @param pdfBytes PDF 字节
	 * @param config   PDF 配置（不可为 null）
	 * @param detParams DB 参数；{@code null} 表示使用引擎默认
	 * @param bridge   渲染通道的 OCR 回调（不可为 null）
	 * @return per-page 结果列表
	 * @throws IOException PDF 解析或渲染失败
	 */
	public List<PdfPageResult> run(byte[] pdfBytes, PdfOcrConfig config, DbDetParams detParams,
								   PdfOcrBridge bridge) throws IOException {
		if (config == null) {
			throw new IllegalArgumentException("config must not be null");
		}
		if (pdfBytes == null || pdfBytes.length == 0) {
			throw new IllegalArgumentException("pdfBytes must not be empty");
		}
		if (bridge == null) {
			throw new IllegalArgumentException("bridge must not be null");
		}
		try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
			return runPages(doc, config, detParams, bridge);
		}
	}

	/**
	 * PDF 双通道解析（核心入口，<b>文件路径版</b>）。
	 *
	 * <p>走 {@link Loader#loadPDF(java.io.File)}：PDFBox 按需从磁盘随机读，
	 * <b>整份 PDF 不进 JVM 堆</b> —— 字节版（{@code Loader.loadPDF(byte[])}）会把整个 PDF
	 * 作为随机访问缓冲常驻堆直到文档关闭，扫描件 PDF 动辄几十 MB。
	 *
	 * <p>⚠️ <b>行为变更</b>：PDFBox 会持有该文件的读句柄直到 {@link PDDocument} 关闭 ——
	 * Windows 上处理期间该文件不能被删除 / 改名。
	 *
	 * @param pdfFile   PDF 文件（不可为 null，且必须是已存在的常规文件）
	 * @param config    PDF 配置（不可为 null）
	 * @param detParams DB 参数；{@code null} 表示使用引擎默认
	 * @param bridge    渲染通道的 OCR 回调（不可为 null）
	 * @return per-page 结果列表
	 * @throws IOException PDF 解析或渲染失败
	 */
	public List<PdfPageResult> run(File pdfFile, PdfOcrConfig config, DbDetParams detParams,
									PdfOcrBridge bridge) throws IOException {
		if (config == null) {
			throw new IllegalArgumentException("config must not be null");
		}
		if (pdfFile == null || !pdfFile.isFile()) {
			throw new IllegalArgumentException("pdfFile must be an existing file");
		}
		if (bridge == null) {
			throw new IllegalArgumentException("bridge must not be null");
		}
		try (PDDocument doc = Loader.loadPDF(pdfFile)) {
			return runPages(doc, config, detParams, bridge);
		}
	}

	/**
	 * 按 {@code config} 遍历所有页：文本层质量达标时直接走 {@link PdfTextExtractor}，
	 * 否则降级到渲染 + OCR。{@code forceOcr=true} 时跳过文本层抽取，全走 OCR。
	 *
	 * <p>文本层抽出的坐标单位是 PDF point，渲染通道的坐标单位是渲染位图像素，
	 * 两者在进入结果列表前统一换算到<b>渲染图像素坐标</b>（见 {@link #toRenderedPixels}），
	 * 避免同一份 PDF 里两种通道产出两套坐标系的框。
	 *
	 * @param doc       已加载的 PDF 文档（不可为 null）
	 * @param config    PDF 配置（不可为 null）
	 * @param detParams DB 参数；{@code null} 表示使用引擎默认
	 * @param bridge    渲染通道的 OCR 回调（不可为 null）
	 * @return per-page 结果列表
	 * @throws IOException 渲染失败
	 */
	private List<PdfPageResult> runPages(PDDocument doc, PdfOcrConfig config, DbDetParams detParams,
										 PdfOcrBridge bridge) throws IOException {
		int pageCount = doc.getNumberOfPages();
		List<PdfPageResult> pages = new ArrayList<>(pageCount);
		PDFRenderer renderer = null;
		for (int i = 0; i < pageCount; i++) {
			if (config.isForceOcr()) {
				renderer = ensureRenderer(doc, renderer);
				pages.add(ocrPage(i, renderer, config, detParams, bridge));
				continue;
			}
			List<PPOcrV6Result> textResults = textExtractor.extract(doc, i);
			PdfTextQuality quality = textExtractor.quality(textResults);
			if (quality.usable(config.getMinTextChars(), config.getMinReadableRatio())) {
				pages.add(new PdfPageResult(i, false, toRenderedPixels(doc.getPage(i), textResults, config.getRenderDpi())));
			} else {
				renderer = ensureRenderer(doc, renderer);
				pages.add(ocrPage(i, renderer, config, detParams, bridge));
			}
		}
		return pages;
	}

	/**
	 * 文本层 point 坐标 → 渲染图像素坐标。
	 *
	 * <p>文本层走 {@code TextPosition.getXDirAdj/getYDirAdj}，单位是 point 且不含页面旋转；
	 * 渲染通道的框是渲染位图（裁剪框按 {@code renderDpi} 缩放并按 {@code /Rotate} 旋转）的像素坐标。
	 * 此处按 {@link PdfPageGeometry} 做「旋转 → 缩放」换算，两条通道的框因此落在同一坐标系。
	 *
	 * @param page      PDF 页面（不可为 null）
	 * @param results   文本层抽取结果（不可为 null）
	 * @param renderDpi 渲染 DPI
	 * @return 像素坐标下的结果列表
	 */
	private static List<PPOcrV6Result> toRenderedPixels(PDPage page, List<PPOcrV6Result> results, int renderDpi) {
		PDRectangle cropBox = page.getCropBox();
		PdfPageGeometry geometry = PdfPageGeometry.of(
			cropBox.getWidth(), cropBox.getHeight(), page.getRotation(), renderDpi);
		List<PPOcrV6Result> mapped = new ArrayList<>(results.size());
		for (PPOcrV6Result result : results) {
			mapped.add(new PPOcrV6Result(result.text(), result.score(),
				geometry.toPixelBox(result.box()), result.rotatedDegrees()));
		}
		return mapped;
	}

	/**
	 * 惰性构造 {@link PDFRenderer}：同一文档只需创建一个 renderer 即可复用，避免重复绑定底层资源。
	 *
	 * @param doc      PDF 文档
	 * @param renderer 已有 renderer（可为空）
	 * @return 已存在或新创建的 renderer
	 */
	private static PDFRenderer ensureRenderer(PDDocument doc, PDFRenderer renderer) {
		return renderer != null ? renderer : new PDFRenderer(doc);
	}

	/**
	 * 渲染通道：按配置 DPI 渲染页面为 BGR Mat，走完整 OCR 链路。
	 *
	 * @param pageIndex 0-based 页索引
	 * @param renderer  PDF 渲染器（不可为 null）
	 * @param config    PDF 配置（不可为 null）
	 * @param detParams DB 参数；{@code null} 表示使用引擎默认
	 * @param bridge    渲染通道的 OCR 回调（不可为 null）
	 * @return 该页 OCR 结果，{@code viaOcr=true}
	 * @throws IOException 渲染失败
	 */
	private PdfPageResult ocrPage(int pageIndex, PDFRenderer renderer, PdfOcrConfig config,
								  DbDetParams detParams, PdfOcrBridge bridge) throws IOException {
		// OpenCV 原生库已在引擎构造期由 ensureOpenCvLoaded() 加载（幂等），此处无需再加载
		BufferedImage image = renderer.renderImageWithDPI(pageIndex, config.getRenderDpi(), ImageType.RGB);
		Mat mat = BufferedImageUtils.toBgrMat(image);
		List<PPOcrV6Result> results;
		try {
			results = bridge.ocr(mat, detParams);
		} finally {
			mat.release();
		}
		return new PdfPageResult(pageIndex, true, results);
	}
}
