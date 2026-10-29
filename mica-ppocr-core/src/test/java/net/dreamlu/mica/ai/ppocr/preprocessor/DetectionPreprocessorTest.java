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

import net.dreamlu.mica.ai.ppocr.utils.NdArrayUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DetectionPreprocessor 单元测试。
 *
 * <p>resize 对齐规则：Math.round(value / 32) * 32，下限 32。
 */
class DetectionPreprocessorTest {

	/**
	 * 本测试直接使用 {@code org.opencv.*}（不经过 {@code PPOcrV6Engine}），
	 * 引擎的构造期自动加载管不到这里，需自行加载 native 库。
	 */
	@BeforeAll
	static void loadOpenCv() {
		nu.pattern.OpenCV.loadLocally();
	}

	@Test
	void computeResizedHW_minLimit_scaleUp() {
		// 短边 100 < limit 640, ratio=6.4
		// h=100*6.4=640 → round(640/32)*32=640
		// w=200*6.4=1280 → round(1280/32)*32=1280
		int[] hw = DetectionPreprocessor.computeResizedHW(100, 200, 640, "min", 4000);
		assertEquals(640, hw[0]);
		assertEquals(1280, hw[1]);
	}

	@Test
	void computeResizedHW_minLimit_noScale() {
		// 短边 800 > limit 640, ratio=1.0
		// h=800 → round(800/32)=25, 25*32=800
		// w=1000 → round(1000/32)=31, 31*32=992
		int[] hw = DetectionPreprocessor.computeResizedHW(800, 1000, 640, "min", 4000);
		assertEquals(800, hw[0]);
		assertEquals(992, hw[1]);
	}

	@Test
	void computeResizedHW_maxLimit_scaleDown() {
		// max(800,2000)=2000 > 1000, ratio=0.5
		// h=800*0.5=400 → round(400/32)=round(12.5)=13, 13*32=416
		// w=2000*0.5=1000 → round(1000/32)=round(31.25)=31, 31*32=992
		int[] hw = DetectionPreprocessor.computeResizedHW(800, 2000, 1000, "max", 4000);
		assertEquals(416, hw[0]);
		assertEquals(992, hw[1]);
	}

	@Test
	void computeResizedHW_maxSideLimit() {
		// min(100,5000)=100 < 640, ratio=6.4 → h=640, w=32000
		// max(640,32000)=32000 > maxSide 2000, ratio2=2000/32000=0.0625
		// h=640*0.0625=40 → round(40/32)=1, max(1*32,32)=32
		// w=32000*0.0625=2000 → round(2000/32)=round(62.5)=63, 63*32=2016
		int[] hw = DetectionPreprocessor.computeResizedHW(100, 5000, 640, "min", 2000);
		assertEquals(32, hw[0]);
		assertEquals(2016, hw[1]);
	}

	@Test
	void computeResizedHW_alignToStride32() {
		// min(100,100)=100 > 64, ratio=1.0
		// h=100 → round(100/32)=round(3.125)=3, 3*32=96
		int[] hw = DetectionPreprocessor.computeResizedHW(100, 100, 64, "min", 4000);
		assertEquals(96, hw[0]);
		assertEquals(96, hw[1]);
		// 验证对齐
		assertEquals(0, hw[0] % 32);
		assertEquals(0, hw[1] % 32);
	}

	@Test
	void computeResizedHW_minLowerBound32() {
		// 极小图像，下限 32
		int[] hw = DetectionPreprocessor.computeResizedHW(1, 1, 64, "min", 4000);
		assertTrue(hw[0] >= 32);
		assertTrue(hw[1] >= 32);
	}

	@Test
	void constructor_invalidLimitType() {
		assertThrows(IllegalArgumentException.class, () ->
			new DetectionPreprocessor(64, "invalid", 4000));
	}

	@Test
	void constructor_zeroLimitSideLen() {
		assertThrows(IllegalArgumentException.class, () ->
			new DetectionPreprocessor(0, "min", 4000));
	}

	@Test
	void constructor_zeroMaxSideLimit() {
		assertThrows(IllegalArgumentException.class, () ->
			new DetectionPreprocessor(64, "min", 0));
	}

	@Test
	void constructor_valid() {
		DetectionPreprocessor pre = new DetectionPreprocessor(64, "min", 4000);
		assertNotNull(pre);
	}

	// ==================================================================
	// bit-exact 回归：流式归一化实现必须与旧实现逐位相同
	// ==================================================================

	/**
	 * 旧实现（materialize HWC flat + CV_32F 中间 Mat）作为参考实现。
	 *
	 * <p>保留在测试里作为「数值真相源」：优化只允许改内存占用，不允许改数值。
	 */
	private static float[] referenceNormalizeToChw(Mat img) {
		int h = img.rows();
		int w = img.cols();
		int c = img.channels();
		Mat f = new Mat();
		try {
			img.convertTo(f, CvType.CV_32F);
			float[] hwc = NdArrayUtils.matToFlatHwc(f);
			int n = hwc.length;
			int hw = n / 3;
			float scale = 1.0f / 255.0f;
			float meanB = 0.485f, meanG = 0.456f, meanR = 0.406f;
			float stdB = 0.229f, stdG = 0.224f, stdR = 0.225f;
			for (int i = 0; i < hw; i++) {
				int b = i * 3;
				hwc[b] = (hwc[b] * scale - meanB) / stdB;
				hwc[b + 1] = (hwc[b + 1] * scale - meanG) / stdG;
				hwc[b + 2] = (hwc[b + 2] * scale - meanR) / stdR;
			}
			return NdArrayUtils.hwcFlatToNchw(hwc, h, w, c);
		} finally {
			f.release();
		}
	}

	/**
	 * 复刻生产代码的 resize 步骤，得到与 {@code call()} 内部完全一致的输入图。
	 *
	 * <p>注意 stride-32 对齐会把尺寸改掉（如 137×211 → 128×224），
	 * 所以参考实现必须基于 resize 后的图计算。
	 *
	 * @return resize 后的 Mat；未发生缩放时返回原图（不额外持有）
	 */
	private static Mat resizedLikeProduction(Mat img, int limitSideLen, String limitType, int maxSideLimit) {
		int h = img.rows();
		int w = img.cols();
		int[] hw = DetectionPreprocessor.computeResizedHW(h, w, limitSideLen, limitType, maxSideLimit);
		if (hw[0] == h && hw[1] == w) {
			return img;
		}
		Mat out = new Mat();
		org.opencv.imgproc.Imgproc.resize(img, out,
			new org.opencv.core.Size(hw[1], hw[0]), 0, 0,
			org.opencv.imgproc.Imgproc.INTER_LINEAR);
		return out;
	}

	@Test
	void call_bitExact_noResize() {
		// 固定随机种子，保证可复现
		Random rnd = new Random(20261025L);
		// 128×128：min/64 下短边 128 >= 64 → 不缩放；128 又是 32 的整数倍 → 对齐后尺寸不变
		int h = 128;
		int w = 128;
		Mat img = new Mat(h, w, CvType.CV_8UC3);
		for (int r = 0; r < h; r++) {
			for (int c = 0; c < w; c++) {
				img.put(r, c, new double[]{
					rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)
				});
			}
		}
		try {
			DetectionPreprocessor pre = new DetectionPreprocessor(64, "min", 4000);
			DetectionPreprocessor.Result result = pre.call(img);

			float[] expected = referenceNormalizeToChw(img);
			assertArrayEquals(expected, result.data(),
				"流式归一化必须与旧实现逐位相同（bit-exact）");
			assertArrayEquals(new int[]{1, 3, h, w}, result.shape());
		} finally {
			img.release();
		}
	}

	@Test
	void call_bitExact_afterResize() {
		Random rnd = new Random(42L);
		int h = 96;
		int w = 96;
		Mat img = new Mat(h, w, CvType.CV_8UC3);
		for (int r = 0; r < h; r++) {
			for (int c = 0; c < w; c++) {
				img.put(r, c, new double[]{
					rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)
				});
			}
		}
		try {
			// limitSideLen=640 + min：短边 96 < 640 → 放大到 640，走 resize 分支
			DetectionPreprocessor pre = new DetectionPreprocessor(640, "min", 4000);
			DetectionPreprocessor.Result result = pre.call(img);

			Mat resized = resizedLikeProduction(img, 640, "min", 4000);
			try {
				float[] expected = referenceNormalizeToChw(resized);
				assertArrayEquals(expected, result.data(),
					"resize 之后仍须与旧实现逐位相同");
				assertArrayEquals(new int[]{1, 3, resized.rows(), resized.cols()}, result.shape());
			} finally {
				if (resized != img) {
					resized.release();
				}
			}
		} finally {
			img.release();
		}
	}

	@Test
	void call_nonContinuousMat_readsCorrectly() {
		int h = 40;
		int w = 40;
		Mat base = new Mat(h, w, CvType.CV_8UC3, new Scalar(7, 9, 11));
		// 窄 ROI：行 stride（w*3）大于 ROI 宽度（w/2*3）→ OpenCV 判定为非连续，
		// Mat.get(row, 0, byte[]) 逐行读会跨行，必须走 clone 分支
		Mat roi = new Mat(base, new org.opencv.core.Rect(0, 0, w / 2, h / 2));
		try {
			assertFalse(roi.isContinuous(), "窄 ROI 应为非连续，用于覆盖 clone 分支");
			DetectionPreprocessor pre = new DetectionPreprocessor(32, "min", 4000);
			DetectionPreprocessor.Result result = pre.call(roi);

			Mat resized = resizedLikeProduction(roi, 32, "min", 4000);
			try {
				float[] expected = referenceNormalizeToChw(resized);
				assertArrayEquals(expected, result.data(),
					"非连续 Mat 走 clone 分支后仍须逐位相同");
			} finally {
				if (resized != roi) {
					resized.release();
				}
			}
		} finally {
			roi.release();
			base.release();
		}
	}
}
