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

package net.dreamlu.mica.ai.ppocr.structured.parser.invoice;

import net.dreamlu.mica.ai.ppocr.config.PPOcrV6Config;
import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Engine;
import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Result;
import net.dreamlu.mica.ai.ppocr.utils.PdfMagicDetector;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 发票结构化解析调试入口。
 *
 * <p>替换 {@link #INPUT_PATH} 为待调试的发票图片或 PDF 路径，运行 main 即可输出 OCR 框 + 结构化字段。
 *
 * <p>自动识别：
 * <ul>
 *   <li>图片（jpg/png/bmp）：走传统单图 OCR + 可视化框图</li>
 *   <li>PDF：走引擎内置双通道（文本层 + 兜底 OCR），多页平铺输出</li>
 * </ul>
 */
public class InvoiceMain {

	private static final String INPUT_PATH = "test_images/invoice/invoice1.jpg";
	private static final String VIS_PATH = "test_images/invoice/vis.png";

	public static void main(String[] args) throws IOException {
		nu.pattern.OpenCV.loadLocally();
		PPOcrV6Config config = PPOcrV6Config.builder()
			.detModelPath("models/ppocr-v6/tiny/det.onnx")
			.recModelPath("models/ppocr-v6/tiny/rec.onnx")
			.recCharDictPath("models/ppocr-v6/tiny/dict.txt")
			.useDocOrientationClassify(true)
			.docOrientationModelPath("models/ppocr-v6/doc_ori/doc_ori.onnx")
			.build();

		Path inputPath = Paths.get(INPUT_PATH);
		if (!Files.exists(inputPath)) {
			System.err.println("输入文件不存在: " + inputPath.toAbsolutePath());
			return;
		}

		try (PPOcrV6Engine engine = new PPOcrV6Engine(config)) {
			InvoiceParser dispatcher = new InvoiceParser(engine);

			// 优先嗅探头部字节判定 PDF：避免 Imgcodecs.imread 把 PDF 当图片读时返回 null
			byte[] head = readHead(inputPath);
			boolean isPdf = PdfMagicDetector.isPdf(head);

			if (isPdf) {
				runPdf(engine, dispatcher, inputPath);
			} else {
				runImage(engine, dispatcher, inputPath);
			}
		}
	}

	private static void runImage(PPOcrV6Engine engine, InvoiceParser dispatcher, Path inputPath) {
		Mat img = Imgcodecs.imread(inputPath.toString());
		if (img.empty()) {
			System.err.println("无法读取图片: " + inputPath);
			return;
		}
		try {
			List<PPOcrV6Result> results = engine.runMat(img);
			printOcrResults(results);
			System.out.println("\n--- 结构化解析 ---");
			printResult(dispatcher.parseResults(results));
			saveVis(img, results, Paths.get(VIS_PATH));
		} finally {
			img.release();
		}
	}

	private static void runPdf(PPOcrV6Engine engine, InvoiceParser dispatcher, Path inputPath) throws IOException {
		System.out.println("检测到 PDF 输入，走 PDF 双通道（文本层优先 + OCR 兜底）。");
		List<PPOcrV6Result> results = engine.run(inputPath);
		printOcrResults(results);
		System.out.println("\n--- 结构化解析（PDF 多页已平铺） ---");
		printResult(dispatcher.parseResults(results));
	}

	private static byte[] readHead(Path path) throws IOException {
		try {
			byte[] buf = new byte[1024 + 8];
			int n = 0;
			try (java.io.InputStream in = Files.newInputStream(path)) {
				while (n < buf.length) {
					int read = in.read(buf, n, buf.length - n);
					if (read < 0) {
						break;
					}
					n += read;
				}
			}
			if (n == buf.length) {
				return buf;
			}
			byte[] out = new byte[n];
			System.arraycopy(buf, 0, out, 0, n);
			return out;
		} catch (IOException e) {
			return null;
		}
	}

	private static void printOcrResults(List<PPOcrV6Result> results) {
		System.out.println("Detected " + results.size() + " text regions:\n");
		for (PPOcrV6Result r : results) {
			int[][] b = r.box();
			System.out.printf("  text=\"%s\"  score=%.6f  box=[(%d,%d),(%d,%d)]%n",
				r.text(), r.score(), b[0][0], b[0][1], b[2][0], b[2][1]);
		}
	}

	private static void printResult(InvoiceResult inv) {
		System.out.println("发票代码       " + inv.getInvoiceCode());
		System.out.println("发票号码       " + inv.getInvoiceNo());
		System.out.println("开票日期       " + inv.getInvoiceDate());
		System.out.println();
		System.out.println("--- 购买方 ---");
		System.out.println("名称           " + inv.getBuyerName());
		System.out.println("税号           " + inv.getBuyerTaxNo());
		System.out.println("地址电话       " + inv.getBuyerAddressPhone());
		System.out.println("开户行账号     " + inv.getBuyerBankAccount());
		System.out.println();
		System.out.println("--- 销售方 ---");
		System.out.println("名称           " + inv.getSellerName());
		System.out.println("税号           " + inv.getSellerTaxNo());
		System.out.println("地址电话       " + inv.getSellerAddressPhone());
		System.out.println("开户行账号     " + inv.getSellerBankAccount());
		System.out.println();
		System.out.println("--- 明细 ---");
		for (InvoiceItem item : inv.getItems()) {
			System.out.println("商品/服务名称  " + item.getGoodsName());
			System.out.println("金额           " + item.getAmount());
			System.out.println("税率           " + item.getTaxRate());
			System.out.println("税额           " + item.getTaxAmount());
		}
		System.out.println();
		System.out.println("--- 合计 ---");
		System.out.println("价税合计(大写) " + inv.getTotalAmountUpper());
		System.out.println("价税合计(小写) " + inv.getTotalAmountLower());
		System.out.println();
		System.out.println("--- 底栏 ---");
		System.out.println("收款人         " + inv.getPayee());
		System.out.println("复核人         " + inv.getReviewer());
		System.out.println("开票人         " + inv.getIssuer());
	}

	private static void saveVis(Mat img, List<PPOcrV6Result> results, Path out) {
		Mat canvas = img.clone();
		int imgW = img.cols();
		int imgH = img.rows();
		for (PPOcrV6Result r : results) {
			int[][] box = r.boxInOriginalImg(imgW, imgH);
			Point[] pts = new Point[4];
			for (int i = 0; i < 4; i++) {
				pts[i] = new Point(box[i][0], box[i][1]);
			}
			MatOfPoint mop = new MatOfPoint(pts);
			List<MatOfPoint> list = new ArrayList<>();
			list.add(mop);
			Imgproc.polylines(canvas, list, true, new Scalar(0, 255, 0), 2);
		}
		boolean ok = Imgcodecs.imwrite(out.toString(), canvas);
		System.out.println(ok ? "\nVisualization saved: " + out : "Warning: failed to save visualization: " + out);
		canvas.release();
	}
}
