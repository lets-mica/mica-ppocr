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

package net.dreamlu.mica.ai.ppocr.solon;

import ai.onnxruntime.OrtSession.SessionOptions.ExecutionMode;
import lombok.Data;
import net.dreamlu.mica.ai.ppocr.config.PPOcrV6Config;
import org.noear.solon.annotation.BindProps;
import org.noear.solon.annotation.Configuration;

/**
 * PP-OCR 配置属性。
 *
 * <p>对应 mica.ai.ppocr 配置前缀。
 */
@Data
@Configuration
@BindProps(prefix = "mica.ai.ppocr")
public class PPOCRProperties implements PPOcrV6Config.Source {

	/**
	 * 是否启用该 Starter。默认 true：启用时必填的 det/rec 模型路径及字典缺失将启动失败；
	 * 设为 false 时整个 Starter 不注入任何 Bean。
	 */
	private boolean enabled = true;

	/**
	 * 检测模型路径（必填）
	 */
	private String detModelPath;

	/**
	 * 识别模型路径（必填）
	 */
	private String recModelPath;

	/**
	 * 识别字符字典路径（必填）
	 */
	private String recCharDictPath;

	/**
	 * 检测图像限制边长（默认 64）。
	 *
	 * <p>与 {@link #detLimitType} 配合使用：长边 / 短边超过此值时按比例缩放。
	 * 默认组合 {@code 64 + "min"} 面向证件 / 卡证类小图场景，
	 * 保证短边至少 64 像素；通用文档 / 自然场景可改为较大值 + "max"。
	 */
	private int detLimitSideLen = 64;

	/**
	 * 检测限制类型（默认 "min"）：min（短边限制） / max（长边限制）。
	 */
	private String detLimitType = "min";

	/**
	 * 检测最大边长限制
	 */
	private int detMaxSideLimit = 4000;

	/**
	 * 检测阈值
	 */
	private float detThresh = 0.3f;

	/**
	 * 检测框阈值
	 */
	private float detBoxThresh = 0.6f;

	/**
	 * 检测 unclip 比例
	 */
	private float detUnclipRatio = 1.5f;

	/**
	 * 识别输入 shape [C, H, W]
	 */
	private int[] recImageShape = {3, 48, 320};

	/**
	 * 识别批处理大小
	 */
	private int recBatchSize = 6;

	/**
	 * 是否优先使用 GPU 加速（默认 false，强制 CPU 保证跨平台 bit-exact）
	 */
	private boolean preferAccelerator = false;

	/**
	 * 是否启用文档方向分类（PP-OCRv6 use_doc_orientation_classify，对应 PP-LCNet_x1_0_doc_ori）
	 */
	private boolean useDocOrientationClassify = false;

	/**
	 * 文档方向分类模型路径（useDocOrientationClassify=true 时必填）
	 */
	private String docOrientationModelPath;

	/**
	 * 文档方向分类置信度阈值，低于此值视为 0°（不旋转）。范围 [0, 1]，默认 0.4。
	 *
	 * <p>采用 {@code 0.4} 作为经验阈值。取值依据（实测 doc_ori 模型的 4 类 softmax 概率）：
	 * <ul>
	 *   <li>idcard1（手机横拍、270° 倒置）：score=0.430 → 必须 ≥ 0.4 才能正确旋转</li>
	 *   <li>taxi1 / taxi3（正向图、doc_ori 误判 180°）：score=0.387/0.396 → 必须 > 0.4 才能丢弃</li>
	 *   <li>其它 taxi2/4/5、train1~5 全部 score &lt; 0.3，0.4 阈值也不会误触发</li>
	 * </ul>
	 *
	 * <p>{@code 0.4} 是当前样本集下"误判丢弃 / 误判旋转"的最佳折中点。
	 * 调高（如 0.5）会让 idcard1 类真实倒置图失去旋转机会；
	 * 调低（如 0.3）会让 taxi1/3 这种 doc_ori 弱信号被误触，反而把正向图转成 180°。
	 */
	private float docOrientationThresh = 0.4f;

	/**
	 * ONNX 内部线程数
	 */
	private int intraOpNumThreads = 1;

	/**
	 * ONNX 交互线程数
	 */
	private int interOpNumThreads = 1;

	/**
	 * ONNX Runtime 执行模式（默认 "sequential"）：sequential / parallel（大小写不敏感）。
	 *
	 * <p>sequential：计算图节点按拓扑序逐个执行，内存占用低，PP-OCR 默认流水线足够；
	 * parallel：无依赖节点并行执行，吞吐更高，但需要 {@link #interOpNumThreads} &gt; 1 且内存占用更高。
	 * 非法值回退 sequential。
	 */
	private ExecutionMode execMode = ExecutionMode.SEQUENTIAL;

	/**
	 * 是否启用 ONNX Runtime CPU memory arena（默认 false，关闭）。
	 *
	 * <p>arena 为后续推理预留并复用内存，输入 shape 固定时减少 malloc 开销；
	 * 但 OCR 输入分辨率随图片变化，arena 高水位会随历史最大图持续抬升且不归还 OS，
	 * 内存受限环境（Docker）表现为内存持续增长直至 OOM（issue #14）。
	 * 关闭后临时内存用完即释放，吞吐损失约 10%。
	 */
	private boolean enableCpuMemArena = false;

	/**
	 * 是否启用 ONNX Runtime 内存模式优化（默认 false，关闭，与 {@link #enableCpuMemArena} 同步使用）。
	 *
	 * <p>首次推理时按计算图预分配中间激活张量并复用，shape 固定时收益明显；
	 * shape 变化时会按新图重新规划，与 CPU arena 一起在容器 / 内存受限环境下持续吃内存，
	 * 建议保持关闭。关闭后每次推理按需分配 / 释放临时张量，内存峰值可控。
	 */
	private boolean enableMemoryPattern = false;

	/**
	 * PDF 通道配置（仅当 classpath 含 mica-ppocr-pdf 时生效）。
	 *
	 * <p>对应 yml：
	 * <pre>
	 * mica:
	 *   ai:
	 *     ppocr:
	 *       pdf:
	 *         render-dpi: 200
	 *         min-text-chars: 10
	 *         min-readable-ratio: 0.6
	 *         force-ocr: false
	 * </pre>
	 */
	private Pdf pdf = new Pdf();

	/**
	 * PDF 通道子配置。
	 */
	@Data
	public static class Pdf {
		/**
		 * PDF 渲染通道 DPI（默认 200）。调高提升小字精度，代价是渲染耗时与内存。
		 */
		private int renderDpi = 200;
		/**
		 * 文本层判定的最少字符数。低于此值视为扫描件，强制走 OCR 通道。
		 */
		private int minTextChars = 10;
		/**
		 * 文本层判定中可读字符的最低占比（0~1）。低于此值视为扫描件，强制走 OCR 通道。
		 */
		private double minReadableRatio = 0.6;
		/**
		 * 强制走 OCR 通道（跳过文本层判定与抽文）。用于已知是扫描件但 PDF 仍被标了文本层的场景。
		 */
		private boolean forceOcr = false;
	}
}
