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
import net.dreamlu.mica.ai.ppocr.utils.NdArrayUtils;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.List;

/**
 * 识别模型预处理：resize → normalize → batch padding。
 *
 * <p>对应 Python 端的 rec 预处理：
 * <ol>
 *   <li>按目标高度(48)等比缩放</li>
 *   <li>归一化：(img / 255 - 0.5) / 0.5</li>
 *   <li>batch 内按最大宽度 padding 对齐，输出 NCHW 张量</li>
 * </ol>
 */
@ToString
public final class RecognitionPreprocessor {
	/**
	 * 通道数（RGB/BGR）。
	 */
	public static final int CHANNELS = 3;
	/**
	 * 默认目标高度。
	 */
	public static final int HEIGHT = 48;
	/**
	 * 默认最小宽度。
	 */
	public static final int W_MIN = 320;
	/**
	 * 默认最大宽度。
	 */
	public static final int W_MAX = 3200;

	private final int h;
	private final int wMin;
	private final int wMax;

	/**
	 * 使用默认参数 (h=48, wMin=320, wMax=3200) 创建识别预处理器。
	 */
	public RecognitionPreprocessor() {
		this(HEIGHT, W_MIN, W_MAX);
	}

	/**
	 * 创建识别预处理器。
	 *
	 * @param h    目标高度
	 * @param wMin 最小宽度
	 * @param wMax 最大宽度
	 */
	public RecognitionPreprocessor(int h, int wMin, int wMax) {
		if (h <= 0) {
			throw new IllegalArgumentException("h must be > 0");
		}
		if (wMin <= 0 || wMax <= 0 || wMax < wMin) {
			throw new IllegalArgumentException("invalid wMin/wMax: " + wMin + "/" + wMax);
		}
		this.h = h;
		this.wMin = wMin;
		this.wMax = wMax;
	}

	/**
	 * 执行批量预处理。
	 *
	 * <p>内存策略：归一化结果<b>直接写入 NCHW 输出数组</b>，不 materialize 中间 HWC 数组，
	 * 也不生成 CV_32F 中间 Mat。峰值内存 = 1 份 NCHW（4 字节/像素/通道）+ 1 行缓冲。
	 * 旧实现需要额外 2 份中间态（每图的 HWC float[] + CV_32F Mat），且必须同时持有全部图的
	 * HWC 数据才能进入第二轮 —— N=50 框、W=3200、H=48 时多占约 184 MiB。
	 *
	 * <p>数值等价性：uint8 像素值 0~255 在 float32 中精确可表示，
	 * 故「uint8 → CV_32F → 运算」与「uint8 → 提升为 float → 运算」逐位相同，
	 * 与 Python 参考实现保持 bit-exact。
	 *
	 * @param imgs 裁剪后的 BGR 文本行图像
	 * @return 预处理结果
	 */
	public Result call(List<Mat> imgs) {
		int n = imgs.size();
		if (n == 0) {
			return new Result(new float[0], new int[]{0, CHANNELS, h, 0});
		}

		// 第一轮：校验输入 + 计算每图目标宽度（不触碰像素），确定 batch 内 padding 宽度
		int[] actualWs = new int[n];
		int maxW = 0;
		for (int i = 0; i < n; i++) {
			Mat img = imgs.get(i);
			requireBgrU8(img, i);
			int srcH = img.rows();
			int srcW = img.cols();
			double whRatio = Math.max((double) wMin / h, (double) srcW / srcH);
			int targetW = (int) (h * whRatio);

			int actualW;
			if (targetW > wMax) {
				actualW = wMax;
				targetW = wMax;
			} else {
				actualW = Math.min(NdArrayUtils.ceilDiv(h * srcW, srcH), targetW);
			}
			actualWs[i] = actualW;
			if (targetW > maxW) {
				maxW = targetW;
			}
		}

		// 第二轮：逐图 resize → 归一化 → 直接写入 NCHW（含右侧 padding）
		int chwSize = CHANNELS * h * maxW;
		float[] data = new float[n * chwSize];
		for (int i = 0; i < n; i++) {
			Mat img = imgs.get(i);
			int actualW = actualWs[i];
			Mat resized = new Mat();
			try {
				Imgproc.resize(img, resized, new Size(actualW, h), 0, 0, Imgproc.INTER_LINEAR);
				writeNormalizedChw(resized, data, i * chwSize, h, actualW, maxW);
			} finally {
				resized.release();
			}
		}

		return new Result(data, new int[]{n, CHANNELS, h, maxW});
	}

	/**
	 * 校验输入是 8bit 3 通道（CV_8UC3）Mat。
	 *
	 * <p>归一化按「每像素 3 字节」逐行读取（{@code Mat.get(row, 0, byte[])}）。
	 * 该 API 从 (row, 0) 起<b>连续读满给定数组长度</b>，而不是"读一行"。
	 * OpenCV 有一道前置校验：<b>数组长度必须是通道数的整数倍</b>，否则抛
	 * {@code UnsupportedOperationException}（Provided data element number (N) should be
	 * multiple of the Mat channels count (C)）。校验通过后，通道数与「每像素 3 字节」
	 * 的假设不符时仍有两种<b>静默</b>错误：
	 * <ul>
	 *   <li>通道数 &lt; 3：行缓冲比一行长 → <b>跨行读</b>，把相邻行数据当本行像素。
	 *       1 通道时任何长度都是 1 的倍数，<b>永远绕得过那道校验</b> —— 灰度图必然静默出错；</li>
	 *   <li>通道数 &gt; 3：行缓冲比一行短 → 通道步长错位，首个像素对、后续全错。
	 *       仅当缓冲长度不是通道数倍数时，才会被上面那道校验拦下并抛异常。</li>
	 * </ul>
	 * 旧实现（{@code matToFlatHwc} + 硬编码 3 通道下标）在这些输入下越界即抛
	 * {@link ArrayIndexOutOfBoundsException}，故障模式由「响亮崩溃」退化为「静默错误」。
	 * 故此处显式校验，让失败可观测。
	 *
	 * @param img   输入 Mat
	 * @param index 在入参列表中的下标（用于错误信息定位）
	 * @throws IllegalArgumentException 输入不是 CV_8UC3
	 */
	private static void requireBgrU8(Mat img, int index) {
		if (img.type() != CvType.CV_8UC3) {
			throw new IllegalArgumentException(
				"imgs[" + index + "] 必须是 8bit 3 通道 Mat（CV_8UC3），实际 "
					+ CvType.typeToString(img.type())
					+ "（channels=" + img.channels() + "）");
		}
	}

	/**
	 * 归一化并直接写出 NCHW 中的一段（batch 内第 i 张图）。
	 *
	 * <p>归一化公式：{@code (img / 255 - 0.5) / 0.5}，等价于 {@code img / 127.5 - 1}。
	 *
	 * <p>逐行读取要求 Mat 连续（否则 {@code Mat.get(row, 0, byte[])} 会跨行读错），
	 * 非连续时先 clone。
	 *
	 * @param src      resize 后的 BGR uint8 Mat
	 * @param data     NCHW 输出数组
	 * @param destBase 本图在 data 中的起始下标
	 * @param h        目标高
	 * @param actualW  本图实际像素宽（≤ maxW，右侧为 padding）
	 * @param maxW     batch 内统一宽（padding 目标）
	 */
	private void writeNormalizedChw(Mat src, float[] data, int destBase, int h, int actualW, int maxW) {
		int c1 = destBase + h * maxW;
		int c2 = destBase + 2 * h * maxW;
		Mat m = src.isContinuous() ? src : src.clone();
		try {
			byte[] rowBuf = new byte[actualW * CHANNELS];
			for (int r = 0; r < h; r++) {
				m.get(r, 0, rowBuf);
				int rowOff = r * maxW;
				for (int col = 0; col < actualW; col++) {
					int b = col * CHANNELS;
					data[destBase + rowOff + col] = (rowBuf[b] & 0xFF) / 127.5f - 1.0f;
					data[c1 + rowOff + col] = (rowBuf[b + 1] & 0xFF) / 127.5f - 1.0f;
					data[c2 + rowOff + col] = (rowBuf[b + 2] & 0xFF) / 127.5f - 1.0f;
				}
			}
		} finally {
			if (m != src) {
				m.release();
			}
		}
	}

	/**
	 * rec 预处理结果。
	 */
	@lombok.Value
	@Accessors(fluent = true)
	public static class Result {
		/**
		 * NCHW flat float[] 数据
		 */
		float[] data;
		/**
		 * [N, C, H, W]
		 */
		int[] shape;
	}
}
