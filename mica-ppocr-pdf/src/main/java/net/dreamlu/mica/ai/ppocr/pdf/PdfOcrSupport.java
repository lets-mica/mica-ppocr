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

import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Engine;
import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Result;
import net.dreamlu.mica.ai.ppocr.utils.CollUtil;
import nu.pattern.OpenCV;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.opencv.core.CvType;
import org.opencv.core.Mat;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * PDF 双通道 OCR 入口（门面）：每页独立做"文本层 vs 渲染"分流。
 *
 * <p>这是 mica-ppocr-pdf 的推荐入口。背后是两条路径：
 * <ol>
 *   <li>文本层通道：{@link PdfTextExtractor} 直接抽 PDF 内嵌文本（字符 100% 无损，
 *       score=1.0），跳过引擎推理；</li>
 *   <li>渲染通道：按 {@link PdfOcrConfig#getRenderDpi()} 渲染为 BGR Mat，
 *       调 {@link PPOcrV6Engine#runMat(Mat)} 走完整 OCR 链路。</li>
 * </ol>
 *
 * <p>为什么不直接走 {@link PdfInputLoader}（SPI）？因为文本层产物是
 * {@code List<PPOcrV6Result>} 而非位图，不能复用 engine 单一"按页解码 OCR"流水线。
 * 本门面承担"每页单独选择通道 + 拼接结果"职责，引擎侧保持形状统一。
 *
 * <h3>线程安全</h3>
 * 实例无状态，engine 的线程安全由其自身保证，可单例共享。
 *
 * <h3>Mat 生命周期</h3>
 * 渲染通道创建的 Mat 由本类自行 release；{@code engine.runMat} 不持有 Mat 引用。
 */
public class PdfOcrSupport {
	private final PPOcrV6Engine engine;
	private final PdfOcrConfig config;
	private final PdfTextExtractor extractor = new PdfTextExtractor();

	/**
	 * 构造 PDF OCR 支持（默认配置）。
	 *
	 * @param engine 推理引擎；可为 null（仅当所有页面文本层可用，
	 *               一旦需要 OCR 通道将抛 IllegalStateException）
	 */
	public PdfOcrSupport(PPOcrV6Engine engine) {
		this(engine, PdfOcrConfig.defaults());
	}

	/**
	 * 构造 PDF OCR 支持。
	 *
	 * @param engine 推理引擎
	 * @param config 双通道配置，不可为 null
	 */
	public PdfOcrSupport(PPOcrV6Engine engine, PdfOcrConfig config) {
		if (config == null) {
			throw new IllegalArgumentException("config must not be null");
		}
		this.engine = engine;
		this.config = config;
	}

	/**
	 * 魔数嗅探：字节流是否为 PDF。
	 *
	 * @param bytes 待检字节流
	 * @return true 表示 PDF
	 * @deprecated 改用 {@link net.dreamlu.mica.ai.ppocr.utils.PdfMagicDetector#isPdf(byte[])}；
	 *             本方法保留仅为兼容既有调用方。
	 */
	@Deprecated
	public static boolean isPdf(byte[] bytes) {
		return net.dreamlu.mica.ai.ppocr.utils.PdfMagicDetector.isPdf(bytes);
	}

	/**
	 * 双通道解析 PDF 文件。
	 *
	 * @param pdfPath PDF 文件路径
	 * @return 按页结果列表
	 */
	public List<PdfPageResult> run(String pdfPath) throws IOException {
		if (pdfPath == null || pdfPath.isEmpty()) {
			throw new IllegalArgumentException("pdfPath must not be empty");
		}
		return run(CollUtil.pathOf(pdfPath));
	}

	/**
	 * 双通道解析 PDF 文件。
	 *
	 * @param pdfFile PDF 文件
	 * @return 按页结果列表
	 */
	public List<PdfPageResult> run(File pdfFile) throws IOException {
		if (pdfFile == null) {
			throw new IllegalArgumentException("pdfFile must not be null");
		}
		return run(pdfFile.toPath());
	}

	/**
	 * 双通道解析 PDF 文件。
	 *
	 * @param pdfPath PDF 文件路径
	 * @return 按页结果列表
	 */
	public List<PdfPageResult> run(Path pdfPath) throws IOException {
		if (pdfPath == null) {
			throw new IllegalArgumentException("pdfPath must not be null");
		}
		return run(Files.readAllBytes(pdfPath));
	}

	/**
	 * 双通道解析 PDF 字节流。
	 *
	 * @param pdfBytes PDF 字节流
	 * @return 按页结果列表
	 */
	public List<PdfPageResult> run(byte[] pdfBytes) throws IOException {
		if (pdfBytes == null || pdfBytes.length == 0) {
			throw new IllegalArgumentException("pdfBytes must not be empty");
		}
		// 委托给统一 OcrInput 入口；engine == null 时文本层命中不报错，渲染通道才报错
		// SPI 路径会因 engine==null 在 runPages 抛错；这里直接拿字节走双通道
		if (!net.dreamlu.mica.ai.ppocr.utils.PdfMagicDetector.isPdf(pdfBytes)) {
			throw new IllegalArgumentException(
				"input bytes are not a PDF (missing %PDF- magic); use PPOcrV6Engine.run(...) for images");
		}
		try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
			return runPerPage(doc);
		}
	}

	/**
	 * 双通道解析 PDF 输入流（内部读取全部字节，不负责关闭流）。
	 *
	 * @param in PDF 输入流
	 * @return 按页结果列表
	 */
	public List<PdfPageResult> run(InputStream in) throws IOException {
		if (in == null) {
			throw new IllegalArgumentException("InputStream must not be null");
		}
		return run(CollUtil.readAllBytes(in));
	}

	private List<PdfPageResult> runPerPage(PDDocument doc) throws IOException {
		int pageCount = doc.getNumberOfPages();
		List<PdfPageResult> pages = new ArrayList<>(pageCount);
		PDFRenderer renderer = null;
		for (int i = 0; i < pageCount; i++) {
			if (config.isForceOcr()) {
				renderer = ensureRenderer(doc, renderer);
				pages.add(ocrPage(i, renderer));
				continue;
			}
			List<PPOcrV6Result> textResults = extractor.extract(doc, i);
			PdfTextQuality quality = extractor.quality(textResults);
			if (quality.usable(config.getMinTextChars(), config.getMinReadableRatio())) {
				pages.add(new PdfPageResult(i, false, textResults));
			} else {
				renderer = ensureRenderer(doc, renderer);
				pages.add(ocrPage(i, renderer));
			}
		}
		return pages;
	}

	private static PDFRenderer ensureRenderer(PDDocument doc, PDFRenderer renderer) {
		return renderer != null ? renderer : new PDFRenderer(doc);
	}

	/**
	 * 渲染通道：按配置 DPI 渲染页面为 BGR Mat，走完整 OCR 链路。
	 */
	private PdfPageResult ocrPage(int pageIndex, PDFRenderer renderer) throws IOException {
		if (engine == null) {
			throw new IllegalStateException(
				"page " + pageIndex + " has no usable text layer and requires OCR, "
					+ "but PPOcrV6Engine is null; construct PdfOcrSupport with an engine");
		}
		// openpnp OpenCV 本地库幂等加载（starter/solon 已被 OpenCVNativeLoader 提前加载，此处为非容器兜底）
		OpenCV.loadLocally();
		BufferedImage image = renderer.renderImageWithDPI(pageIndex, config.getRenderDpi(), ImageType.RGB);
		Mat mat = toBgrMat(image);
		List<PPOcrV6Result> results;
		try {
			results = engine.runMat(mat);
		} finally {
			mat.release();
		}
		return new PdfPageResult(pageIndex, true, results);
	}

	/**
	 * BufferedImage → BGR Mat：TYPE_3BYTE_BGR 的 raster 字节序天然为 BGR，
	 * 直接整块 put，不逐像素 getRGB。
	 */
	private static Mat toBgrMat(BufferedImage src) {
		int width = src.getWidth();
		int height = src.getHeight();
		BufferedImage bgr = src;
		if (src.getType() != BufferedImage.TYPE_3BYTE_BGR) {
			bgr = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
			Graphics2D g = bgr.createGraphics();
			try {
				g.drawImage(src, 0, 0, null);
			} finally {
				g.dispose();
			}
		}
		byte[] data = ((DataBufferByte) bgr.getRaster().getDataBuffer()).getData();
		Mat mat = new Mat(height, width, CvType.CV_8UC3);
		mat.put(0, 0, data);
		return mat;
	}

	// ==================================================================
	// 路径 / 字节工具
	// ==================================================================
}
