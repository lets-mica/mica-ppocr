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

package net.dreamlu.mica.ai.ppocr.demo;

import net.dreamlu.mica.ai.ppocr.config.PPOcrV6Config;
import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Engine;
import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Result;
import nu.pattern.OpenCV;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 纯 OCR 调试 demo：直接运行 main 即可打印识别结果 + 框坐标 + 保存可视化图片。
 *
 * <p>用法：
 * <pre>{@code
 * // 默认：跑 models/ppocr-v6/tiny + test_images/vehicle/vehicle1.png
 * mvn -pl mica-ppocr-core test-compile
 * java -cp mica-ppocr-core/target/test-classes:mica-ppocr-core/target/classes:... \
 *      net.dreamlu.mica.ai.ppocr.demo.OcrMain
 *
 * // 自定义：传图片路径
 * java ... net.dreamlu.mica.ai.ppocr.demo.OcrMain test_images/idcard/idcard-front.png
 *
 * // 自定义：图片 + 可视化输出路径
 * java ... net.dreamlu.mica.ai.ppocr.demo.OcrMain <image> <vis.png> [tier]
 * }</pre>
 *
 * <p>参数：
 * <ol>
 *   <li>args[0] —— 图片路径（默认 {@code test_images/vehicle/vehicle1.png}）</li>
 *   <li>args[1] —— 可视化 PNG 输出路径（默认 {@code <image-stem>.vis.png}）</li>
 *   <li>args[2] —— 模型档位 tiny / small / medium（默认 {@code tiny}）</li>
 * </ol>
 *
 * <p>依赖模型目录：
 * <pre>{@code
 * models/ppocr-v6/
 *   ├── tiny/{det,rec,dict}
 *   ├── small/{det,rec,dict}
 *   └── medium/{det,rec,dict}
 *   └── doc_ori/doc_ori.onnx
 * }</pre>
 */
public class OcrMain {
	private static final String DEFAULT_IMAGE = "test_images/vehicle/vehicle1.png";
	private static final String DEFAULT_TIER = "tiny";

	public static void main(String[] args) {
		String imagePath = args.length >= 1 ? args[0] : DEFAULT_IMAGE;
		String visPath = args.length >= 2 ? args[1] : defaultVisPath(imagePath);
		String tier = args.length >= 3 ? args[2] : DEFAULT_TIER;

		// openpnp OpenCV 本地库加载（首次调用幂等）
		OpenCV.loadLocally();

		System.out.println("=== PP-OCRv6 Demo ===");
		System.out.println("Image:  " + imagePath);
		System.out.println("Tier:   " + tier);
		System.out.println("Vis:    " + visPath);

		// 读取图片（保持原图坐标系，方便 box 直接落到画布上）
		Mat img = Imgcodecs.imread(imagePath);
		if (img.empty()) {
			System.err.println("Error: cannot read image: " + imagePath);
			System.exit(1);
		}
		System.out.println("Size:   " + img.cols() + "x" + img.rows());

		// 加载模型
		String detModel = "models/ppocr-v6/" + tier + "/det.onnx";
		String recModel = "models/ppocr-v6/" + tier + "/rec.onnx";
		String dict = "models/ppocr-v6/" + tier + "/dict.txt";
		String docOriModel = "models/ppocr-v6/doc_ori/doc_ori.onnx";

		Path detFile = Paths.get(detModel);
		if (!Files.isRegularFile(detFile)) {
			System.err.println("Error: 缺少模型文件: " + detFile.toAbsolutePath());
			System.err.println("请先把模型放到 models/ppocr-v6/" + tier + "/ 下，或通过 args[2] 指定档位。");
			img.release();
			System.exit(1);
		}

		PPOcrV6Config config = PPOcrV6Config.builder()
			.detModelPath(detModel)
			.recModelPath(recModel)
			.recCharDictPath(dict)
			.detBoxThresh(0.5f)
			.useDocOrientationClassify(true)
			.docOrientationModelPath(docOriModel)
			.build();

		long t0 = System.currentTimeMillis();
		List<PPOcrV6Result> results;
		try (PPOcrV6Engine engine = new PPOcrV6Engine(config)) {
			results = engine.runMat(img);
		}
		long elapsed = System.currentTimeMillis() - t0;

		System.out.println();
		System.out.println("Elapsed: " + elapsed + " ms");
		System.out.println("Detected " + results.size() + " text regions:");
		System.out.println();
		int imgW = img.cols();
		int imgH = img.rows();
		for (int i = 0; i < results.size(); i++) {
			PPOcrV6Result r = results.get(i);
			// 如果启用了 doc_ori，需要把 box 投影回原图坐标系再可视化
			int[][] box = r.boxInOriginalImg(imgW, imgH);
			System.out.printf("  [%2d] text=%-30s  score=%.4f  box=[(%4d,%4d),(%4d,%4d),(%4d,%4d),(%4d,%4d)]  rot=%d°%n",
				i + 1, "\"" + r.text() + "\"", r.score(),
				box[0][0], box[0][1], box[1][0], box[1][1],
				box[2][0], box[2][1], box[3][0], box[3][1],
				r.rotatedDegrees());
		}

		saveVis(img, results, visPath);

		img.release();
	}

	/**
	 * 默认可视化输出路径：与原图同目录，文件名加 {@code .vis.png} 后缀。
	 */
	private static String defaultVisPath(String imagePath) {
		int dot = imagePath.lastIndexOf('.');
		int slash = Math.max(imagePath.lastIndexOf('/'), imagePath.lastIndexOf('\\'));
		String stem = dot > slash ? imagePath.substring(0, dot) : imagePath;
		return stem + ".vis.png";
	}

	/**
	 * 在原图上绘制所有检测框（绿色多边形），保存为 PNG。
	 */
	private static void saveVis(Mat img, List<PPOcrV6Result> results, String outPath) {
		Mat canvas = img.clone();
		try {
			int imgW = img.cols();
			int imgH = img.rows();
			List<MatOfPoint> contours = new ArrayList<>(results.size());
			try {
				for (PPOcrV6Result r : results) {
					int[][] box = r.boxInOriginalImg(imgW, imgH);
					Point[] pts = new Point[4];
					for (int i = 0; i < 4; i++) {
						pts[i] = new Point(box[i][0], box[i][1]);
					}
					contours.add(new MatOfPoint(pts));
				}
				Imgproc.polylines(canvas, contours, true, new Scalar(0, 255, 0), 2);
			} finally {
				for (MatOfPoint m : contours) {
					m.release();
				}
			}
			boolean ok = Imgcodecs.imwrite(outPath, canvas);
			if (ok) {
				System.out.println();
				System.out.println("Visualization saved: " + outPath);
			} else {
				System.err.println("Warning: failed to save visualization: " + outPath);
			}
		} finally {
			canvas.release();
		}
	}
}
