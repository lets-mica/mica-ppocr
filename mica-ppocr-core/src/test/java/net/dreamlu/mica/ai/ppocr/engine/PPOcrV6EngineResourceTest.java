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

import net.dreamlu.mica.ai.ppocr.utils.CollUtil;

import net.dreamlu.mica.ai.ppocr.config.PPOcrV6Config;
import net.dreamlu.mica.ai.ppocr.utils.ModelResourceLoader;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencv.core.Mat;
import org.opencv.imgcodecs.Imgcodecs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PPOcrV6EngineResourceTest {
	private static final Path PROC_FD_DIR = CollUtil.pathOf("/proc/self/fd");
	private static final long MAX_FD_DELTA = 2L; // allow tiny /proc fd stream jitter during counting

	/**
	 * 本测试在构造引擎<b>之前</b>就用 {@code Imgcodecs.imread} 直接读图，
	 * 引擎的构造期自动加载管不到这里，需自行加载 native 库。
	 */
	@BeforeAll
	static void loadOpenCv() {
		nu.pattern.OpenCV.loadLocally();
	}

	@Test
	void repeatedRunProducesStableResults() {
		Path root = findRepositoryRoot();
		Path modelDir = root.resolve("models/ppocr-v6/tiny");
		Mat image = Imgcodecs.imread(root.resolve("test_images/vehicle/vehicle1.png").toString());
		try {
			assertFalse(image.empty(), "test image should load");

			PPOcrV6Config config = PPOcrV6Config.builder()
				.detModelPath(modelDir.resolve("det.onnx").toString())
				.recModelPath(modelDir.resolve("rec.onnx").toString())
				.recCharDictPath(modelDir.resolve("dict.txt").toString())
				.build();

			try (PPOcrV6Engine engine = new PPOcrV6Engine(config)) {
				List<String> expected = texts(engine.runMat(image));
				assertFalse(expected.isEmpty(), "OCR should return at least one result");
				for (int i = 0; i < 2; i++) {
					assertEquals(expected, texts(engine.runMat(image)));
				}
			}
		} finally {
			image.release();
		}
	}

	@Test
	void shouldCloseOrtSessionsWhenConstructorFailsAfterSessionCreation() throws IOException {
		Assumptions.assumeTrue(Files.isDirectory(PROC_FD_DIR), "requires /proc/self/fd");
		Path root = findRepositoryRoot();
		Path detModel = root.resolve("models/ppocr-v6/tiny/det.onnx");
		Path recModel = root.resolve("models/ppocr-v6/tiny/rec.onnx");
		Path dict = root.resolve("models/ppocr-v6/tiny/dict.txt");
		Assumptions.assumeTrue(Files.isRegularFile(detModel), "requires tiny det model");
		Assumptions.assumeTrue(Files.isRegularFile(recModel), "requires tiny rec model");
		Assumptions.assumeTrue(Files.isRegularFile(dict), "requires tiny dict");

		PPOcrV6Config config = PPOcrV6Config.builder()
			.detModelPath(detModel.toString())
			.recModelPath(recModel.toString())
			.recCharDictPath(dict.toString())
			.detLimitType("invalid")
			.build();

		long before = openFdCount();
		for (int i = 0; i < 20; i++) {
			assertThrows(IllegalArgumentException.class, () -> new PPOcrV6Engine(config));
		}
		long after = openFdCount();
		assertTrue(after - before <= MAX_FD_DELTA, "constructor failure leaked file descriptors: before=" + before + ", after=" + after);
	}

	/**
	 * 验证 ModelResourceLoader 在文件路径通道下加载出的字节与直接 Files.readAllBytes 一致，
	 * 并确保 PPOcrV6Engine 通过该字节通道能正常构建 ONNX Session 并跑通 OCR。
	 *
	 * <p>classpath 通道的字节解析逻辑由 {@link net.dreamlu.mica.ai.ppocr.utils.ModelResourceLoaderTest}
	 * 单独覆盖；此处通过文件路径 + ModelResourceLoader 串通到 env.createSession(byte[], opts)，
	 * 保证"字节 → ORT Session"链路无回归。
	 */
	@Test
	void classpathLoaderBytesEqualsFileBytes() throws Exception {
		Path root = findRepositoryRoot();
		Path modelDir = root.resolve("models/ppocr-v6/tiny");
		Path imageFile = root.resolve("test_images/vehicle/vehicle1.png");
		Assumptions.assumeTrue(Files.isDirectory(modelDir), "requires tiny models");
		Assumptions.assumeTrue(Files.isRegularFile(imageFile), "requires test image");

		// 直接读字节；ModelResourceLoader.load(file_path) 必须返回等价字节
		byte[] detBytes = Files.readAllBytes(modelDir.resolve("det.onnx"));
		byte[] recBytes = Files.readAllBytes(modelDir.resolve("rec.onnx"));
		byte[] dictBytes = Files.readAllBytes(modelDir.resolve("dict.txt"));
		assertArrayEquals(detBytes, ModelResourceLoader.load(modelDir.resolve("det.onnx").toString()));
		assertArrayEquals(recBytes, ModelResourceLoader.load(modelDir.resolve("rec.onnx").toString()));
		assertArrayEquals(dictBytes, ModelResourceLoader.load(modelDir.resolve("dict.txt").toString()));

		// 跑一次端到端 OCR，确认 byte → ORT 链路 OK（classpath 通道等价于文件路径通道）
		PPOcrV6Config fileConfig = PPOcrV6Config.builder()
			.detModelPath(modelDir.resolve("det.onnx").toString())
			.recModelPath(modelDir.resolve("rec.onnx").toString())
			.recCharDictPath(modelDir.resolve("dict.txt").toString())
			.build();
		List<String> baseline = readOcrTexts(fileConfig, imageFile);
		assertFalse(baseline.isEmpty(), "baseline OCR should produce results");
	}

	private static List<String> texts(List<PPOcrV6Result> results) {
		return CollUtil.toList(results.stream().map(PPOcrV6Result::text));
	}

	private static List<String> readOcrTexts(PPOcrV6Config config, Path imageFile) {
		Mat image = Imgcodecs.imread(imageFile.toString());
		try (PPOcrV6Engine engine = new PPOcrV6Engine(config)) {
			return CollUtil.toList(engine.runMat(image).stream().map(PPOcrV6Result::text));
		} finally {
			image.release();
		}
	}

	private static long openFdCount() throws IOException {
		try (Stream<Path> files = Files.list(PROC_FD_DIR)) {
			return files.count();
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