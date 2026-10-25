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
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

/**
 * 文档方向分类模型预处理流水线。
 *
 * <p>对应官方 PP-LCNet_x1_0_doc_ori 模型的预处理：
 * <ol>
 *   <li>DecodeImage：to_rgb=true</li>
 *   <li>ResizeImage：resize_short=256（短边缩放到 256，长边按比例缩放）</li>
 *   <li>CropImage：size=224（中心裁剪 224×224）</li>
 *   <li>NormalizeImage：scale=1/255，mean=[0.485, 0.456, 0.406]，std=[0.229, 0.224, 0.225]</li>
 *   <li>ToCHWImage：HWC → CHW</li>
 * </ol>
 *
 * <p>输出张量用 flat float[]（长度 C·H·W，CHW 顺序），
 * 供 OnnxTensor.createTensor(env, buffer, new long[]{1, C, H, W}) 消费。
 *
 * <p>对应 PP-OCRv6 的 {@code use_doc_orientation_classify} 开关。
 */
@ToString
public final class DocOrientationPreprocessor {
	/**
	 * 通道数（RGB）。
	 */
	public static final int CHANNELS = 3;
	/**
	 * 训练时的 crop 尺寸（与 model 严格匹配）。
	 */
	public static final int CROP_SIZE = 224;
	/**
	 * 训练时的 resize 短边长度。
	 */
	public static final int RESIZE_SHORT = 256;

	/**
	 * ImageNet mean（RGB 顺序）。
	 */
	private static final float[] MEAN = {0.485f, 0.456f, 0.406f};
	/**
	 * ImageNet std（RGB 顺序）。
	 */
	private static final float[] STD = {0.229f, 0.224f, 0.225f};
	private static final float SCALE = 1.0f / 255.0f;

	private final int cropSize;
	private final int resizeShort;

	/**
	 * 使用默认参数 (cropSize=224, resizeShort=256) 创建文档方向预处理器。
	 */
	public DocOrientationPreprocessor() {
		this(CROP_SIZE, RESIZE_SHORT);
	}

	/**
	 * 创建文档方向预处理器。
	 *
	 * @param cropSize    中心裁剪尺寸
	 * @param resizeShort 短边 resize 长度
	 */
	public DocOrientationPreprocessor(int cropSize, int resizeShort) {
		if (cropSize <= 0) {
			throw new IllegalArgumentException("cropSize must be > 0, got " + cropSize);
		}
		if (resizeShort <= cropSize) {
			throw new IllegalArgumentException("resizeShort must be > cropSize, got " + resizeShort + "/" + cropSize);
		}
		this.cropSize = cropSize;
		this.resizeShort = resizeShort;
	}

	/**
	 * 执行预处理。
	 *
	 * <p>输入 BGR 图像，内部按 RGB 顺序应用 mean/std（与 PaddleX 官方实现一致）。
	 *
	 * @param imgBgr BGR 图像 (H, W, 3) uint8
	 * @return 预处理结果
	 * @throws IllegalArgumentException 输入为 null / 空 / 不是 8bit 3 通道 Mat
	 */
	public Result call(Mat imgBgr) {
		if (imgBgr == null || imgBgr.empty()) {
			throw new IllegalArgumentException("imgBgr must not be null or empty");
		}
		requireBgrU8(imgBgr);
		int srcH = imgBgr.rows();
		int srcW = imgBgr.cols();

		// resizeShort 会返回「新建 Mat」或「原图」：两种 case 的内存归属不同
		Mat resized = resizeShort(imgBgr);
		boolean ownsResized = (resized != imgBgr);
		// cropped 是 resized 的子 Mat 视图（共享底层数据，OpenCV Mat 走引用计数）：
		// 视图本身也持有一份引用，必须 release，否则 resized 的 native 缓冲
		// 会一直挂到 GC 才回收。
		Mat cropped = centerCrop(resized, cropSize);
		try {
			float[] normalized = normalizeAndToChw(cropped);
			// 注意：BGR 输入但按 RGB 顺序应用 mean/std（与官方 PP-LCNet 训练时一致）
			return new Result(normalized, new int[]{1, CHANNELS, cropSize, cropSize}, srcH, srcW);
		} finally {
			// 先释放子视图、再释放本体：引用计数 2 → 1 → 0，数据在本体释放时才真正回收
			cropped.release();
			if (ownsResized) {
				resized.release();
			}
		}
	}

	/**
	 * 短边 resize：保证短边 == resizeShort，长边按比例缩放。
	 */
	private Mat resizeShort(Mat img) {
		int h = img.rows();
		int w = img.cols();
		int shortSide = Math.min(h, w);
		if (shortSide == resizeShort) {
			// 不需要 resize，原图返回
			return img;
		}
		double ratio = (double) resizeShort / shortSide;
		int newH = (int) Math.round(h * ratio);
		int newW = (int) Math.round(w * ratio);
		Mat out = new Mat();
		try {
			Imgproc.resize(img, out, new Size(newW, newH), 0, 0, Imgproc.INTER_LINEAR);
			return out;
		} catch (RuntimeException | Error e) {
			out.release();
			throw e;
		}
	}

	/**
	 * 中心裁剪到 (cropSize, cropSize)。
	 */
	private Mat centerCrop(Mat img, int crop) {
		int h = img.rows();
		int w = img.cols();
		int y0 = (h - crop) / 2;
		int x0 = (w - crop) / 2;
		if (y0 < 0 || x0 < 0) {
			// 极端情况：resize 后还不到 crop 大小（不应发生，因为 resizeShort > cropSize）
			// 退化为左上角裁剪
			y0 = Math.max(0, y0);
			x0 = Math.max(0, x0);
		}
		// OpenCV 不直接支持越界裁剪，构造子 Mat 视图即可
		return new Mat(img, new org.opencv.core.Rect(x0, y0, crop, crop));
	}

	/**
	 * 校验输入是 8bit 3 通道（CV_8UC3）Mat。
	 *
	 * <p>归一化按「每像素 3 字节」逐行读取（{@code Mat.get(row, 0, byte[])}）。
	 * 该 API 从 (row, 0) 起<b>连续读满给定数组长度</b>，而不是"读一行"：
	 * 通道数不是 3 时会跨行读（&lt; 3）或通道步长错位（&gt; 3），
	 * 且不抛异常，会<b>静默产出错误的归一化张量</b>。
	 * 旧实现（{@code matToFlatHwc} + 硬编码 3 通道下标）在同样输入下越界即抛
	 * {@link ArrayIndexOutOfBoundsException}，故障模式由「响亮崩溃」退化为「静默错误」。
	 * 故此处显式校验，让失败可观测。
	 *
	 * @param img 输入 Mat
	 * @throws IllegalArgumentException 输入不是 CV_8UC3
	 */
	private static void requireBgrU8(Mat img) {
		if (img.type() != CvType.CV_8UC3) {
			throw new IllegalArgumentException(
				"imgBgr 必须是 8bit 3 通道 Mat（CV_8UC3），实际 "
					+ CvType.typeToString(img.type())
					+ "（channels=" + img.channels() + "）");
		}
	}

	/**
	 * 归一化 + HWC → CHW：输出 flat float[]，顺序为 C·H·W（C=3 通道）。
	 *
	 * <p>内存策略：直接逐行读 uint8 并写出 CHW，不 materialize 中间 HWC 数组，
	 * 也不生成 CV_32F 中间 Mat。
	 *
	 * <p>数值等价性：uint8 像素值 0~255 在 float32 中精确可表示，
	 * 故「uint8 → CV_32F → 运算」与「uint8 → 提升为 float → 运算」逐位相同。
	 *
	 * <p>逐行读取要求 Mat 连续（否则 {@code Mat.get(row, 0, byte[])} 会跨行读错），
	 * 非连续时先 clone。cropped 是从更大图上切出的子视图，通常非连续。
	 */
	private float[] normalizeAndToChw(Mat img) {
		int h = img.rows();
		int w = img.cols();
		int hw = h * w;
		int c = CHANNELS;
		float[] chw = new float[c * hw];
		Mat m = img.isContinuous() ? img : img.clone();
		try {
			byte[] rowBuf = new byte[w * c];
			for (int r = 0; r < h; r++) {
				m.get(r, 0, rowBuf);
				int rowBase = r * w;
				for (int col = 0; col < w; col++) {
					int i = rowBase + col;
					int b = col * c;
					float bgr0 = rowBuf[b] & 0xFF;      // B
					float bgr1 = rowBuf[b + 1] & 0xFF;  // G
					float bgr2 = rowBuf[b + 2] & 0xFF;  // R
					// 注意：BGR → 通道排列为 B/G/R，但 mean/std 仍按 R/G/B 顺序应用
					// 这是 PaddleX 官方预处理脚本的标准做法（保持 bit-exact 兼容）
					// 详见：https://github.com/PaddlePaddle/PaddleX/issues/4966
					chw[hw * 0 + i] = (bgr2 * SCALE - MEAN[0]) / STD[0];  // R
					chw[hw * 1 + i] = (bgr1 * SCALE - MEAN[1]) / STD[1];  // G
					chw[hw * 2 + i] = (bgr0 * SCALE - MEAN[2]) / STD[2];  // B
				}
			}
		} finally {
			if (m != img) {
				m.release();
			}
		}
		return chw;
	}

	/**
	 * 文档方向预处理结果。
	 *
	 * @param data  NCHW flat float[] 数据（C=3, H=cropSize, W=cropSize）
	 * @param shape [N, C, H, W] = [1, 3, 224, 224]
	 * @param srcH  原始图高
	 * @param srcW  原始图宽
	 */
	@lombok.Value
	@Accessors(fluent = true)
	public static class Result {
		private final float[] data;
		private final int[] shape;
		private final int srcH;
		private final int srcW;
	}
}
