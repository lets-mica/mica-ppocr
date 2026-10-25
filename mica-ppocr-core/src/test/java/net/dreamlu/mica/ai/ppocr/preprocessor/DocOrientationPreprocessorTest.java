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
import org.junit.jupiter.api.Test;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DocOrientationPreprocessor 单元测试。
 *
 * <p>重点覆盖：中心裁剪视图（子 Mat）的释放顺序 —— 修 native 缓冲滞留后，
 * 仍必须保证「调用方的 Mat 不会被误释放」且「归一化结果逐位不变」。
 */
class DocOrientationPreprocessorTest {

	/**
	 * 旧实现作为参考：resizeShort → centerCrop → CV_32F → 归一化 → CHW。
	 */
	private static float[] referenceCall(Mat imgBgr) {
		int h = imgBgr.rows();
		int w = imgBgr.cols();
		int shortSide = Math.min(h, w);
		Mat resized;
		if (shortSide == DocOrientationPreprocessor.RESIZE_SHORT) {
			resized = imgBgr;
		} else {
			double ratio = (double) DocOrientationPreprocessor.RESIZE_SHORT / shortSide;
			int newH = (int) Math.round(h * ratio);
			int newW = (int) Math.round(w * ratio);
			resized = new Mat();
			Imgproc.resize(imgBgr, resized, new Size(newW, newH), 0, 0, Imgproc.INTER_LINEAR);
		}
		try {
			int crop = DocOrientationPreprocessor.CROP_SIZE;
			int y0 = Math.max(0, (resized.rows() - crop) / 2);
			int x0 = Math.max(0, (resized.cols() - crop) / 2);
			Mat cropped = new Mat(resized, new org.opencv.core.Rect(x0, y0, crop, crop));

			Mat f = new Mat();
			try {
				cropped.convertTo(f, CvType.CV_32F);
				float[] hwc = NdArrayUtils.matToFlatHwc(f);
				int hh = cropped.rows();
				int ww = cropped.cols();
				int hw = hh * ww;
				int c = 3;
				float[] chw = new float[c * hw];
				float scale = 1.0f / 255.0f;
				float[] mean = {0.485f, 0.456f, 0.406f};
				float[] std = {0.229f, 0.224f, 0.225f};
				for (int i = 0; i < hw; i++) {
					int b = i * c;
					// BGR → CHW，且按 RGB 顺序应用 mean/std
					chw[hw * 0 + i] = (hwc[b + 2] * scale - mean[0]) / std[0];
					chw[hw * 1 + i] = (hwc[b + 1] * scale - mean[1]) / std[1];
					chw[hw * 2 + i] = (hwc[b] * scale - mean[2]) / std[2];
				}
				return chw;
			} finally {
				f.release();
			}
		} finally {
			if (resized != imgBgr) {
				resized.release();
			}
		}
	}

	@Test
	void call_bitExact_matchesReference() {
		nu.pattern.OpenCV.loadLocally();
		Mat img = new Mat(300, 400, CvType.CV_8UC3);
		for (int r = 0; r < 300; r++) {
			for (int c = 0; c < 400; c++) {
				img.put(r, c, new double[]{(r * 3) % 256, (c * 5) % 256, (r + c) % 256});
			}
		}
		try {
			DocOrientationPreprocessor pre = new DocOrientationPreprocessor();
			DocOrientationPreprocessor.Result result = pre.call(img);
			assertArrayEquals(referenceCall(img), result.data(),
				"释放顺序调整后归一化结果必须逐位不变");
			assertArrayEquals(new int[]{1, 3, 224, 224}, result.shape());
			assertEquals(300, result.srcH());
			assertEquals(400, result.srcW());
		} finally {
			img.release();
		}
	}

	@Test
	void call_doesNotReleaseCallerMat() {
		nu.pattern.OpenCV.loadLocally();
		// 短边正好 256 → resizeShort 直接返回原图（ownsResized=false），
		// cropped 是调用方 Mat 的子视图；释放视图不得连带释放调用方的数据
		Mat img = new Mat(256, 300, CvType.CV_8UC3, new Scalar(11, 22, 33));
		try {
			DocOrientationPreprocessor pre = new DocOrientationPreprocessor();
			pre.call(img);

			// 调用后原图仍可读，且像素未被破坏（CV_8UC3 用 byte[] 读）
			byte[] px = new byte[3];
			img.get(10, 10, px);
			assertEquals(11, px[0] & 0xFF);
			assertEquals(22, px[1] & 0xFF);
			assertEquals(33, px[2] & 0xFF);
			assertFalse(img.empty(), "调用方 Mat 不应被释放");
		} finally {
			img.release();
		}
	}

	@Test
	void call_rejectsNonBgrMat() {
		nu.pattern.OpenCV.loadLocally();
		DocOrientationPreprocessor pre = new DocOrientationPreprocessor();

		// 1 通道（灰度）：行缓冲比一行长 → 跨行读，必须显式拒绝
		Mat gray = new Mat(256, 300, CvType.CV_8UC1);
		try {
			IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> pre.call(gray));
			assertTrue(e.getMessage().contains("CV_8UC3"), "错误信息应说明期望的通道类型");
		} finally {
			gray.release();
		}

		// 4 通道：通道步长错位
		Mat rgba = new Mat(256, 300, CvType.CV_8UC4);
		try {
			assertThrows(IllegalArgumentException.class, () -> pre.call(rgba));
		} finally {
			rgba.release();
		}

		// float32：逐行 byte[] 读不支持
		Mat f32 = new Mat(256, 300, CvType.CV_32FC3);
		try {
			assertThrows(IllegalArgumentException.class, () -> pre.call(f32));
		} finally {
			f32.release();
		}
	}

	@Test
	void call_emptyMat_throws() {
		nu.pattern.OpenCV.loadLocally();
		DocOrientationPreprocessor pre = new DocOrientationPreprocessor();
		Mat empty = new Mat();
		try {
			assertThrows(IllegalArgumentException.class, () -> pre.call(empty));
		} finally {
			empty.release();
		}
	}

	@Test
	void constructor_invalidArgs() {
		assertThrows(IllegalArgumentException.class, () -> new DocOrientationPreprocessor(0, 256));
		assertThrows(IllegalArgumentException.class, () -> new DocOrientationPreprocessor(224, 224));
	}
}
