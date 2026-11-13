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
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归护栏：{@link PPOcrV6Engine} 构造期必须完成 OpenCV 原生库加载。
 *
 * <p><b>守护的契约</b>：调用方（starter / plugin / 业务代码）<b>不需要</b>自己调
 * {@code OpenCV.loadLocally()} —— 引擎构造成功即意味着后续推理不会撞
 * {@link UnsatisfiedLinkError}。一旦有人把构造期的加载挪走或删掉，本测试必须变红。
 *
 * <p><b>为什么用子 JVM</b>：其它测试在 {@code @BeforeAll} 里已显式加载过 native，
 * 同 JVM 内断言恒为真、毫无牙齿。子 JVM 是唯一能确定「native 尚未加载」的验证环境。
 * 详见 {@link OpenCvNativeProbe}。
 *
 * @see OpenCvNativeProbe
 */
class PPOcrV6EngineOpenCvLoadTest {

	/**
	 * 子 JVM 超时（秒）。
	 */
	private static final long TIMEOUT_SECONDS = 300L;

	/**
	 * 探针类全名 —— 候选 classpath 必须能定位到它。
	 */
	private static final String PROBE_CLASS = OpenCvNativeProbe.class.getName();

	/**
	 * 被测引擎类全名 —— 候选 classpath 必须能定位到它。
	 */
	private static final String ENGINE_CLASS = PPOcrV6Engine.class.getName();

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
	 * 回归护栏：manifest-only jar 必须能被展开成真实 classpath。
	 *
	 * <p>Surefire 在 fork 模式下会把完整 classpath 写进一个「只有 manifest 的 jar」的
	 * {@code Class-Path} 属性里，测试进程里能看到的往往只是这个 jar 本身。
	 * 若不展开，子 JVM 的 {@code -cp} 就只指向那个空壳 jar ⇒ 找不到探针类。
	 */
	@Test
	void manifestOnlyJarClasspath_expandsToRealEntries() throws Exception {
		Path testClasses = codeSourceDir(OpenCvNativeProbe.class);
		Path classes = codeSourceDir(PPOcrV6Engine.class);
		Assumptions.assumeTrue(testClasses != null && classes != null, "requires code source location");

		Path jar = Files.createTempFile("ppocr-manifest-only", ".jar");
		try {
			Manifest manifest = new Manifest();
			manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
			manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH,
				testClasses.toUri().toString() + " " + classes.toUri().toString());
			try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
				// 空壳 jar：全部内容都在 manifest 里
			}

			String expanded = expandManifestOnlyJar(jar.toString());
			assertFalse(expanded.contains(jar.toString()), "manifest-only jar 本身应被展开替换：" + expanded);
			assertTrue(expanded.contains(testClasses.toString()), "展开后应包含测试类目录：" + expanded);
			assertTrue(expanded.contains(classes.toString()), "展开后应包含主类目录：" + expanded);
			assertTrue(isUsableClasspath(expanded), "展开后的 classpath 应能定位到探针类与引擎类：" + expanded);
		} finally {
			Files.deleteIfExists(jar);
		}

		// 只含测试类目录（缺主类目录）不算可用 —— 防止「只找到一个目录就当成功」
		assertFalse(isUsableClasspath(testClasses.toString()),
			"缺少主类目录的 classpath 不应被判为可用");
	}

	/**
	 * 解析子进程用的 classpath。
	 *
	 * <p>Surefire 的 classpath 暴露方式随运行模式变化，没有一种是「永远可用」的：
	 * <ul>
	 *     <li>{@code forkCount=0}（in-process）：测试跑在 Maven JVM 内，
	 *         {@code java.class.path} 是 <b>Maven 自身</b>的 classpath（不含项目依赖），
	 *         真实 classpath 在测试类加载器（{@code IsolatedClassLoader}，继承
	 *         {@link URLClassLoader}）持有的 URL 列表里；</li>
	 *     <li>fork 模式：真实 classpath 要么由 surefire 以 {@code surefire.test.class.path}
	 *         属性注入，要么写进一个 manifest-only jar 的 {@code Class-Path} 里，
	 *         而 {@code java.class.path} 只剩那个 jar 本身。</li>
	 * </ul>
	 * ⇒ 逐个来源收集候选，<b>展开 manifest-only jar</b> 后再校验，
	 * 挑第一个真正能定位到探针类与引擎类的候选。
	 *
	 * @return classpath 字符串（{@code ;} 分隔，Windows）
	 */
	private static String resolveTestClasspath() {
		List<String> candidates = new ArrayList<>();

		// ① 测试类加载器持有的 URL —— in-process 模式最准
		ClassLoader loader = PPOcrV6EngineOpenCvLoadTest.class.getClassLoader();
		if (loader instanceof URLClassLoader) {
			List<String> urls = new ArrayList<>();
			for (URL url : ((URLClassLoader) loader).getURLs()) {
				String path = toLocalPath(url);
				if (path != null) {
					urls.add(path);
				}
			}
			if (!urls.isEmpty()) {
				candidates.add(String.join(File.pathSeparator, urls));
			}
		}

		// ② surefire 官方属性 —— fork 模式下由 surefire 注入的真实测试 classpath
		addCandidate(candidates, System.getProperty("surefire.test.class.path"));
		// ③ 当前 JVM 的 classpath
		addCandidate(candidates, System.getProperty("java.class.path"));
		// ④ surefire 记录的启动 classpath —— fork 模式下通常就是那个 manifest-only jar
		addCandidate(candidates, System.getProperty("surefire.real.class.path"));

		for (String candidate : candidates) {
			String expanded = expandManifestOnlyJar(candidate);
			if (isUsableClasspath(expanded)) {
				return expanded;
			}
		}
		throw new IllegalStateException("未能解析出可用的测试 classpath，候选来源：" + candidates);
	}

	/**
	 * 收集候选 classpath（忽略空值）。
	 *
	 * @param candidates 候选列表
	 * @param value      候选值
	 */
	private static void addCandidate(List<String> candidates, String value) {
		if (value != null && !value.trim().isEmpty()) {
			candidates.add(value);
		}
	}

	/**
	 * 展开 classpath 中的 manifest-only jar。
	 *
	 * <p>jar 自身的 manifest 里带 {@code Class-Path} 属性时，说明真正的 classpath
	 * 被写进了 manifest，需要展开替换 —— 否则子 JVM 只能看到这个空壳 jar。
	 *
	 * @param classpath 原始 classpath
	 * @return 展开后的 classpath
	 */
	static String expandManifestOnlyJar(String classpath) {
		if (classpath == null || classpath.trim().isEmpty()) {
			return "";
		}
		List<String> expanded = new ArrayList<>();
		for (String entry : classpath.split(Pattern.quote(File.pathSeparator))) {
			String trimmed = entry.trim();
			if (trimmed.isEmpty()) {
				continue;
			}
			List<String> manifestEntries = readManifestClassPath(trimmed);
			if (manifestEntries.isEmpty()) {
				expanded.add(trimmed);
			} else {
				expanded.addAll(manifestEntries);
			}
		}
		return String.join(File.pathSeparator, expanded);
	}

	/**
	 * 读取 jar manifest 里的 {@code Class-Path} 并解析成绝对路径。
	 *
	 * @param jarPath jar 路径
	 * @return manifest 中的 classpath 条目（无则空列表）
	 */
	private static List<String> readManifestClassPath(String jarPath) {
		File file = new File(jarPath);
		if (!file.isFile() || !jarPath.toLowerCase().endsWith(".jar")) {
			return CollUtil.listOf();
		}
		try (JarFile jar = new JarFile(file)) {
			Manifest manifest = jar.getManifest();
			if (manifest == null) {
				return CollUtil.listOf();
			}
			String value = manifest.getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
			if (value == null || value.trim().isEmpty()) {
				return CollUtil.listOf();
			}
			List<String> resolved = new ArrayList<>();
			for (String spec : value.trim().split("\\s+")) {
				String path = resolveManifestEntry(spec, file.getParentFile());
				if (path != null) {
					resolved.add(path);
				}
			}
			return resolved;
		} catch (Exception e) {
			// 读不动就当作普通 jar 处理，不影响其它候选
			return CollUtil.listOf();
		}
	}

	/**
	 * 解析 manifest {@code Class-Path} 里的单个条目。
	 *
	 * @param spec    manifest 里的条目（{@code file:/E:/xxx} 形式的 URL 或普通路径）
	 * @param baseDir jar 所在目录（相对路径的基准）
	 * @return 绝对路径，解析不了返回 null
	 */
	private static String resolveManifestEntry(String spec, File baseDir) {
		if (spec == null || spec.trim().isEmpty()) {
			return null;
		}
		String trimmed = spec.trim();
		// file:/E:/xxx 形式的绝对 URL
		if (trimmed.regionMatches(true, 0, "file:", 0, 5)) {
			try {
				return new File(new URI(trimmed)).getAbsolutePath();
			} catch (Exception e) {
				// URI 解析不了（未转义的字符）时，按路径字面量处理
				String literal = trimmed.substring(5);
				while (literal.startsWith("/")) {
					literal = literal.substring(1);
				}
				return new File(literal.replace('/', File.separatorChar)).getAbsolutePath();
			}
		}
		File file = new File(trimmed);
		if (!file.isAbsolute() && baseDir != null) {
			file = new File(baseDir, trimmed);
		}
		return file.getAbsolutePath();
	}

	/**
	 * 判定候选 classpath 是否真的可用。
	 *
	 * <p>判据是「能否定位到探针类与引擎类」——光看路径里有没有模块名是不够的：
	 * 只含一个目录的 classpath 同样带模块名，却跑不起来。
	 *
	 * @param classpath 候选 classpath
	 * @return 可用返回 true
	 */
	static boolean isUsableClasspath(String classpath) {
		if (classpath == null || classpath.isEmpty()) {
			return false;
		}
		boolean probeFound = false;
		boolean engineFound = false;
		for (String entry : classpath.split(Pattern.quote(File.pathSeparator))) {
			String trimmed = entry.trim();
			if (trimmed.isEmpty()) {
				continue;
			}
			probeFound |= containsClass(trimmed, PROBE_CLASS);
			engineFound |= containsClass(trimmed, ENGINE_CLASS);
		}
		return probeFound && engineFound;
	}

	/**
	 * 判断 classpath 条目里是否含有指定类。
	 *
	 * @param entry     classpath 条目（目录或 jar）
	 * @param className 类全名
	 * @return 含该类返回 true
	 */
	private static boolean containsClass(String entry, String className) {
		String resource = className.replace('.', '/') + ".class";
		File file = new File(entry);
		if (file.isDirectory()) {
			return new File(file, resource).isFile();
		}
		if (file.isFile() && entry.toLowerCase().endsWith(".jar")) {
			try (JarFile jar = new JarFile(file)) {
				return jar.getEntry(resource) != null;
			} catch (Exception e) {
				return false;
			}
		}
		return false;
	}

	/**
	 * URL 转本地绝对路径。
	 *
	 * @param url URL
	 * @return 本地路径，解析不了返回 null
	 */
	private static String toLocalPath(URL url) {
		try {
			return new File(url.toURI()).getAbsolutePath();
		} catch (Exception e) {
			return url.getPath();
		}
	}

	/**
	 * 取类所在目录（编译产物目录）。
	 *
	 * @param type 类
	 * @return 目录，取不到返回 null
	 */
	private static Path codeSourceDir(Class<?> type) {
		try {
			java.security.CodeSource source = type.getProtectionDomain().getCodeSource();
			if (source == null || source.getLocation() == null) {
				return null;
			}
			return Paths.get(source.getLocation().toURI());
		} catch (Exception e) {
			return null;
		}
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
