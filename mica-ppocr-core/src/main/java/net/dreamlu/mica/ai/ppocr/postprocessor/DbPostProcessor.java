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

import lombok.RequiredArgsConstructor;
import lombok.ToString;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import net.dreamlu.mica.ai.ppocr.utils.BoxUtil;
import net.dreamlu.mica.ai.ppocr.utils.NdArrayUtils;
import net.dreamlu.mica.ai.ppocr.utils.Offset;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;

/**
 * DB 后处理：从概率图中提取四边形文本框。
 *
 * <p>对应 Python 端的 DBPostProcess：
 * <ol>
 *   <li>prob map → 二值 segmentation（threshold）</li>
 *   <li>findContours → minAreaRect → boxPoints</li>
 *   <li>Offset.unclip 扩框</li>
 *   <li>映射回原图坐标</li>
 * </ol>
 *
 * <p>第 1 步的「二值 segmentation」不再走 OpenCV 的
 * {@code threshold → multiply → convertTo} 三趟链，改为一次 Java 遍历直出 0/255 掩码
 * （见 {@link #fillBinaryMask}）：三趟链每次要在 native 侧过两遍 CV_32F 中间结果
 * （约 21 MB），直出后只剩一份 CV_8U 掩码（约 2.7 MB）。
 */
@Slf4j
@ToString
@RequiredArgsConstructor
public final class DbPostProcessor {
	private final DbDetParams dbDetParams;

	/**
	 * 执行 DB 后处理。
	 *
	 * @param prob     det 模型输出的概率图
	 * @param imgShape 原始图像尺寸与缩放比例 [srcH, srcW, ratioH, ratioW]
	 * @return 检测结果（boxes + scores）
	 */
	public Result call(Mat prob, float[] imgShape) {
		int srcH = (int) imgShape[0];
		int srcW = (int) imgShape[1];
		Mat workingProb = prob;
		try {
			if (prob.dims() == 4 && prob.size(0) == 1 && prob.size(1) == 1) {
				int hNew = prob.size(2);
				int wNew = prob.size(3);
				workingProb = prob.reshape(1, hNew);
				if (workingProb.cols() != wNew) {
					Mat previous = workingProb;
					workingProb = previous.reshape(1, wNew);
					previous.release();
				}
			} else if (prob.dims() != 2) {
				StringBuilder sb = new StringBuilder("DbPostProcessor: 期望 prob 2D (H, W) 或 4D (1, 1, H, W)，实际 (");
				for (int d = 0; d < prob.dims(); d++) {
					if (d > 0) sb.append(", ");
					sb.append(prob.size(d));
				}
				sb.append(')');
				throw new IllegalArgumentException(sb.toString());
			}

			return extractBoxes(workingProb, srcW, srcH);
		} finally {
			if (workingProb != prob) {
				workingProb.release();
			}
		}
	}

	/**
	 * 二值化：把概率图直接转成 {@code findContours} 需要的 0/255 uint8 掩码。
	 *
	 * <p><b>为什么直出而不是走 OpenCV 三趟链</b>：历史实现是
	 * {@code Imgproc.threshold} → {@code Core.multiply(255)} → {@code convertTo(CV_8U)}，
	 * 三趟都要在 native 侧过一遍 1408×1920 的 CV_32F 中间结果（每次约 11 MB），
	 * 实测占 DB 后处理耗时的约三分之一。而三趟的语义等价于一句话：
	 * {@code u8[i] = (prob[i] > thresh) ? 255 : 0}，
	 * 用一次 Java 遍历直出即可，<b>逐位相同</b>且省掉两个 native 中间 Mat。
	 *
	 * <p>⚠️ 阈值比较是<b>严格大于</b>（与 OpenCV {@code THRESH_BINARY} 一致）：
	 * {@code prob[i] == thresh} 时取 0（背景），不是 255。
	 *
	 * <p>⚠️ 掩码<b>逐行写入</b>而非先攒成整块 {@code byte[]} 再一次 put：
	 * 两者耗时相当（实测 dbPost 6.7 ms vs 6.8 ms），但整块写法每次要额外分配
	 * 一份 h×w 的 Java 堆数组（1920×1408 即 2.7 MB），逐行写法只用一行缓冲，
	 * 对并发场景的 GC 压力更友好。
	 *
	 * @param prob   概率图 (CV_32F)
	 * @param thresh 二值化阈值
	 * @param out    输出掩码 (CV_8U)，内容被整体覆盖
	 */
	static void fillBinaryMask(Mat prob, float thresh, Mat out) {
		int h = prob.rows();
		int w = prob.cols();
		// 逐行读要求 Mat 连续，否则 Mat.get(row, 0, float[]) 会跨行读错
		Mat src = prob.isContinuous() ? prob : prob.clone();
		try {
			out.create(h, w, CvType.CV_8U);
			float[] rowBuf = new float[w];
			byte[] outRow = new byte[w];
			for (int r = 0; r < h; r++) {
				src.get(r, 0, rowBuf);
				for (int c = 0; c < w; c++) {
					outRow[c] = rowBuf[c] > thresh ? (byte) 255 : (byte) 0;
				}
				out.put(r, 0, outRow);
			}
		} finally {
			if (src != prob) {
				src.release();
			}
		}
	}

	private Result extractBoxes(Mat prob, int dstW, int dstH) {
		List<MatOfPoint> contours = new ArrayList<>();
		Mat u8 = new Mat();
		Mat hierarchy = null;
		try {
			hierarchy = new Mat();
			fillBinaryMask(prob, dbDetParams.thresh(), u8);
			Imgproc.findContours(u8, contours, hierarchy, Imgproc.RETR_LIST,
				Imgproc.CHAIN_APPROX_SIMPLE);

			int bmH = prob.rows();
			int bmW = prob.cols();
			double ws = (double) dstW / bmW;
			double hs = (double) dstH / bmH;

			List<int[][]> boxList = new ArrayList<>();
			List<Float> scoreList = new ArrayList<>();

			int maxCandidates = dbDetParams.maxCandidates();
			int minSize = dbDetParams.minSize();
			float boxThresh = dbDetParams.boxThresh();
			float unclipRatio = dbDetParams.unclipRatio();
			int n = Math.min(contours.size(), maxCandidates);
			for (int i = 0; i < n; i++) {
				MatOfPoint contour = contours.get(i);
				BoxUtil.MinAreaBox mab;
				try {
					mab = BoxUtil.orderMinAreaBoxPoints(contour);
				} catch (Exception e) {
					log.debug("minAreaRect 失败, 跳过轮廓 #{}: {}", i, e.getMessage());
					continue;
				}
				if (mab.minSideLen() < minSize) {
					continue;
				}

				float[][] pts = mab.asFloatArray();
				float score = boxScore(prob, pts);
				if (score < boxThresh) {
					continue;
				}

				float[][] expanded = Offset.unclip(pts, Offset.unclipDistance(pts, unclipRatio));
				if (expanded.length < 3) {
					continue;
				}

				BoxUtil.MinAreaBox mab2 = BoxUtil.orderMinAreaBoxPoints(expanded);
				if (mab2.minSideLen() < minSize + 2) {
					continue;
				}

				float[][] boxF = mab2.asFloatArray();
				int[][] boxI = new int[4][2];
				for (int k = 0; k < 4; k++) {
					int x = NdArrayUtils.clamp((int) Math.round(boxF[k][0] * ws), 0, dstW);
					int y = NdArrayUtils.clamp((int) Math.round(boxF[k][1] * hs), 0, dstH);
					boxI[k][0] = x;
					boxI[k][1] = y;
				}
				boxList.add(boxI);
				scoreList.add(score);
			}

			int[][][] boxes = new int[boxList.size()][4][2];
			for (int i = 0; i < boxList.size(); i++) {
				boxes[i] = boxList.get(i);
			}
			float[] scores = new float[scoreList.size()];
			for (int i = 0; i < scoreList.size(); i++) {
				scores[i] = scoreList.get(i);
			}
			return new Result(boxes, scores);
		} finally {
			for (MatOfPoint contour : contours) {
				contour.release();
			}
			if (hierarchy != null) {
				hierarchy.release();
			}
			u8.release();
		}
	}

	private float boxScore(Mat bitmap, float[][] polygon) {
		int h = bitmap.rows();
		int w = bitmap.cols();

		float xMinF = Float.POSITIVE_INFINITY, xMaxF = Float.NEGATIVE_INFINITY;
		float yMinF = Float.POSITIVE_INFINITY, yMaxF = Float.NEGATIVE_INFINITY;
		for (float[] p : polygon) {
			if (p[0] < xMinF) xMinF = p[0];
			if (p[0] > xMaxF) xMaxF = p[0];
			if (p[1] < yMinF) yMinF = p[1];
			if (p[1] > yMaxF) yMaxF = p[1];
		}

		int xMin = NdArrayUtils.clamp((int) Math.floor(xMinF), 0, w - 1);
		int xMax = NdArrayUtils.clamp((int) Math.ceil(xMaxF), 0, w - 1);
		int yMin = NdArrayUtils.clamp((int) Math.floor(yMinF), 0, h - 1);
		int yMax = NdArrayUtils.clamp((int) Math.ceil(yMaxF), 0, h - 1);

		if (xMax < xMin || yMax < yMin) {
			return 0f;
		}

		int ww = xMax - xMin + 1;
		int hh = yMax - yMin + 1;
		Mat mask = Mat.zeros(hh, ww, CvType.CV_8U);
		MatOfPoint mop = null;
		Mat roi = null;
		try {
			Point[] shifted = new Point[polygon.length];
			for (int i = 0; i < polygon.length; i++) {
				shifted[i] = new Point(polygon[i][0] - xMin, polygon[i][1] - yMin);
			}
			mop = new MatOfPoint(shifted);
			ArrayList<MatOfPoint> list = new ArrayList<>();
			list.add(mop);
			Imgproc.fillPoly(mask, list, new Scalar(1));

			roi = bitmap.submat(yMin, yMax + 1, xMin, xMax + 1);
			return (float) Core.mean(roi, mask).val[0];
		} finally {
			if (roi != null) {
				roi.release();
			}
			if (mop != null) {
				mop.release();
			}
			mask.release();
		}
	}

	/**
	 * DB 后处理结果。
	 */
	@lombok.Value
	@Accessors(fluent = true)
	public static class Result {
		/**
		 * 文本框 (N, 4, 2)
		 */
		int[][][] boxes;
		/**
		 * 每框分数，长度 N
		 */
		float[] scores;
	}
}
