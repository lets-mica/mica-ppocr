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

package net.dreamlu.mica.ai.ppocr.config;

import ai.onnxruntime.OrtSession.SessionOptions.ExecutionMode;
import lombok.Builder;
import lombok.Getter;

/**
 * PP-OCRv6 引擎配置。
 *
 * <p>使用 Builder 模式构建，所有参数均有合理默认值。
 */
@Getter
@Builder(toBuilder = true)
public final class PPOcrV6Config {

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
	 * 检测：限制边长（默认 64）。
	 *
	 * <p>当原图长边或短边（由 {@link #detLimitType} 决定）超过该值时按比例缩放，
	 * 否则保持原图分辨率。64 + {@code detLimitType="min"} 是 PP-OCR 面向
	 * 证件 / 卡证类小图场景的经典组合：保证短边至少 64，像素信息充足，
	 * 对 4K/2K 大图也不会做无效放大。
	 */
	@Builder.Default
	private int detLimitSideLen = 64;

	/**
	 * 检测：限制类型，min 或 max（默认 min）。
	 *
	 * <p>{@code min} 表示约束"短边"，{@code max} 表示约束"长边"。
	 * 证件类场景默认使用 {@code min}，配合较小的 {@link #detLimitSideLen}
	 * 即可获得稳定识别效果；通用文档 / 自然场景可改用 {@code max} + 较大边长。
	 */
	@Builder.Default
	private String detLimitType = "min";

	/**
	 * 检测：最大边长限制
	 */
	@Builder.Default
	private int detMaxSideLimit = 4000;

	/**
	 * 检测：DB 后处理二值化阈值
	 */
	@Builder.Default
	private float detThresh = 0.3f;

	/**
	 * 检测：DB 后处理 box 阈值
	 */
	@Builder.Default
	private float detBoxThresh = 0.6f;

	/**
	 * 检测：DB 后处理 unclip 比率
	 */
	@Builder.Default
	private float detUnclipRatio = 1.5f;

	/**
	 * 识别：输入图像形状 [C, H, W]
	 */
	@Builder.Default
	private int[] recImageShape = {3, 48, 320};

	/**
	 * 识别：批处理大小
	 */
	@Builder.Default
	private int recBatchSize = 6;

	/**
	 * 是否优先使用 GPU 加速（默认 false，强制 CPU）
	 */
	@Builder.Default
	private boolean preferAccelerator = false;

	/**
	 * 是否启用文档方向分类（PP-OCRv6 use_doc_orientation_classify，对应 PP-LCNet_x1_0_doc_ori）
	 */
	@Builder.Default
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
	@Builder.Default
	private float docOrientationThresh = 0.4f;

	/**
	 * ONNX Runtime 线程数
	 */
	@Builder.Default
	private int intraOpNumThreads = 1;

	/**
	 * ONNX Runtime 线程数
	 */
	@Builder.Default
	private int interOpNumThreads = 1;

	/**
	 * 是否启用 ONNX Runtime CPU memory arena（默认 false，关闭）。
	 *
	 * <p>arena 会为后续推理预留内存并复用，输入 shape 固定时能减少 malloc 开销；
	 * 但 OCR 场景输入分辨率随图片变化（det 输入为原始分辨率），
	 * arena 高水位会随历史出现过的最大图持续抬升，且<b>推理结束后不归还 OS</b>：
	 * <ul>
	 *   <li>识别同一张图（固定 shape）→ arena 复用，内存稳定</li>
	 *   <li>持续识别新图片（新 shape）→ arena 不断扩展，Docker 等内存受限环境下
	 *       表现为内存持续增长直至 OOM（issue #14）</li>
	 * </ul>
	 *
	 * <p>关闭后每次推理的临时内存用完即释放，内存占用稳定可控，
	 * 代价是推理吞吐约有 10% 左右的损耗（参考 RapidOCR 实测）。
	 * 仅当输入分辨率固定且追求极致吞吐时才建议开启。
	 */
	@Builder.Default
	private boolean enableCpuMemArena = false;

	/**
	 * 是否启用 ONNX Runtime 内存模式优化（默认 false，关闭，与 {@link #enableCpuMemArena} 同步使用）。
	 *
	 * <p>内存模式优化会在首次推理时按计算图预分配中间激活张量，
	 * 后续推理复用，shape 固定时收益明显；shape 变化时会按新图重新规划。
	 * 与 CPU arena 配合时（默认行为）二者都会持续吃内存；
	 * 在容器 / 内存受限环境下建议与 {@code enableCpuMemArena} 一起关闭。
	 *
	 * <p>关闭后每次推理按需分配/释放临时张量，内存峰值可控，
	 * 吞吐约有 10% 左右的损耗。
	 */
	@Builder.Default
	private boolean enableMemoryPattern = false;

	/**
	 * ONNX Runtime 执行模式（默认 "sequential"）：sequential / parallel（大小写不敏感）。
	 *
	 * <p>对应 {@code OrtSession.SessionOptions#setExecutionMode}：
	 * <ul>
	 *   <li>{@code sequential}（默认）：计算图节点按拓扑序逐个执行，内存占用低；
	 *       PP-OCR 的 det / rec 是独立 session 且流水线严格串行，串行模式足够。</li>
	 *   <li>{@code parallel}：计算图内无依赖的节点并行执行，吞吐更高，
	 *       但需要配合 {@link #interOpNumThreads} &gt; 1，且内存占用更高。</li>
	 * </ul>
	 *
	 * <p>非法值回退 {@code sequential} 并告警。
	 */
	@Builder.Default
	private ExecutionMode execMode = ExecutionMode.SEQUENTIAL;

	/**
	 * 返回使用全部默认字段的 PPOcrV6Config。
	 *
	 * @return 默认配置
	 */
	public static PPOcrV6Config defaults() {
		return builder().build();
	}

	/**
	 * 通用配置源：把任意具有同名 getter 的属性对象转换为 {@link PPOcrV6Config}。
	 *
	 * <p>用于消除 spring-boot / solon 两端自动配置中 18 行
	 * {@code builder().detModelPath(...).recModelPath(...)...} 复制：
	 * 两端只需让各自的 {@code PPOCRProperties} 实现本接口，
	 * 即可调用 {@code PPOcrV6Config.from(properties)} 一行完成转换。
	 *
	 * <p>core 不直接依赖 spring-boot / solon，避免编译期绑定两端框架；
	 * 接口契约以"用得到的 getter 名"为白名单，由两端实现自行暴露。
	 *
	 * @author L.cm
	 */
	public interface Source {
		String getDetModelPath();

		String getRecModelPath();

		String getRecCharDictPath();

		int getDetLimitSideLen();

		String getDetLimitType();

		int getDetMaxSideLimit();

		float getDetThresh();

		float getDetBoxThresh();

		float getDetUnclipRatio();

		int[] getRecImageShape();

		int getRecBatchSize();

		boolean isPreferAccelerator();

		boolean isUseDocOrientationClassify();

		String getDocOrientationModelPath();

		float getDocOrientationThresh();

		int getIntraOpNumThreads();

		int getInterOpNumThreads();

		ExecutionMode getExecMode();

		boolean isEnableCpuMemArena();

		boolean isEnableMemoryPattern();
	}

	/**
	 * 把 {@link Source} 配置源转换为 {@link PPOcrV6Config}。
	 *
	 * <p>约定：null 字段（如未配置的 docOrientationModelPath）会保留为 null，
	 * 由 {@link PPOcrV6Engine} 构造器按 {@code useDocOrientationClassify} 决定是否必填。
	 *
	 * @param source 配置源（spring-boot / solon 各自的 properties）
	 * @return 构造好的 PPOcrV6Config
	 */
	public static PPOcrV6Config from(Source source) {
		return builder()
			.detModelPath(source.getDetModelPath())
			.recModelPath(source.getRecModelPath())
			.recCharDictPath(source.getRecCharDictPath())
			.detLimitSideLen(source.getDetLimitSideLen())
			.detLimitType(source.getDetLimitType())
			.detMaxSideLimit(source.getDetMaxSideLimit())
			.detThresh(source.getDetThresh())
			.detBoxThresh(source.getDetBoxThresh())
			.detUnclipRatio(source.getDetUnclipRatio())
			.recImageShape(source.getRecImageShape())
			.recBatchSize(source.getRecBatchSize())
			.preferAccelerator(source.isPreferAccelerator())
			.useDocOrientationClassify(source.isUseDocOrientationClassify())
			.docOrientationModelPath(source.getDocOrientationModelPath())
			.docOrientationThresh(source.getDocOrientationThresh())
			.intraOpNumThreads(source.getIntraOpNumThreads())
			.interOpNumThreads(source.getInterOpNumThreads())
			.execMode(source.getExecMode())
			.enableCpuMemArena(source.isEnableCpuMemArena())
			.enableMemoryPattern(source.isEnableMemoryPattern())
			.build();
	}
}
