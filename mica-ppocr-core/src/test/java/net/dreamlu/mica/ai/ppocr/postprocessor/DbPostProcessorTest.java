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

package net.dreamlu.mica.ai.ppocr.postprocessor;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DbPostProcessor 单元测试。
 *
 * <p>DB 后处理 = 概率图二值化 → 轮廓 → 最小外接矩形 → 框内概率均值 → 多边形外扩。
 * 其中「二值化」这一步历史上走的是三趟 OpenCV 运算
 * （{@code threshold} → {@code Core.multiply} → {@code convertTo}），
 * 现在改为一次 Java 遍历直出 0/255 掩码 —— 这类改动的红线是<b>数值必须逐位不变</b>，
 * 故本测试把「OpenCV 三趟链」作为参考实现锁住。
 */
class DbPostProcessorTest {

	/**
	 * 本测试直接使用 {@code org.opencv.*}（不经过 {@code PPOcrV6Engine}），
	 * 引擎的构造期自动加载管不到这里，需自行加载 native 库。
	 */
	@BeforeAll
	static void loadOpenCv() {
		nu.pattern.OpenCV.loadLocally();
	}

	// ==================================================================
	// 二值化直出：必须与 OpenCV 三趟链逐位相同
	// ==================================================================

	/**
	 * 参考实现：OpenCV 的 threshold → multiply → convertTo 三趟链。
	 *
	 * <p>语义：{@code u8[i] = (prob[i] > thresh) ? 255 : 0}。
	 *
	 * @param prob   概率图 (CV_32F)
	 * @param thresh 阈值
	 * @return 0/255 的 uint8 掩码
	 */
	private static byte[] referenceBinaryMask(Mat prob, float thresh) {
		Mat segmentation = new Mat();
		Mat u8 = new Mat();
		try {
			Imgproc.threshold(prob, segmentation, thresh, 1.0, Imgproc.THRESH_BINARY);
			Core.multiply(segmentation, new Scalar(255.0), u8);
			u8.convertTo(u8, CvType.CV_8U);
			byte[] out = new byte[u8.rows() * u8.cols()];
			u8.get(0, 0, out);
			return out;
		} finally {
			segmentation.release();
			u8.release();
		}
	}

	@Test
	void fillBinaryMask_matchesOpenCvThresholdChain_bitExact() {
		// 固定种子，覆盖随机概率值；阈值两侧与相等点都要落到掩码的不同分支
		Random rnd = new Random(20261112L);
		int h = 64;
		int w = 96;
		Mat prob = new Mat(h, w, CvType.CV_32F);
		float[] values = new float[h * w];
		for (int i = 0; i < values.length; i++) {
			values[i] = rnd.nextFloat();
		}
		// 边界值：恰好等于阈值（走 <= 分支）、略大于、略小于
		values[0] = 0.3f;
		values[1] = Math.nextUp(0.3f);
		values[2] = Math.nextDown(0.3f);
		values[3] = 0.0f;
		values[4] = 1.0f;
		prob.put(0, 0, values);

		Mat actual = new Mat();
		try {
			DbPostProcessor.fillBinaryMask(prob, 0.3f, actual);
			byte[] got = new byte[h * w];
			actual.get(0, 0, got);
			byte[] expected = referenceBinaryMask(prob, 0.3f);
			assertArrayEquals(expected, got,
				"二值化直出必须与 OpenCV threshold→multiply→convertTo 三趟链逐位相同");
		} finally {
			actual.release();
			prob.release();
		}
	}

	@Test
	void fillBinaryMask_nonContinuousMat_readsCorrectly() {
		// 窄 ROI：行 stride 大于 ROI 宽度 → OpenCV 判定非连续，
		// 逐行读会跨行，必须走 clone 分支
		int h = 32;
		int w = 32;
		Mat base = new Mat(h, w, CvType.CV_32F, new Scalar(0.9));
		Mat roi = new Mat(base, new org.opencv.core.Rect(0, 0, w / 2, h / 2));
		try {
			assertFalse(roi.isContinuous(), "窄 ROI 应为非连续，用于覆盖 clone 分支");
			Mat actual = new Mat();
			try {
				DbPostProcessor.fillBinaryMask(roi, 0.3f, actual);
				byte[] got = new byte[roi.rows() * roi.cols()];
				actual.get(0, 0, got);
				byte[] expected = referenceBinaryMask(roi, 0.3f);
				assertArrayEquals(expected, got, "非连续 Mat 走 clone 分支后仍须逐位相同");
			} finally {
				actual.release();
			}
		} finally {
			roi.release();
			base.release();
		}
	}

	// ==================================================================
	// 端到端：合成概率图 → 期望框（golden）
	// ==================================================================

	/**
	 * 构造一张含两个前景矩形的概率图。
	 *
	 * @return 概率图 (CV_32F)；调用方负责 release
	 */
	private static Mat syntheticProbMap() {
		int h = 160;
		int w = 160;
		Mat prob = new Mat(h, w, CvType.CV_32F, new Scalar(0.05));
		// 矩形一：左上区域
		org.opencv.core.Rect r1 = new org.opencv.core.Rect(20, 30, 60, 25);
		// 矩形二：右下区域
		org.opencv.core.Rect r2 = new org.opencv.core.Rect(90, 100, 45, 30);
		prob.submat(r1.y, r1.y + r1.height, r1.x, r1.x + r1.width)
			.setTo(new Scalar(0.9));
		prob.submat(r2.y, r2.y + r2.height, r2.x, r2.x + r2.width)
			.setTo(new Scalar(0.85));
		return prob;
	}

	@Test
	void call_syntheticProbMap_returnsExpectedBoxes() {
		Mat prob = syntheticProbMap();
		try {
			DbPostProcessor post = new DbPostProcessor(DbDetParams.of(0.3f, 0.6f, 1.5f));
			DbPostProcessor.Result result = post.call(prob, new float[]{prob.rows(), prob.cols(), 1f, 1f});

			assertEquals(2, result.boxes().length, "应检出两个文本块");
			// golden：框坐标由「二值化 → 轮廓 → minAreaRect → boxScore → unclip」链路决定，
			// 任何一步的数值变化都会让本断言变红
			assertArrayEquals(new int[][]{
				{76, 86}, {147, 86}, {147, 142}, {76, 142}
			}, result.boxes()[0], "第一个框的坐标必须逐位稳定");
			assertArrayEquals(new int[][]{
				{7, 17}, {91, 17}, {91, 66}, {7, 66}
			}, result.boxes()[1], "第二个框的坐标必须逐位稳定");
		} finally {
			prob.release();
		}
	}
}
