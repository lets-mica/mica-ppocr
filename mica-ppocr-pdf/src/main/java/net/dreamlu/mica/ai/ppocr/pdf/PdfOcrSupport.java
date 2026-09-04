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
 * PDF 双通道 OCR 入口：文字型 PDF 直接抽文本层坐标（字符无损），扫描件渲染位图走 OCR 兜底。
 *
 * <p>处理流程（每页独立分流）：
 * <ol>
 *   <li>{@code %PDF-} 魔数嗅探（容忍 header 前最多 1024 字节垃圾数据，PDF 规范 7.5.2）；</li>
 *   <li>文本层探测：抽取页面文本并评分（{@link PdfTextQuality}），字符数与可读占比
 *       达标 → <strong>文本层通道</strong>，直接产出与 OCR 同构的文本框（score = 1.0，
 *       字符 100% 无损，零推理开销）；</li>
 *   <li>不达标（扫描件 / 缺 ToUnicode 的 CID 字体 / Type3 字形 / 整页图片）→
 *       <strong>渲染通道</strong>：PDFBox 按 {@link PdfOcrConfig#getRenderDpi()} 渲染成
 *       BGR 位图，走 {@code PPOcrV6Engine.runMat(Mat)} 完整 OCR 链路。</li>
 * </ol>
 *
 * <p>返回按页组织的 {@link PdfPageResult}（带 pageIndex 与 viaOcr 标注），
 * {@code results()} 与 {@code PPOcrV6Engine.run(...)} 元素同构，可直接喂给
 * mica-ppocr-structured 的 {@code parseResults(List)} 复用结构化解析层。
 *
 * <p>与官方 PaddleOCR pipeline 的差异：官方一律"渲染 + OCR"（文档型 PDF 也先光栅化），
 * 本类对文字型 PDF 走文本层捷径——这正是发票类场景（数电票 / 电子发票 PDF 为
 * 税控系统生成的矢量文字型）精度与性能的最优解。
 *
 * <h3>线程安全</h3>
 * 实例无状态（engine 的线程安全性由其自身保证），可单例共享。
 *
 * <h3>Mat 生命周期</h3>
 * 渲染通道创建的 Mat 由本类自行 release；{@code engine.runMat} 不持有 Mat 引用。
 */
public class PdfOcrSupport {

	/**
	 * PDF 魔数：{@code %PDF-}。
	 */
	private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F', '-'};
	/**
	 * 魔数嗅探窗口：PDF 规范允许 header 前存在最多 1024 字节的垃圾数据。
	 */
	private static final int MAGIC_WINDOW = 1024;

	private final PPOcrV6Engine engine;
	private final PdfOcrConfig config;
	private final PdfTextExtractor extractor = new PdfTextExtractor();

	/**
	 * 构造 PDF OCR 支持（默认配置）。
	 *
	 * @param engine 推理引擎，供渲染通道兜底；可为 null（仅当所有页面文本层可用，
	 *               一旦需要 OCR 通道将抛 IllegalStateException）
	 */
	public PdfOcrSupport(PPOcrV6Engine engine) {
		this(engine, PdfOcrConfig.defaults());
	}

	/**
	 * 构造 PDF OCR 支持。
	 *
	 * @param engine 推理引擎，供渲染通道兜底；可为 null（语义同上）
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
	 * <p>在前 1024 字节窗口内查找 {@code %PDF-}（PDF 规范允许 header 前有垃圾数据）。
	 *
	 * @param bytes 待检字节流
	 * @return true 表示 PDF
	 */
	public static boolean isPdf(byte[] bytes) {
		if (bytes == null || bytes.length < PDF_MAGIC.length) {
			return false;
		}
		int limit = Math.min(bytes.length - PDF_MAGIC.length, MAGIC_WINDOW);
		for (int offset = 0; offset <= limit; offset++) {
			boolean match = true;
			for (int i = 0; i < PDF_MAGIC.length; i++) {
				if (bytes[offset + i] != PDF_MAGIC[i]) {
					match = false;
					break;
				}
			}
			if (match) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 双通道解析 PDF 文件。
	 *
	 * @param pdfPath PDF 文件路径
	 * @return 按页结果列表（页序 = 文档页序，pageIndex 从 0 开始）
	 * @throws IllegalArgumentException 路径为空
	 * @throws IOException              读取文件或解析 PDF 失败
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
	 * @throws IllegalArgumentException 文件为 null
	 * @throws IOException              读取文件或解析 PDF 失败
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
	 * @throws IllegalArgumentException 路径为 null
	 * @throws IOException              读取文件或解析 PDF 失败
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
	 * <p>典型场景：Spring Boot 上传 {@code MultipartFile.getBytes()}。
	 *
	 * @param pdfBytes PDF 字节流
	 * @return 按页结果列表
	 * @throws IllegalArgumentException 字节流为空，或不是 PDF（魔数嗅探失败）
	 * @throws IOException              解析 PDF 失败
	 */
	public List<PdfPageResult> run(byte[] pdfBytes) throws IOException {
		if (pdfBytes == null || pdfBytes.length == 0) {
			throw new IllegalArgumentException("pdfBytes must not be empty");
		}
		if (!isPdf(pdfBytes)) {
			throw new IllegalArgumentException(
				"input bytes are not a PDF (missing %PDF- magic); use PPOcrV6Engine.run(...) for images");
		}
		try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
			return run(doc);
		}
	}

	/**
	 * 双通道解析 PDF 输入流（内部读取全部字节，不负责关闭流）。
	 *
	 * @param in PDF 输入流
	 * @return 按页结果列表
	 * @throws IllegalArgumentException 流为 null
	 * @throws IOException              读取流或解析 PDF 失败
	 */
	public List<PdfPageResult> run(InputStream in) throws IOException {
		if (in == null) {
			throw new IllegalArgumentException("InputStream must not be null");
		}
		return run(CollUtil.readAllBytes(in));
	}

	private List<PdfPageResult> run(PDDocument doc) throws IOException {
		int pageCount = doc.getNumberOfPages();
		List<PdfPageResult> pages = new ArrayList<>(pageCount);
		// 渲染器懒创建：全部页面文本层可用时零开销
		PDFRenderer renderer = null;
		for (int i = 0; i < pageCount; i++) {
			if (config.isForceOcr()) {
				pages.add(ocrPage(doc, i, lazyRenderer(doc, renderer)));
				continue;
			}
			List<PPOcrV6Result> textResults = extractor.extract(doc, i);
			PdfTextQuality quality = extractor.quality(textResults);
			if (quality.usable(config.getMinTextChars(), config.getMinReadableRatio())) {
				pages.add(new PdfPageResult(i, false, textResults));
			} else {
				renderer = lazyRenderer(doc, renderer);
				pages.add(ocrPage(doc, i, renderer));
			}
		}
		return pages;
	}

	private static PDFRenderer lazyRenderer(PDDocument doc, PDFRenderer renderer) {
		return renderer != null ? renderer : new PDFRenderer(doc);
	}

	/**
	 * 渲染通道：按配置 DPI 渲染页面为 BGR 位图，走完整 OCR 链路。
	 */
	private PdfPageResult ocrPage(PDDocument doc, int pageIndex, PDFRenderer renderer) throws IOException {
		if (engine == null) {
			throw new IllegalStateException(
				"page " + pageIndex + " has no usable text layer and requires OCR, "
					+ "but PPOcrV6Engine is null; construct PdfOcrSupport with an engine");
		}
		// openpnp OpenCV 本地库幂等加载（starter/solon 场景已被 OpenCVNativeLoader 提前加载，此处为非容器兜底）
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
}
