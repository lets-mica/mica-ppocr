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

import lombok.ToString;
import lombok.experimental.Accessors;
import net.dreamlu.mica.ai.ppocr.utils.CollUtil;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.Set;

/**
 * 检测模型预处理流水线。
 *
 * <p>对应 Python 端的 det 预处理：
 * <ol>
 *   <li>DetResizeForTest.resize_image_type0：按 limitType 缩放，再对齐到 32 倍数</li>
 *   <li>NormalizeImage：(img * (1/255) - mean) / std</li>
 *   <li>HWC → CHW → NCHW（增加 batch 维）</li>
 * </ol>
 *
 * <p>注意：原 PaddleX 代码在 BGR 图像上使用 RGB 顺序的 ImageNet mean/std
 * （值相同但被当成 BGR 通道应用）。这里沿用同样的常量以保证 bit-exact 兼容。
 *
 * <p>输出张量用 flat float[]（长度 C·H'·W'，CHW 顺序），
 * 供 OnnxTensor.createTensor(env, buffer, new long[]{1, C, H, W}) 消费。
 */
@ToString
public final class DetectionPreprocessor {
	/**
	 * 支持的 limitType 取值。
	 */
	public static final Set<String> VALID_LIMIT_TYPES = CollUtil.setOf("min", "max");
	private static final float SCALE = 1.0f / 255.0f;
	private static final Scalar MEAN = new Scalar(0.485, 0.456, 0.406);
	private static final Scalar STD = new Scalar(0.229, 0.224, 0.225);
	private static final int STRIDE = 32;

	private final int limitSideLen;
	private final String limitType;
	private final int maxSideLimit;

	/**
	 * 创建检测预处理器。
	 *
	 * @param limitSideLen 限制边长
	 * @param limitType    限制类型：min 或 max
	 * @param maxSideLimit 最大边长
	 */
	public DetectionPreprocessor(int limitSideLen, String limitType, int maxSideLimit) {
		if (!VALID_LIMIT_TYPES.contains(limitType)) {
			throw new IllegalArgumentException("limitType must be one of " + VALID_LIMIT_TYPES + ", got '" + limitType + "'");
		}
		if (limitSideLen <= 0) {
			throw new IllegalArgumentException("limitSideLen must be > 0, got " + limitSideLen);
		}
		if (maxSideLimit <= 0) {
			throw new IllegalArgumentException("maxSideLimit must be > 0, got " + maxSideLimit);
		}
		this.limitSideLen = limitSideLen;
		this.limitType = limitType;
		this.maxSideLimit = maxSideLimit;
	}

	/**
	 * 静态工具：根据给定 (h, w) 计算 resize 后的实际 (rh, rw)。
	 *
	 * @param h       原始高
	 * @param w       原始宽
	 * @param limit   限制边长
	 * @param type    限制类型：min 或 max
	 * @param maxSide 最大边长
	 * @return [rh, rw]
	 */
	public static int[] computeResizedHW(int h, int w, int limit, String type, int maxSide) {
		double ratio = "max".equals(type)
			? (Math.max(h, w) > limit ? (double) limit / Math.max(h, w) : 1.0)
			: (Math.min(h, w) < limit ? (double) limit / Math.min(h, w) : 1.0);
		int rh = (int) (h * ratio);
		int rw = (int) (w * ratio);
		if (Math.max(rh, rw) > maxSide) {
			ratio = (double) maxSide / Math.max(rh, rw);
			rh = (int) (rh * ratio);
			rw = (int) (rw * ratio);
		}
		rh = Math.max(Math.round((float) rh / STRIDE) * STRIDE, STRIDE);
		rw = Math.max(Math.round((float) rw / STRIDE) * STRIDE, STRIDE);
		return new int[]{rh, rw};
	}

	/**
	 * 执行预处理。
	 *
	 * <p>内存策略：归一化结果<b>直接写入 CHW 输出数组</b>，不 materialize 中间 HWC 数组，
	 * 也不生成 CV_32F 中间 Mat。峰值内存 = 1 份 CHW（4 字节/像素/通道）+ 1 行缓冲。
	 * 旧实现需要 2 份堆数组（hwcFlat + chw）+ 1 份 native CV_32F Mat，
	 * 4000×3000 图下多占约 288 MiB。
	 *
	 * <p>数值等价性：uint8 像素值 0~255 在 float32 中精确可表示，
	 * 故「uint8 → CV_32F → 运算」与「uint8 → 提升为 float → 运算」逐位相同，
	 * 与 Python 参考实现保持 bit-exact。
	 *
	 * @param imgBgr BGR 图像 (H, W, 3) uint8
	 * @return 预处理结果
	 */
	public Result call(Mat imgBgr) {
		int srcH = imgBgr.rows();
		int srcW = imgBgr.cols();

		ResizeOutcome ro = resizeImage(imgBgr, srcH, srcW);
		try {
			int hNew = ro.image.rows();
			int wNew = ro.image.cols();
			int c = ro.image.channels();
			float[] chw = normalizeToChw(ro.image, hNew, wNew, c);

			float[] shape = new float[]{
				srcH, srcW,
				(float) ro.ratioH, (float) ro.ratioW
			};
			return new Result(chw, new int[]{1, c, hNew, wNew}, shape);
		} finally {
			if (ro.image != imgBgr) {
				ro.image.release();
			}
		}
	}

	private ResizeOutcome resizeImage(Mat img, int h, int w) {
		int[] hw = computeResizedHW(h, w, limitSideLen, limitType, maxSideLimit);
		int rh = hw[0];
		int rw = hw[1];

		if (rh == h && rw == w) {
			return new ResizeOutcome(img, 1.0, 1.0);
		}
		Mat resized = new Mat();
		try {
			Imgproc.resize(img, resized, new Size(rw, rh), 0, 0, Imgproc.INTER_LINEAR);
			return new ResizeOutcome(resized, (double) rh / h, (double) rw / w);
		} catch (RuntimeException | Error e) {
			resized.release();
			throw e;
		}
	}

	/**
	 * 归一化并直接写出 CHW flat float[]。
	 *
	 * <p>归一化公式：{@code (img * (1/255) - mean) / std}，按通道索引取 mean/std
	 * （B → MEAN[0] / STD[0]，G → [1]，R → [2]，与旧实现一致）。
	 *
	 * <p>注意：Core.multiply/subtract/divide(Mat, Scalar, Mat) 对多通道 Mat
	 * 处理有 bug（G/R 通道会变成 +Inf），所以归一化在 Java 端完成。
	 *
	 * <p>逐行读取要求 Mat 连续（否则 {@code Mat.get(row, 0, byte[])} 会跨行读错），
	 * 非连续时先 clone。
	 *
	 * @param img BGR uint8 Mat
	 * @param h   图像高
	 * @param w   图像宽
	 * @param c   通道数
	 * @return 归一化后的 CHW flat float[]，长度 = C * H * W
	 */
	private float[] normalizeToChw(Mat img, int h, int w, int c) {
		int hw = h * w;
		float[] chw = new float[c * hw];
		Mat src = img.isContinuous() ? img : img.clone();
		try {
			byte[] rowBuf = new byte[w * c];
			if (c == 3) {
				normalizeToChwBgr(src, chw, h, w, hw, rowBuf);
			} else {
				normalizeToChwGeneric(src, chw, h, w, c, hw, rowBuf);
			}
		} finally {
			if (src != img) {
				src.release();
			}
		}
		return chw;
	}

	/**
	 * 3 通道（BGR）快速路径：mean/std 按通道索引固定取 B/G/R 三档。
	 */
	private void normalizeToChwBgr(Mat src, float[] chw, int h, int w, int hw, byte[] rowBuf) {
		float scale = SCALE;
		float meanB = (float) MEAN.val[0], meanG = (float) MEAN.val[1], meanR = (float) MEAN.val[2];
		float stdB = (float) STD.val[0], stdG = (float) STD.val[1], stdR = (float) STD.val[2];
		for (int r = 0; r < h; r++) {
			src.get(r, 0, rowBuf);
			int rowBase = r * w;
			for (int col = 0; col < w; col++) {
				int i = rowBase + col;
				int b = col * 3;
				chw[i] = ((rowBuf[b] & 0xFF) * scale - meanB) / stdB;
				chw[hw + i] = ((rowBuf[b + 1] & 0xFF) * scale - meanG) / stdG;
				chw[2 * hw + i] = ((rowBuf[b + 2] & 0xFF) * scale - meanR) / stdR;
			}
		}
	}

	/**
	 * 非 3 通道通用路径：按通道索引取 mean/std（超出范围时取最后一档）。
	 *
	 * <p>det 输入在当前代码路径上恒为 3 通道（imread / imdecode IMREAD_COLOR /
	 * BufferedImageUtils.toBgrMat 均产出 CV_8UC3），此分支仅为完整性保留。
	 */
	private void normalizeToChwGeneric(Mat src, float[] chw, int h, int w, int c, int hw, byte[] rowBuf) {
		float scale = SCALE;
		for (int r = 0; r < h; r++) {
			src.get(r, 0, rowBuf);
			int rowBase = r * w;
			for (int col = 0; col < w; col++) {
				int i = rowBase + col;
				int b = col * c;
				for (int ch = 0; ch < c; ch++) {
					float mean = ch < MEAN.val.length ? (float) MEAN.val[ch] : (float) MEAN.val[MEAN.val.length - 1];
					float std = ch < STD.val.length ? (float) STD.val[ch] : (float) STD.val[STD.val.length - 1];
					chw[ch * hw + i] = ((rowBuf[b + ch] & 0xFF) * scale - mean) / std;
				}
			}
		}
	}

	/**
	 * det 预处理结果。
	 *
	 * @param data     NCHW flat float[] 数据
	 * @param shape    [N, C, H, W]
	 * @param imgShape [srcH, srcW, ratioH, ratioW]
	 */
	@lombok.Value
	@Accessors(fluent = true)
	public static class Result {
		private final float[] data;
		private final int[] shape;
		private final float[] imgShape;
	}

	@lombok.Value
	@Accessors(fluent = true)
	private static class ResizeOutcome {
		private final Mat image;
		private final double ratioH;
		private final double ratioW;
	}
}
