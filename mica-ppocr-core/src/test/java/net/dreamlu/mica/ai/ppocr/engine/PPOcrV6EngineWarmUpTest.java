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

package net.dreamlu.mica.ai.ppocr.engine;

import net.dreamlu.mica.ai.ppocr.config.PPOcrV6Config;
import net.dreamlu.mica.ai.ppocr.utils.CollUtil;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 引擎<b>构造期自动预热</b>的测试。
 *
 * <p>预热的价值在于：ONNX Runtime 在每个 Session 的<b>首次</b>推理时才做计算图优化
 * 与内存池初始化，首次推理显著慢于后续。预热用一张合成图把 det + rec 两个分支都跑一遍，
 * 消除「滚动更新后首个慢请求打爆新实例」的事故。
 *
 * <p>预热是引擎的<b>初始化语义</b>（由构造器触发，{@code warmUp()} 为私有方法），
 * 因此本测试从「构造完成即已就绪」的角度验证，而不是去调一个公开方法。
 *
 * <p>测试图像全部为程序化生成的合成图，不依赖任何真实证件素材。
 */
class PPOcrV6EngineWarmUpTest {

	/**
	 * 本测试会直接调用 {@code PPOcrV6Engine.createWarmUpImage()}（不经过引擎实例），
	 * 引擎的构造期自动加载管不到这里，需自行加载 native 库。
	 */
	@BeforeAll
	static void loadOpenCv() {
		nu.pattern.OpenCV.loadLocally();
	}

	/**
	 * 引擎<b>构造期自动预热</b> —— 构造完成即已就绪，调用方无需显式触发。
	 */
	@Test
	@Timeout(180)
	void engineIsWarmedUpAfterConstruction() {
		PPOcrV6Config config = tinyConfig();

		try (PPOcrV6Engine engine = new PPOcrV6Engine(config)) {
			assertTrue(engine.isWarmedUp(), "引擎构造完成时应已自动预热");
			assertFalse(engine.isClosed(), "预热不应关闭引擎");
		}
	}

	/**
	 * 预热后引擎仍可正常推理 —— 预热不能破坏任何功能。
	 */
	@Test
	@Timeout(180)
	void warmUpKeepsEngineFullyFunctional() {
		PPOcrV6Config config = tinyConfig();

		try (PPOcrV6Engine engine = new PPOcrV6Engine(config)) {
			assertTrue(engine.isWarmedUp(), "预热成功后应标记为已预热");

			Mat image = PPOcrV6Engine.createWarmUpImage();
			try {
				assertFalse(engine.runMat(image).isEmpty(), "预热后推理仍应返回结果");
			} finally {
				image.release();
			}
		}
	}

	/**
	 * 预热失败<b>绝不能让引擎构造失败</b> —— 预热是「尽力而为」的优化手段。
	 *
	 * <p><b>失败注入方式（零新增素材）</b>：用 {@code doc_ori} 模型冒充 rec 模型。
	 * 该模型是固定 224×224 输入的 4 分类器，ONNX Session 能正常创建（构造期不报错），
	 * 但预热推理时 rec 的输入 shape（3×48×W）与之不匹配 ⇒ 必然抛错。
	 * 这正是「预热失败」的真实场景：环境有问题，但不是「模型文件缺失」那种连 Session 都建不出来的情况。
	 *
	 * <p>断言两件事：构造<b>成功</b>（没被预热失败拖垮）+ {@code isWarmedUp()} 保持 false
	 * （失败时不得误标为已预热）。
	 */
	@Test
	@Timeout(180)
	void warmUpFailureDoesNotBreakEngineConstruction() {
		Path modelDir = findRepositoryRoot().resolve("models/ppocr-v6");
		Path docOri = modelDir.resolve("doc_ori/doc_ori.onnx");
		Assumptions.assumeTrue(Files.isDirectory(modelDir.resolve("tiny")), "requires tiny models");
		Assumptions.assumeTrue(Files.isRegularFile(docOri), "requires doc_ori model");

		PPOcrV6Config config = PPOcrV6Config.builder()
			.detModelPath(modelDir.resolve("tiny/det.onnx").toString())
			// 故意用 doc_ori 冒充 rec：Session 可建，但推理 shape 不匹配 ⇒ 预热必失败
			.recModelPath(docOri.toString())
			.recCharDictPath(modelDir.resolve("tiny/dict.txt").toString())
			.build();

		try (PPOcrV6Engine engine = new PPOcrV6Engine(config)) {
			assertFalse(engine.isClosed(), "预热失败不应影响引擎构造");
			assertFalse(engine.isWarmedUp(), "预热失败时不得标记为已预热");
		}
	}

	/**
	 * 预热图必须能被 det 检出 —— 否则 rec 分支根本不会被执行到，
	 * 「预热全链路」就名不副实（纯白图会踩这个坑）。
	 */
	@Test
	@Timeout(180)
	void warmUpImageIsDetectableSoRecognitionBranchIsActuallyWarmed() {
		PPOcrV6Config config = tinyConfig();

		try (PPOcrV6Engine engine = new PPOcrV6Engine(config)) {
			Mat warmUpImage = PPOcrV6Engine.createWarmUpImage();
			try {
				PPOcrV6Engine.DetectResult detect = engine.detectMat(warmUpImage);
				assertTrue(detect.boxes().length > 0,
					"预热图必须能被 det 检出，否则 rec 分支不会被预热到");
			} finally {
				warmUpImage.release();
			}
		}
	}

	/**
	 * 预热图必须含足量笔画像素 —— 结构层面的护栏，防止后人把预热图简化回纯白 / 纯色块。
	 *
	 * <p>参考实现用纯白图预热，实测 det 检出 <b>0 框</b> ⇒ rec 分支根本不会被预热到。
	 * 本测试只断言图像<b>结构</b>（不依赖模型行为），因此模型升级不会让本测试无故变红；
	 * 模型相关的「能否检出」由 {@link #warmUpImageIsDetectableSoRecognitionBranchIsActuallyWarmed()} 负责。
	 *
	 * <p>同时锁定预热图尺寸：64×64 是「消罚效果 / 预热成本 / 检出稳健性」的最优点
	 * （见 {@code PPOcrV6Engine.createWarmUpImage()} 的对照表）——
	 * 放大只会让启动期成本线性上升而收益封顶，缩小则会脱离 det 的 limit_side_len 而依赖「小图被放大」。
	 */
	@Test
	void warmUpImageContainsStrokesSoItIsNotABlankCanvas() {
		Mat warmUpImage = PPOcrV6Engine.createWarmUpImage();
		try {
			assertEquals(CvType.CV_8UC3, warmUpImage.type(), "预热图应为 BGR 三通道");
			assertEquals(64, warmUpImage.cols(), "预热图宽度");
			assertEquals(64, warmUpImage.rows(), "预热图高度");

			Mat gray = new Mat();
			Mat binary = new Mat();
			try {
				Imgproc.cvtColor(warmUpImage, gray, Imgproc.COLOR_BGR2GRAY);
				// 反相阈值：白底(255)→0，黑笔画(0)→255，再统计非零像素即「笔画像素数」。
				// 注意不能直接用 Core.countNonZero(gray) —— 那是数「非零」，白底 255 全会被算进去。
				Imgproc.threshold(gray, binary, 250, 255, Imgproc.THRESH_BINARY_INV);
				int darkPixels = Core.countNonZero(binary);
				int totalPixels = gray.rows() * gray.cols();

				// 2 个字位 × 每字位约 56 像素 ≈ 112，取 80 作下限
				assertTrue(darkPixels > 80,
					"预热图应含足量笔画像素，实际仅 " + darkPixels + " 个 —— 是否被简化成了空白图？");
				assertTrue(darkPixels < totalPixels / 2,
					"预热图应是白底黑笔画，实测非白像素 " + darkPixels + " / " + totalPixels + "，疑似整图反色");
			} finally {
				binary.release();
				gray.release();
			}
		} finally {
			warmUpImage.release();
		}
	}

	/**
	 * 预热图必须在<b>任意 det 预处理参数</b>下都能被检出 —— 静默退化的护栏。
	 *
	 * <p>det 预处理默认 {@code limitType=min, limitSideLen=64}：短边 &lt; 64 的图会被<b>放大</b>到 64，
	 * 短边 ≥ 64 的图<b>原样</b>进模型。⇒ 预热图若小到依赖「被放大」才能被检出，
	 * 一旦 det 参数换成 PaddleOCR 常见的 {@code limitType=max, limitSideLen=960}，
	 * 预热图就会原样进模型、<b>检出 0 框</b> ⇒ rec 分支根本没被执行到 ⇒
	 * 预热静默退化成「只预热 det 一半」，而且<b>没有任何报错</b>。
	 *
	 * <p>实测（tiny 档）：30×30 在两种参数下的检出框数为 1 / <b>0</b>；
	 * 60×60 = 1 / 1；<b>64×64 = 1 / 1</b>；120×120 = 2 / 2。
	 * ⇒ 64×64 = det 的 limit_side_len，是最小安全尺寸。
	 *
	 * <p>本测试把这条约束钉死：后人若把预热图缩到 30×30，测试会立即变红，
	 * 而不是等到线上「预热好像没生效」才发现 rec 分支压根没跑。
	 */
	@Test
	@Timeout(300)
	void warmUpImageIsDetectableUnderAnyDetLimitConfig() {
		Mat warmUpImage = PPOcrV6Engine.createWarmUpImage();
		try {
			// 默认 det 参数：limitType=min, limitSideLen=64
			try (PPOcrV6Engine engine = new PPOcrV6Engine(tinyConfig("min", 64))) {
				assertTrue(engine.detectMat(warmUpImage).boxes().length > 0,
					"预热图在 det(min,64) 下必须能被检出");
			}
			// PaddleOCR 标准 det 参数：limitType=max, limitSideLen=960
			try (PPOcrV6Engine engine = new PPOcrV6Engine(tinyConfig("max", 960))) {
				assertTrue(engine.detectMat(warmUpImage).boxes().length > 0,
					"预热图在 det(max,960) 下必须能被检出 —— 否则换用 max 限长时预热会静默退化成「只预热 det 一半」");
			}
		} finally {
			warmUpImage.release();
		}
	}

	// ==================================================================
	// 辅助方法
	// ==================================================================

	/**
	 * 构造 tiny 档配置；模型缺失时跳过测试。
	 *
	 * @return tiny 档配置
	 */
	private static PPOcrV6Config tinyConfig() {
		return tinyConfig("min", 64);
	}

	/**
	 * 构造指定 det 预处理参数的 tiny 档配置；模型缺失时跳过测试。
	 *
	 * @param detLimitType  det 限长类型（min / max）
	 * @param detLimitSideLen det 限长
	 * @return tiny 档配置
	 */
	private static PPOcrV6Config tinyConfig(String detLimitType, int detLimitSideLen) {
		Path modelDir = findRepositoryRoot().resolve("models/ppocr-v6/tiny");
		Assumptions.assumeTrue(Files.isDirectory(modelDir), "requires tiny models");
		return PPOcrV6Config.builder()
			.detModelPath(modelDir.resolve("det.onnx").toString())
			.recModelPath(modelDir.resolve("rec.onnx").toString())
			.recCharDictPath(modelDir.resolve("dict.txt").toString())
			.detLimitType(detLimitType)
			.detLimitSideLen(detLimitSideLen)
			.build();
	}

	/**
	 * 定位仓库根目录（含 models/ppocr-v6/tiny）。
	 *
	 * @return 仓库根目录
	 */
	private static Path findRepositoryRoot() {
		String multiModuleDir = System.getProperty("maven.multiModuleProjectDirectory");
		if (multiModuleDir != null) {
			return CollUtil.pathOf(multiModuleDir);
		}
		Path current = CollUtil.pathOf("").toAbsolutePath();
		while (current != null && !Files.isDirectory(current.resolve("models/ppocr-v6/tiny"))) {
			current = current.getParent();
		}
		if (current == null) {
			throw new IllegalStateException("repository root with test models not found");
		}
		return current;
	}
}
