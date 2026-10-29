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
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归护栏：{@link PPOcrV6Engine} 构造期必须完成 OpenCV 原生库加载。
 *
 * <p><b>守护的契约</b>：调用方（starter / plugin / 业务代码）<b>不需要</b>自己调
 * {@code OpenCV.loadLocally()} —— 引擎构造成功即意味着后续推理不会撞
 * {@link UnsatisfiedLinkError}。一旦有人把构造期的加载挪走或删掉，本测试必须变红。
 *
 * <p><b>为什么用子 JVM</b>：本仓库 surefire 配了 {@code forkCount=0}，全部测试共享一个 JVM，
 * 而其它测试在 {@code @BeforeAll} 里已显式加载过 native ⇒ 同 JVM 内断言恒为真、毫无牙齿。
 * 子 JVM 是唯一能确定「native 尚未加载」的验证环境。详见 {@link OpenCvNativeProbe}。
 *
 * @see OpenCvNativeProbe
 */
class PPOcrV6EngineOpenCvLoadTest {

	/**
	 * 子 JVM 超时（秒）。
	 */
	private static final long TIMEOUT_SECONDS = 300L;

	@Test
	void engineConstructionLoadsOpenCvNativeLibrary() throws Exception {
		Path root = findRepositoryRoot();
		Path det = root.resolve("models/ppocr-v6/tiny/det.onnx");
		Path rec = root.resolve("models/ppocr-v6/tiny/rec.onnx");
		Path dict = root.resolve("models/ppocr-v6/tiny/dict.txt");
		Path image = root.resolve("test_images/vehicle/vehicle1.png");
		Assumptions.assumeTrue(Files.isRegularFile(det), "requires tiny det model");
		Assumptions.assumeTrue(Files.isRegularFile(rec), "requires tiny rec model");
		Assumptions.assumeTrue(Files.isRegularFile(dict), "requires tiny dict");

		String classpath = resolveTestClasspath();
		Path log = Files.createTempFile("ppocr-opencv-probe", ".log");
		try {
			ProcessBuilder builder = new ProcessBuilder(CollUtil.listOf(
				javaExecutable(),
				"-cp", classpath,
				OpenCvNativeProbe.class.getName(),
				det.toAbsolutePath().toString(),
				rec.toAbsolutePath().toString(),
				dict.toAbsolutePath().toString(),
				image.toAbsolutePath().toString()));
			builder.redirectErrorStream(true);
			builder.redirectOutput(log.toFile());
			Process process = builder.start();
			boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			if (!finished) {
				process.destroyForcibly();
			}
			String output = readLog(log);
			assertTrue(finished, "子 JVM 超时未结束（" + TIMEOUT_SECONDS + "s）：" + output);
			assertEquals(0, process.exitValue(), "子 JVM 退出码非 0：" + output);
			assertTrue(output.contains(OpenCvNativeProbe.OK_MARKER),
				"子 JVM 未确认引擎自行加载了 OpenCV native：" + output);
		} finally {
			Files.deleteIfExists(log);
		}
	}

	/**
	 * 解析子进程用的 classpath。
	 *
	 * <p>本仓库 surefire 走 {@code forkCount=0}（测试跑在 Maven JVM 内），
	 * {@code java.class.path} 是 <b>Maven 自身</b>的 classpath，不含项目依赖 ⇒ 不可用。
	 * 实际可用的是 <b>测试类加载器</b>（surefire 的 {@code IsolatedClassLoader}，继承
	 * {@link URLClassLoader}）持有的 URL 列表。
	 *
	 * @return classpath 字符串（{@code ;} 分隔，Windows）
	 */
	private static String resolveTestClasspath() {
		List<String> entries = new ArrayList<>();
		ClassLoader loader = PPOcrV6EngineOpenCvLoadTest.class.getClassLoader();
		if (loader instanceof URLClassLoader) {
			for (URL url : ((URLClassLoader) loader).getURLs()) {
				try {
					entries.add(new File(url.toURI()).getAbsolutePath());
				} catch (Exception e) {
					entries.add(url.getPath());
				}
			}
		}
		if (entries.isEmpty()) {
			// 兜底：surefire 注入的属性 / 当前 JVM 的 classpath
			String surefireCp = System.getProperty("surefire.test.class.path");
			if (surefireCp != null && !surefireCp.isEmpty()) {
				entries.add(surefireCp);
			}
			entries.add(System.getProperty("java.class.path"));
		}
		String cp = String.join(File.pathSeparator, entries);
		if (!cp.contains("mica-ppocr-core")) {
			throw new IllegalStateException("未能解析出可用的测试 classpath：" + cp);
		}
		return cp;
	}

	/**
	 * 定位当前 JDK 的 java 可执行文件。
	 *
	 * @return java 可执行文件路径
	 */
	private static String javaExecutable() {
		String javaHome = System.getProperty("java.home");
		String exe = System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
		return new File(new File(javaHome, "bin"), exe).getAbsolutePath();
	}

	/**
	 * 读取子进程日志。
	 *
	 * @param log 日志文件
	 * @return 日志文本
	 * @throws Exception 读取失败
	 */
	private static String readLog(Path log) throws Exception {
		if (!Files.isRegularFile(log)) {
			return "";
		}
		return new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
	}

	/**
	 * 定位仓库根目录（含 {@code models/ppocr-v6/tiny}）。
	 *
	 * @return 仓库根目录
	 */
	private static Path findRepositoryRoot() {
		String multiModuleDir = System.getProperty("maven.multiModuleProjectDirectory");
		if (multiModuleDir != null) {
			Path root = CollUtil.pathOf(multiModuleDir);
			if (Files.isDirectory(root.resolve("models/ppocr-v6/tiny"))) {
				return root;
			}
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
