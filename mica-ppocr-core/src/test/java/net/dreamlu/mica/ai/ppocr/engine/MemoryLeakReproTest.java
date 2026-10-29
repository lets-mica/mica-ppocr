/*
 * Copyright (c) 2019-2026, dreamlu.net All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 */

package net.dreamlu.mica.ai.ppocr.engine;

import com.sun.management.OperatingSystemMXBean;
import com.sun.management.ThreadMXBean;
import net.dreamlu.mica.ai.ppocr.config.PPOcrV6Config;
import net.dreamlu.mica.ai.ppocr.utils.CollUtil;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Issue #14 回归测试：ONNX Runtime CPU arena + 内存模式优化默认关闭后，
 * 反复识别不同图片不应出现内存单调上涨。
 *
 * <p>指标（committed virtual memory，Windows 上含 JVM heap committed 等区域）：
 * <ul>
 *   <li>判定标准：<b>稳态斜率</b> —— 从参考点（round 5 与 round 10 中<b>较大</b>者）
 *       到末个 checkpoint（round 20）RSS 增长应 &lt; 80 MB。持续单调上涨才是 issue #14
 *       的回归特征（arena 高水位随新 shape 不断抬升）；</li>
 *   <li>参考点取 max(round 5, round 10) 而非 round 5：round 5 紧跟
 *       {@link #stabilizeJvmMemory()} 的强制 GC，此时堆刚被 uncommit，是一个人为压低的
 *       凹点，把后续正常回升算成「增长」会误报（实测误报样本：r20 仍比 baseline 低
 *       26.8 MB，却因以 r5 为参考而被判为 +87 MB 泄漏）。取前两个 checkpoint 的较大者
 *       可避开该凹点，同时仍能抓住真泄漏的线性增长；</li>
 *   <li>一次性抬升（warmup 后 JVM heap 首次扩展到 -Xms、CodeCache/Metaspace 首次扩容、
 *       CRT heap 预留等）不代表泄漏，只打印 peak 与 baseline 的差值供观察，不判失败。</li>
 *   <li>getThreadAllocatedBytes（线程累计分配）：每个 5 轮 checkpoint 的 chunk_delta
 *       仅打印，不参与断言 —— 它是吞吐指标（每轮分配即释放），与常驻内存无关。</li>
 * </ul>
 *
 * <p>为消除「baseline 之后 heap 首次扩展」造成的伪增长，warmup 后先
 * {@link #stabilizeJvmMemory()} 把 JVM 堆 committed 推到稳态再记录 baseline。
 */
class MemoryLeakReproTest {
	private static final ThreadMXBean THREAD_BEAN =
		(ThreadMXBean) ManagementFactory.getThreadMXBean();
	private static final OperatingSystemMXBean OS_BEAN =
		(OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
	// 容器/裸机通用容差：warmup 后每次 checkpoint RSS 波动不超过 80 MB
	private static final long RSS_FLOOR_TOLERANCE_BYTES = 80L * 1024 * 1024;

	@Test
	void fullPipelineMemoryStableAcrossDifferentImages() {
		Path root = findRepositoryRoot();
		Path modelDir = root.resolve("models/ppocr-v6/tiny");
		Assumptions.assumeTrue(Files.isDirectory(modelDir), "requires tiny models");
		File img1 = root.resolve("test_images/vehicle/vehicle1.png").toFile();
		File img2 = root.resolve("test_images/vehicle/vehicle2.png").toFile();
		Assumptions.assumeTrue(img1.isFile() && img2.isFile(), "requires test images");
		List<File> imgs = new ArrayList<>();
		Collections.addAll(imgs, img1, img2);

		PPOcrV6Config config = PPOcrV6Config.builder()
			.detModelPath(modelDir.resolve("det.onnx").toString())
			.recModelPath(modelDir.resolve("rec.onnx").toString())
			.recCharDictPath(modelDir.resolve("dict.txt").toString())
			.build();
		System.out.println("==== FULL pipeline (engine.run file) — issue #14 regression ====");
		try (PPOcrV6Engine engine = new PPOcrV6Engine(config)) {
			// 预热 15 轮，让 ONNX/JIT 稳定
			for (int i = 0; i < 15; i++) {
				engine.run(imgs.get(i % 2));
			}
			// 稳定化：把 JVM 堆 committed 推到稳态（扩展后由 GC 缩回 -Xms 水位），
			// 避免 baseline 之后 heap 首次扩展造成 committed VM 一次性抬升的伪增长
			stabilizeJvmMemory();

			long tid = Thread.currentThread().getId();
			long allocated0 = THREAD_BEAN.getThreadAllocatedBytes(tid);
			long rss0 = OS_BEAN.getCommittedVirtualMemorySize();
			long heap0 = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
			Runtime rt = Runtime.getRuntime();
			System.out.printf("baseline    allocated=%8.2f MB  rss=%7.2f MB  heap=%5.2f MB  heap_committed=%7.2f MB  max_heap=%7.2f MB%n",
				allocated0 / 1024.0 / 1024.0, rss0 / 1024.0 / 1024.0, heap0 / 1024.0 / 1024.0,
				rt.totalMemory() / 1024.0 / 1024.0, rt.maxMemory() / 1024.0 / 1024.0);

			// 监控 20 轮，每 5 轮采样：检查稳态后 RSS 不持续增长、分配字节 delta 恒定
			int totalRounds = 20;
			long lastChunkAllocated = 0;
			int done = 0;
			long rssPeak = rss0;
			long rssRound5 = 0;
			long rssRound10 = 0;
			long rssLast = 0;
			for (int chunk = 5; chunk <= totalRounds; chunk += 5) {
				while (done < chunk) {
					engine.run(imgs.get(done % 2));
					done++;
				}
				System.gc();
				sleep(200);
				System.gc();
				long allocated = THREAD_BEAN.getThreadAllocatedBytes(tid);
				long rss = OS_BEAN.getCommittedVirtualMemorySize();
				long heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
				long chunkAllocated = allocated - allocated0;
				long chunkDelta = chunkAllocated - lastChunkAllocated;
				lastChunkAllocated = chunkAllocated;
				if (rss > rssPeak) rssPeak = rss;
				if (chunk == 5) {
					rssRound5 = rss;
				} else if (chunk == 10) {
					rssRound10 = rss;
				}
				rssLast = rss;
				System.out.printf("round %3d  allocated=+%7.2f MB  chunk_delta=+%6.2f MB  rss=%7.2f MB  delta_rss=%+.2f MB%n",
					done, chunkAllocated / 1024.0 / 1024.0, chunkDelta / 1024.0 / 1024.0,
					rss / 1024.0 / 1024.0, (rss - rss0) / 1024.0 / 1024.0);
			}

			// 断言：稳态斜率 —— 参考点取 round 5 与 round 10 中较大者，避开 round 5
			// 紧跟强制 GC 的人为凹点；持续单调上涨才是 issue #14 的回归特征。
			// 一次性抬升（JVM heap/CodeCache 首次扩展、CRT heap 预留）不代表泄漏，仅打印参考。
			long reference = Math.max(rssRound5, rssRound10);
			long steadyGrowth = rssLast - reference;
			long peakGrowth = rssPeak - rss0;
			System.out.printf("peak RSS growth = %+.2f MB (info), steady RSS growth (max(round 5, round 10) -> round %d) = %+.2f MB (tolerance %d MB)%n",
				peakGrowth / 1024.0 / 1024.0, totalRounds,
				steadyGrowth / 1024.0 / 1024.0, RSS_FLOOR_TOLERANCE_BYTES / (1024 * 1024));
			if (steadyGrowth > RSS_FLOOR_TOLERANCE_BYTES) {
				throw new AssertionError(
					"Issue #14 regression: RSS kept growing by " + (steadyGrowth / 1024 / 1024)
						+ " MB from max(round 5, round 10) to round " + totalRounds + " (tolerance " + (RSS_FLOOR_TOLERANCE_BYTES / 1024 / 1024) + " MB). "
						+ "Check ONNX arena / memory pattern flags.");
			}
		}
	}

	/**
	 * 稳定化 JVM 内存：把堆 committed 推到目标水位再释放，让后续推理不再触发
	 * heap 首次扩展（-Xms 水位）导致 committed VM 一次性抬升的伪增长。
	 *
	 * <p>目标 = min(maxMemory × 3/4, 1 GB)，覆盖常见默认 -Xms（物理内存 1/64，
	 * 最大约 768 MB@48GB 机器；1 GB 封顶覆盖 64GB 机器的 1 GB 默认 Xms）；
	 * 分配后释放引用并多次 GC，heap committed 由 GC 缩回 -Xms 稳态。
	 *
	 * <p><b>不再因「当前 committed 已达目标」而提前返回</b>：本方法跑在 Maven 的 JVM 内
	 * （surefire forkCount=0），全量套件时前面的测试已把堆撑到 1 GB 以上，提前返回等于
	 * 跳过稳定化，baseline 的堆状态随「套件里之前跑了什么」漂移，判据随之失真。
	 * 现在无论当前水位如何都执行「释放 + 多次 GC」，把堆压到同一低位，
	 * 使 baseline 在单独跑与全量套件两种场景下同构。
	 */
	private static void stabilizeJvmMemory() {
		Runtime rt = Runtime.getRuntime();
		long maxMemory = rt.maxMemory();
		long target = Math.min(maxMemory * 3 / 4, 1024L * 1024 * 1024);
		int chunk = 4 * 1024 * 1024;
		java.util.ArrayList<byte[]> bufs = new java.util.ArrayList<>();
		try {
			while (rt.totalMemory() < target) {
				bufs.add(new byte[chunk]);
			}
		} catch (OutOfMemoryError ignore) {
			// 堆上限兜底：达到上限即止
		}
		bufs.clear();
		System.gc();
		sleep(200);
		System.gc();
		sleep(200);
		System.gc();
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException ignore) {
			Thread.currentThread().interrupt();
		}
	}

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
