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
import net.dreamlu.mica.ai.ppocr.loader.InputLoader;
import net.dreamlu.mica.ai.ppocr.loader.LoaderContext;
import net.dreamlu.mica.ai.ppocr.loader.OcrInput;
import net.dreamlu.mica.ai.ppocr.loader.OcrResources;
import net.dreamlu.mica.ai.ppocr.loader.Page;
import net.dreamlu.mica.auto.annotation.AutoService;
import nu.pattern.OpenCV;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.imgcodecs.Imgcodecs;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PDF 输入加载器（SPI 实现）：把 PDF 解析为"可直接喂给 OCR 引擎"的页字节列表。
 *
 * <p>对 PDF 来说，文本层（{@link PdfTextExtractor}）的产物不是位图而是
 * {@code List<PPOcrV6Result>}——是结构化解析层的输入，不是 engine 再识别的输入。
 * 因此本 loader 走"渲染 + OCR"统一通道：每页都按
 * {@link PdfOcrConfig#getRenderDpi()} 渲染成 BGR Mat，再编码为 JPEG 字节透传给引擎。
 *
 * <p>对文字型 PDF 而言，绕开文本层确实多了一次 OCR 开销，但避免了 SPI 形状不一致
 * （loader 输出结果列表 vs. 引擎输出结果列表）。文本层通道仍然存在，由
 * {@link PdfOcrSupport} 门面直接调 {@link PdfTextExtractor}，跳过 engine 完整链路。
 *
 * <h3>双通道策略</h3>
 * <ul>
 *   <li>{@link #load(OcrInput, LoaderContext)}：走"渲染 + OCR"全页统一通道（最通用）。</li>
 *   <li>{@link PdfOcrSupport#run(byte[])}：保留双通道分流，文本层命中时直接返回
 *       {@link PdfTextExtractor} 的结果，渲染通道走 engine.runPages。门面是
 *       业务方首选入口。</li>
 * </ul>
 *
 * <p>不持有自身状态（PDFRenderer / Mat 在 load 内创建并 release），无线程安全问题。
 *
 * <p>优先级 0（默认），与 {@link net.dreamlu.mica.ai.ppocr.loader.ImageInputLoader}
 * 的 -100 形成顺序：PDF 在前，图片兜底在后。
 */
@AutoService(InputLoader.class)
public class PdfInputLoader implements InputLoader {

	private final PdfOcrConfig config;

	public PdfInputLoader() {
		this(PdfOcrConfig.defaults());
	}

	public PdfInputLoader(PdfOcrConfig config) {
		if (config == null) {
			throw new IllegalArgumentException("config must not be null");
		}
		this.config = config;
	}

	@Override
	public boolean canLoad(OcrInput input) {
		if (input == null || input.kind() != OcrInput.Kind.PDF) {
			return false;
		}
		if (input.source() == OcrInput.Source.BYTES) {
			return net.dreamlu.mica.ai.ppocr.utils.PdfMagicDetector.isPdf(input.bytes());
		}
		return true;
	}

	@Override
	public List<Page> load(OcrInput input, LoaderContext context) throws IOException {
		byte[] pdfBytes = OcrResources.toBytes(input);
		try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
			int pageCount = doc.getNumberOfPages();
			List<Page> pages = new ArrayList<>(pageCount);
			PDFRenderer renderer = null;
			for (int i = 0; i < pageCount; i++) {
				renderer = ensureRenderer(doc, renderer);
				pages.add(renderPage(i, renderer));
			}
			return pages;
		}
	}

	private static PDFRenderer ensureRenderer(PDDocument doc, PDFRenderer renderer) {
		return renderer != null ? renderer : new PDFRenderer(doc);
	}

	/**
	 * 渲染通道：按配置 DPI 渲染页面为 BGR Mat，再编码为 JPEG 字节。
	 */
	private Page renderPage(int pageIndex, PDFRenderer renderer) throws IOException {
		OpenCV.loadLocally();
		BufferedImage image = renderer.renderImageWithDPI(pageIndex, config.getRenderDpi(), ImageType.RGB);
		Mat mat = toBgrMat(image);
		try {
			MatOfByte mob = new MatOfByte();
			try {
				Imgcodecs.imencode(".jpg", mat, mob);
				Map<String, Object> meta = new HashMap<>();
				meta.put("viaOcr", Boolean.TRUE);
				meta.put("sourceDpi", config.getRenderDpi());
				return new Page(pageIndex, mob.toArray(), meta);
			} finally {
				mob.release();
			}
		} finally {
			mat.release();
		}
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

	/**
	 * 直接拿到引擎的便捷方法（供门面内文本层通道之外的"纯渲染"用例使用）。
	 *
	 * @param engine 引擎
	 * @param input  PDF 输入
	 * @return 引擎的逐页结果
	 * @throws IOException IO 异常
	 */
	public static List<PPOcrV6Result> runAll(PPOcrV6Engine engine, OcrInput input) throws IOException {
		List<Page> pages = new PdfInputLoader().load(input, new LoaderContext(engine, null, new HashMap<>()));
		List<PPOcrV6Result> all = new ArrayList<>();
		for (Page p : pages) {
			MatOfByte mob = new MatOfByte(p.bytes());
			Mat mat;
			try {
				mat = Imgcodecs.imdecode(mob, Imgcodecs.IMREAD_COLOR);
			} finally {
				mob.release();
			}
			if (mat.empty()) {
				mat.release();
				continue;
			}
			try {
				all.addAll(engine.runMat(mat));
			} finally {
				mat.release();
			}
		}
		return all;
	}
}
