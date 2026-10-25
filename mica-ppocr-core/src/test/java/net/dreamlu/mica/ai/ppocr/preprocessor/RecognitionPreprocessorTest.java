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

package net.dreamlu.mica.ai.ppocr.preprocessor;

import net.dreamlu.mica.ai.ppocr.utils.CollUtil;
import net.dreamlu.mica.ai.ppocr.utils.NdArrayUtils;
import org.junit.jupiter.api.Test;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RecognitionPreprocessor 单元测试。
 *
 * <p>核心是 bit-exact 回归：流式归一化实现必须与旧实现（CV_32F 中间 Mat + HWC flat 数组）
 * 逐位相同 —— 优化只允许改内存占用，不允许改数值。
 */
class RecognitionPreprocessorTest {

	@Test
	void constructor_defaults() {
		RecognitionPreprocessor pre = new RecognitionPreprocessor();
		assertNotNull(pre);
	}

	@Test
	void constructor_invalidParams() {
		assertThrows(IllegalArgumentException.class, () -> new RecognitionPreprocessor(0, 320, 3200));
		assertThrows(IllegalArgumentException.class, () -> new RecognitionPreprocessor(48, 0, 3200));
		assertThrows(IllegalArgumentException.class, () -> new RecognitionPreprocessor(48, 320, 0));
		assertThrows(IllegalArgumentException.class, () -> new RecognitionPreprocessor(48, 3200, 320));
		// 负数宽度同样非法
		assertThrows(IllegalArgumentException.class, () -> new RecognitionPreprocessor(48, -1, 3200));
	}

	@Test
	void call_emptyList() {
		nu.pattern.OpenCV.loadLocally();
		RecognitionPreprocessor pre = new RecognitionPreprocessor();
		RecognitionPreprocessor.Result r = pre.call(CollUtil.listOf());
		assertEquals(0, r.data().length);
		assertArrayEquals(new int[]{0, 3, 48, 0}, r.shape());
	}

	// ==================================================================
	// bit-exact 回归：流式实现必须与旧实现逐位相同
	// ==================================================================

	/**
	 * 旧实现（materialize HWC flat + CV_32F 中间 Mat）作为参考实现。
	 *
	 * <p>保留在测试里作为「数值真相源」。
	 */
	private static RecognitionPreprocessor.Result referenceCall(List<Mat> imgs, int h, int wMin, int wMax) {
		int n = imgs.size();
		if (n == 0) {
			return new RecognitionPreprocessor.Result(new float[0], new int[]{0, 3, h, 0});
		}

		List<float[]> perImgHwc = new ArrayList<>(n);
		int[] widths = new int[n];
		int[] actualWs = new int[n];
		for (int i = 0; i < n; i++) {
			Mat img = imgs.get(i);
			int srcH = img.rows();
			int srcW = img.cols();
			double whRatio = Math.max((double) wMin / h, (double) srcW / srcH);
			int targetW = (int) (h * whRatio);

			int actualW;
			Mat resized = new Mat();
			try {
				if (targetW > wMax) {
					Imgproc.resize(img, resized, new Size(wMax, h), 0, 0, Imgproc.INTER_LINEAR);
					actualW = wMax;
					targetW = wMax;
				} else {
					actualW = Math.min(NdArrayUtils.ceilDiv(h * srcW, srcH), targetW);
					Imgproc.resize(img, resized, new Size(actualW, h), 0, 0, Imgproc.INTER_LINEAR);
				}
				widths[i] = targetW;
				actualWs[i] = actualW;

				Mat f = NdArrayUtils.toFloat32(resized);
				float[] hwc;
				try {
					hwc = NdArrayUtils.matToFlatHwc(f);
				} finally {
					f.release();
				}
				int nPix = hwc.length;
				for (int k = 0; k < nPix; k++) {
					hwc[k] = hwc[k] / 127.5f - 1.0f;
				}
				perImgHwc.add(hwc);
			} finally {
				resized.release();
			}
		}

		int maxW = 0;
		for (int w : widths) {
			if (w > maxW) maxW = w;
		}
		int totalSize = n * 3 * h * maxW;
		float[] data = new float[totalSize];
		int chwSize = 3 * h * maxW;
		for (int i = 0; i < n; i++) {
			int actualW = actualWs[i];
			int destBase = i * chwSize;
			float[] hwc = perImgHwc.get(i);
			for (int c = 0; c < 3; c++) {
				int cOffset = destBase + c * h * maxW;
				for (int row = 0; row < h; row++) {
					int srcRowBase = row * actualW * 3 + c;
					int dstRowBase = cOffset + row * maxW;
					for (int col = 0; col < actualW; col++) {
						data[dstRowBase + col] = hwc[srcRowBase + col * 3];
					}
				}
			}
		}
		return new RecognitionPreprocessor.Result(data, new int[]{n, 3, h, maxW});
	}

	private static Mat randomBgr(int h, int w, long seed) {
		Random rnd = new Random(seed);
		Mat img = new Mat(h, w, CvType.CV_8UC3);
		for (int r = 0; r < h; r++) {
			for (int c = 0; c < w; c++) {
				img.put(r, c, new double[]{
					rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)
				});
			}
		}
		return img;
	}

	@Test
	void call_bitExact_mixedWidths() {
		nu.pattern.OpenCV.loadLocally();
		// 宽度各异，触发不同的 targetW / actualW（含 actualW < targetW 的 padding 场景）
		int[][] sizes = {{48, 48}, {48, 120}, {30, 400}, {64, 64}, {48, 200}};
		List<Mat> imgs = new ArrayList<>();
		List<Mat> owned = new ArrayList<>();
		try {
			for (int i = 0; i < sizes.length; i++) {
				Mat m = randomBgr(sizes[i][0], sizes[i][1], 1000L + i);
				imgs.add(m);
				owned.add(m);
			}

			RecognitionPreprocessor pre = new RecognitionPreprocessor();
			RecognitionPreprocessor.Result actual = pre.call(imgs);
			RecognitionPreprocessor.Result expected = referenceCall(imgs, 48, 320, 3200);

			assertArrayEquals(expected.shape(), actual.shape(), "shape 必须一致");
			assertArrayEquals(expected.data(), actual.data(),
				"流式归一化必须与旧实现逐位相同（bit-exact）");
		} finally {
			for (Mat m : owned) {
				m.release();
			}
		}
	}

	@Test
	void call_bitExact_afterResize() {
		nu.pattern.OpenCV.loadLocally();
		// 大图：srcW/srcH 远大于 wMin/h，走 resize 分支
		Mat img = randomBgr(80, 600, 4242L);
		List<Mat> imgs = new ArrayList<>();
		imgs.add(img);
		try {
			RecognitionPreprocessor pre = new RecognitionPreprocessor();
			RecognitionPreprocessor.Result actual = pre.call(imgs);
			RecognitionPreprocessor.Result expected = referenceCall(imgs, 48, 320, 3200);

			assertArrayEquals(expected.shape(), actual.shape());
			assertArrayEquals(expected.data(), actual.data(),
				"resize 之后仍须与旧实现逐位相同");
		} finally {
			img.release();
		}
	}

	@Test
	void call_wMaxClamp_bitExact() {
		nu.pattern.OpenCV.loadLocally();
		// 超宽图：targetW = 48 * (5000/48) = 5000 > wMax=3200 → 走 wMax 截断分支
		Mat img = randomBgr(48, 5000, 777L);
		List<Mat> imgs = new ArrayList<>();
		imgs.add(img);
		try {
			RecognitionPreprocessor pre = new RecognitionPreprocessor();
			RecognitionPreprocessor.Result actual = pre.call(imgs);
			RecognitionPreprocessor.Result expected = referenceCall(imgs, 48, 320, 3200);

			assertArrayEquals(new int[]{1, 3, 48, 3200}, actual.shape(),
				"超宽图应被截断到 wMax=3200");
			assertArrayEquals(expected.data(), actual.data(),
				"wMax 截断分支仍须与旧实现逐位相同");
		} finally {
			img.release();
		}
	}

	@Test
	void call_nonContinuousMat_readsCorrectly() {
		nu.pattern.OpenCV.loadLocally();
		int h = 48;
		int w = 96;
		Mat base = randomBgr(h, w, 31337L);
		// 窄 ROI：行 stride（w*3）> ROI 宽度（w/2*3）→ OpenCV 判定非连续
		Mat roi = new Mat(base, new org.opencv.core.Rect(0, 0, w / 2, h / 2));
		List<Mat> imgs = new ArrayList<>();
		imgs.add(roi);
		try {
			assertFalse(roi.isContinuous(), "窄 ROI 应为非连续，用于覆盖 clone 分支");
			RecognitionPreprocessor pre = new RecognitionPreprocessor();
			RecognitionPreprocessor.Result actual = pre.call(imgs);
			RecognitionPreprocessor.Result expected = referenceCall(imgs, 48, 320, 3200);

			assertArrayEquals(expected.shape(), actual.shape());
			assertArrayEquals(expected.data(), actual.data(),
				"非连续 Mat 走 clone 分支后仍须逐位相同");
		} finally {
			roi.release();
			base.release();
		}
	}

	@Test
	void call_paddingRegionIsZero() {
		nu.pattern.OpenCV.loadLocally();
		// srcW=srcH=48：targetW=320（wMin 下限），actualW=48 → 右侧 272 列应为 padding 0
		Mat img = randomBgr(48, 48, 99L);
		List<Mat> imgs = new ArrayList<>();
		imgs.add(img);
		try {
			RecognitionPreprocessor pre = new RecognitionPreprocessor();
			RecognitionPreprocessor.Result r = pre.call(imgs);
			int maxW = r.shape()[3];
			assertEquals(320, maxW, "逻辑宽度应取 wMin=320");
			int chwSize = 3 * 48 * maxW;
			for (int c = 0; c < 3; c++) {
				for (int row = 0; row < 48; row++) {
					for (int col = 48; col < maxW; col++) {
						assertEquals(0f, r.data()[c * 48 * maxW + row * maxW + col],
							"padding 区必须为 0");
					}
				}
			}
			assertEquals(chwSize, r.data().length);
		} finally {
			img.release();
		}
	}
}
