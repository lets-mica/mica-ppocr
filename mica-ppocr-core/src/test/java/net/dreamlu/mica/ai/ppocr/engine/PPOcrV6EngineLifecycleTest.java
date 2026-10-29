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
import net.dreamlu.mica.ai.ppocr.utils.BufferedImageUtils;
import net.dreamlu.mica.ai.ppocr.utils.CollUtil;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.imgcodecs.Imgcodecs;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 引擎生命周期（关闭）与在途推理并发的回归测试。
 *
 * <p><b>被守护的缺陷</b>：修复前 {@code PPOcrV6Engine.close()} 完全无同步，
 * 可能在另一个线程正执行 {@code detSession.run(...)} / {@code recSession.run(...)}
 * 时释放 native ONNX 会话 —— native 句柄被 free 后，在途 native 代码访问已释放内存，
 * 表现为 <b>SIGSEGV（JVM 级崩溃，不可捕获）</b>。
 *
 * <p><b>修复方式</b>：引入生命周期读写锁（公平模式）。推理入口持读锁（可并发），
 * {@code close()} 持写锁，因此「释放 native 会话」永远不会与在途推理重叠。
 *
 * <p><b>当前语义（有界等待 + 超时延迟释放）</b>：
 * <ul>
 *   <li>{@code close()} 最多等待 {@code closeWaitTimeoutMillis}（默认 1s）让在途推理自然结束；</li>
 *   <li>超时则引擎立即退役（拒新请求），但<b>不释放</b> native 会话 ——
 *       释放动作延后到在途推理结束后由守护线程完成；</li>
 *   <li>⇒ 关停不会被长耗时推理拖住，同时绝不会出现 use-after-free 崩溃。</li>
 * </ul>
 *
 * <p><b>测试策略</b>：不靠 sleep 猜时机。测试通过包级可见的
 * {@link PPOcrV6Engine#activeReaderCount()} 精确观测「推理线程是否已进入引擎内部」，
 * 再触发 close()，从而让本测试<b>确定性</b>地覆盖竞态窗口。
 *
 * <p>测试图像为<b>程序化生成的合成图</b>（不依赖任何真实证件素材）。
 */
class PPOcrV6EngineLifecycleTest {

	/**
	 * 本测试在构造引擎<b>之前</b>就用 OpenCV 合成测试图，
	 * 引擎的构造期自动加载管不到这里，需自行加载 native 库。
	 */
	@BeforeAll
	static void loadOpenCv() {
		nu.pattern.OpenCV.loadLocally();
	}

	/**
	 * 新语义核心：close() 是<b>有界等待</b>，不会被在途推理拖住；
	 * 超时后引擎立即退役，但 native 会话<b>不被释放</b>（在途推理仍在用它）。
	 *
	 * <p>断言三件事：
	 * <ol>
	 *   <li>{@code close()} 在有界时间内返回（远早于在途推理结束）；</li>
	 *   <li>close() 返回时在途推理<b>仍在运行</b> ⇒ 证明走的是「超时 → 延迟释放」路径，
	 *       native 会话没有被提前释放；</li>
	 *   <li>在途推理不会因 native 释放而崩溃（use-after-free 的症状是 SIGSEGV /
	 *       {@code ORT_INVALID_ARGUMENT - Invalid input name: x}）。</li>
	 * </ol>
	 */
	@Test
	@Timeout(180)
	void closeReturnsWithinBoundedWaitInsteadOfBlockingOnInFlightInference() throws Exception {
		PPOcrV6Config config = tinyConfig();
		// 2400×1800 合成图：实测推理约 1.3s，远大于 100ms 的等待上限，
		// 保证 close() 返回时推理必然还在跑（不依赖机器快慢）。
		Mat image = syntheticImage(2400, 1800);

		AtomicReference<Throwable> workerError = new AtomicReference<>();

		try (PPOcrV6Engine engine = new PPOcrV6Engine(config)) {
			// 把等待上限压到 100ms，让「超时路径」确定生效
			engine.closeWaitTimeoutMillis(100);

			Thread worker = new Thread(() -> {
				try {
					engine.runMat(image);
				} catch (Throwable t) {
					workerError.set(t);
				}
			}, "ppocr-lifecycle-worker");
			worker.start();

			// 精确等待推理线程进入引擎内部（持有生命周期读锁）
			assertTrue(awaitReaderEntered(engine, worker),
				"推理线程未能在超时内进入引擎内部，无法覆盖并发窗口");

			long startNanos = System.nanoTime();
			engine.close();
			long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

			assertTrue(engine.isClosed(), "close() 返回后引擎应处于已关闭状态");
			assertTrue(elapsedMs < 1000,
				"close() 不应被在途推理拖住，实际耗时 " + elapsedMs + " ms（等待上限 100ms）");
			assertTrue(worker.isAlive(),
				"close() 返回时在途推理应仍在运行 —— 说明走的是「延迟释放」路径，native 会话未被提前释放");

			worker.join(TimeUnit.SECONDS.toMillis(120));
			assertFalse(worker.isAlive(), "在途推理未在预期时间内结束");

			// 关键安全属性：绝不能出现 native 层崩溃（use-after-free 的症状）。
			// 允许的唯一异常是「引擎已关闭」导致的中断（关停场景下属预期行为）。
			Throwable err = workerError.get();
			if (err != null) {
				assertTrue(err instanceof IllegalStateException,
					"在途推理只允许因「引擎已关闭」而中断，绝不允许 native 层崩溃，实际: " + err);
				assertTrue(String.valueOf(err.getMessage()).contains("closed"),
					"中断原因应为「引擎已关闭」，实际: " + err.getMessage());
			}

			// 在途推理结束后，延迟释放线程必须把 native 会话释放掉 ——
			// 否则就是静默泄漏（没有报错、只是内存一直不还）。
			assertTrue(awaitDeferredRelease(engine),
				"在途推理结束后 native 会话仍未释放（静默泄漏）");
		} finally {
			image.release();
		}
	}

	/**
	 * 覆盖「嵌套读锁」路径：{@code run(byte[])} 持读锁后内部再调 {@code runMat(...)} 会<b>再次</b>取读锁。
	 *
	 * <p>这是 Spring Boot 上传（{@code MultipartFile.getBytes()}）的实际入口。
	 *
	 * <p><b>本用例锁住的是新语义下的死锁风险</b>：close() 超时后会启动「延迟释放」线程，
	 * 它会<b>排队等写锁</b>；而此时在途推理正在做<b>嵌套取读锁</b>。
	 * 若公平模式下嵌套取读锁被排队中的写锁挡住 ⇒
	 * 在途推理等读锁、延迟释放线程等写锁 ⇒ <b>互相死锁</b>。
	 *
	 * <p>已实测（独立小程序 + JDK 17）：公平模式下嵌套取读锁 <b>0ms</b> 直接拿到，
	 * 不会被排队写锁阻塞（读锁可重入）。本用例即为该结论的回归护栏。
	 */
	@Test
	@Timeout(180)
	void nestedReadLockOnRunBytesPathDoesNotDeadlockWithConcurrentClose() throws Exception {
		PPOcrV6Config config = tinyConfig();
		// 3600×2700：PNG 编解码本身就要几百毫秒，保证「延迟释放线程」先于
		// 工作线程的嵌套取读锁进入排队状态。
		Mat image = syntheticImage(3600, 2700);
		byte[] imageBytes = readAsPngBytes(image);

		AtomicReference<Throwable> workerError = new AtomicReference<>();

		try (PPOcrV6Engine engine = new PPOcrV6Engine(config)) {
			// 等待上限设为 0 ⇒ close() 立即进入「延迟释放」路径，写锁开始排队
			engine.closeWaitTimeoutMillis(0);

			Thread worker = new Thread(() -> {
				try {
					engine.run(imageBytes);
				} catch (Throwable t) {
					workerError.set(t);
				}
			}, "ppocr-lifecycle-nested-worker");
			worker.start();

			assertTrue(awaitReaderEntered(engine, worker),
				"推理线程未能在超时内进入引擎内部，无法覆盖并发窗口");

			engine.close();

			// 若嵌套取读锁被排队的写锁挡住，这里会一直卡住
			worker.join(TimeUnit.SECONDS.toMillis(120));
			assertFalse(worker.isAlive(), "在途推理未在预期时间内结束（疑似与延迟释放线程死锁）");

			Throwable err = workerError.get();
			if (err != null) {
				assertTrue(err instanceof IllegalStateException,
					"在途推理只允许因「引擎已关闭」而中断，绝不允许 native 层崩溃，实际: " + err);
			}
		} finally {
			image.release();
		}
	}

	/**
	 * 关闭后应快速失败（可捕获的 IllegalStateException），而不是撞 native 崩溃。
	 */
	@Test
	@Timeout(120)
	void engineRejectsFurtherCallsAfterClose() throws Exception {
		PPOcrV6Config config = tinyConfig();
		Mat image = syntheticImage(600, 400);

		try (PPOcrV6Engine engine = new PPOcrV6Engine(config)) {
			assertFalse(engine.isClosed(), "新建引擎不应处于已关闭状态");

			engine.close();
			assertTrue(engine.isClosed(), "close() 后 isClosed() 应为 true");

			// 幂等：重复 close 不抛异常
			engine.close();
			assertTrue(engine.isClosed(), "重复 close() 后仍应为已关闭状态");

			IllegalStateException e = assertThrows(IllegalStateException.class,
				() -> engine.runMat(image));
			assertTrue(e.getMessage().contains("closed"),
				"异常信息应说明引擎已关闭，实际: " + e.getMessage());
		} finally {
			image.release();
		}
	}

	/**
	 * 压力测试：多线程并发推理 + 并发 close，期间不允许出现任何非预期异常
	 * （尤其是 native 层的 SIGSEGV / IllegalStateException 之外的错误）。
	 */
	@Test
	@Timeout(300)
	void concurrentInferenceWithConcurrentCloseProducesNoUnexpectedFailure() throws Exception {
		PPOcrV6Config config = tinyConfig();

		int threadCount = 4;
		List<Throwable> unexpected = Collections.synchronizedList(new ArrayList<>());
		List<Mat> images = new ArrayList<>();
		List<Thread> workers = new ArrayList<>();
		AtomicBoolean stop = new AtomicBoolean(false);

		try (PPOcrV6Engine engine = new PPOcrV6Engine(config)) {
			for (int i = 0; i < threadCount; i++) {
				Mat image = syntheticImage(800, 600);
				images.add(image);
				Thread t = new Thread(() -> {
					while (!stop.get() && !engine.isClosed()) {
						try {
							engine.runMat(image);
						} catch (IllegalStateException e) {
							// close 生效后被拒绝 —— 预期行为
							return;
						} catch (Throwable other) {
							unexpected.add(other);
							return;
						}
					}
				}, "ppocr-lifecycle-stress-" + i);
				workers.add(t);
				t.start();
			}

			// 等到确实有推理在途，再触发并发关闭
			boolean entered = false;
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
			while (System.nanoTime() < deadline) {
				if (engine.activeReaderCount() > 0) {
					entered = true;
					break;
				}
				Thread.sleep(1);
			}
			assertTrue(entered, "未能观测到在途推理，无法覆盖并发窗口");

			engine.close();
			stop.set(true);

			for (Thread t : workers) {
				t.join(TimeUnit.SECONDS.toMillis(120));
				assertFalse(t.isAlive(), "工作线程未在预期时间内结束");
			}

			assertTrue(unexpected.isEmpty(), "并发 close 期间出现非预期异常: " + unexpected);
			assertTrue(engine.isClosed());
		} finally {
			for (Mat image : images) {
				image.release();
			}
		}
	}

	// ==================================================================
	// 辅助方法
	// ==================================================================

	/**
	 * 等待推理线程进入引擎内部（生命周期读锁持有者 &gt; 0）。
	 *
	 * <p>用包级可见的 {@link PPOcrV6Engine#activeReaderCount()} 精确观测，
	 * 避免用固定 sleep 猜时机导致测试不稳定。
	 *
	 * @param engine 引擎
	 * @param worker 推理线程
	 * @return 是否观测到在途推理
	 * @throws InterruptedException 线程被中断
	 */
	private static boolean awaitReaderEntered(PPOcrV6Engine engine, Thread worker) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
		while (System.nanoTime() < deadline) {
			if (engine.activeReaderCount() > 0) {
				return true;
			}
			if (!worker.isAlive()) {
				// 推理线程已结束且没观测到在途状态 —— 视为未覆盖到竞态窗口
				return false;
			}
			Thread.sleep(1);
		}
		return false;
	}

	/**
	 * 等待「延迟释放」完成（在途推理结束后，守护线程释放 native 会话）。
	 *
	 * @param engine 引擎
	 * @return 是否在超时内完成释放
	 * @throws InterruptedException 线程被中断
	 */
	private static boolean awaitDeferredRelease(PPOcrV6Engine engine) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
		while (System.nanoTime() < deadline) {
			if (engine.isDeferredReleaseCompleted()) {
				return true;
			}
			Thread.sleep(5);
		}
		return false;
	}

	/**
	 * 构造 tiny 档配置；模型缺失时跳过测试。
	 *
	 * @return tiny 档配置
	 */
	private static PPOcrV6Config tinyConfig() {
		Path modelDir = findRepositoryRoot().resolve("models/ppocr-v6/tiny");
		Assumptions.assumeTrue(Files.isDirectory(modelDir), "requires tiny models");
		return PPOcrV6Config.builder()
			.detModelPath(modelDir.resolve("det.onnx").toString())
			.recModelPath(modelDir.resolve("rec.onnx").toString())
			.recCharDictPath(modelDir.resolve("dict.txt").toString())
			.build();
	}

	/**
	 * 程序化生成合成测试图：白底 + 若干黑色矩形块，模拟「多行文本」版面。
	 *
	 * <p>不依赖任何真实证件素材；块数量随尺寸缩放，因此大图真的有更多文本块
	 * （实测 tiny 档：1200×900 ≈ 425ms，2400×1800 ≈ 1266ms，3600×2700 ≈ 2921ms）。
	 *
	 * @param width  宽
	 * @param height 高
	 * @return BGR Mat，由调用方负责 release
	 */
	private static Mat syntheticImage(int width, int height) {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		try {
			g.setColor(Color.WHITE);
			g.fillRect(0, 0, width, height);
			g.setColor(Color.BLACK);
			int rows = Math.max(4, height / 150);
			int cols = Math.max(3, width / 220);
			int blockW = width / cols - 40;
			int blockH = height / rows - 30;
			for (int row = 0; row < rows; row++) {
				int y = 40 + row * (height / rows);
				for (int col = 0; col < cols; col++) {
					int x = 60 + col * (width / cols);
					g.fillRect(x, y, blockW, blockH);
				}
			}
		} finally {
			g.dispose();
		}
		return BufferedImageUtils.toBgrMat(image);
	}

	/**
	 * 把 Mat 编码为 PNG 字节（走 {@code run(byte[])} 通道）。
	 *
	 * @param image 源图
	 * @return PNG 字节
	 */
	private static byte[] readAsPngBytes(Mat image) {
		MatOfByte buffer = new MatOfByte();
		try {
			Imgcodecs.imencode(".png", image, buffer);
			return buffer.toArray();
		} finally {
			buffer.release();
		}
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
