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
 */
@Slf4j
@ToString
@RequiredArgsConstructor
public final class DbPostProcessor {
	private final float thresh;
	private final float boxThresh;
	private final float unclipRatio;
	private final int maxCandidates;
	private final int minSize;

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
				sb.append(")");
				throw new IllegalArgumentException(sb.toString());
			}

			Mat segmentation = new Mat();
			try {
				Imgproc.threshold(workingProb, segmentation, thresh, 1.0, Imgproc.THRESH_BINARY);
				return extractBoxes(workingProb, segmentation, srcW, srcH);
			} finally {
				segmentation.release();
			}
		} finally {
			if (workingProb != prob) {
				workingProb.release();
			}
		}
	}

	private Result extractBoxes(Mat prob, Mat bitmap, int dstW, int dstH) {
		List<MatOfPoint> contours = new ArrayList<>();
		Mat u8 = new Mat();
		Mat hierarchy = null;
		try {
			hierarchy = new Mat();
			Core.multiply(bitmap, new Scalar(255.0), u8);
			u8.convertTo(u8, CvType.CV_8U);
			Imgproc.findContours(u8, contours, hierarchy, Imgproc.RETR_LIST,
				Imgproc.CHAIN_APPROX_SIMPLE);

			int bmH = bitmap.rows();
			int bmW = bitmap.cols();
			double ws = (double) dstW / bmW;
			double hs = (double) dstH / bmH;

			List<int[][]> boxList = new ArrayList<>();
			List<Float> scoreList = new ArrayList<>();

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
