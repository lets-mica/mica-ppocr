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

package net.dreamlu.mica.ai.ppocr.structured.parser.pdd;

import net.dreamlu.mica.ai.ppocr.config.PPOcrV6Config;
import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Engine;
import net.dreamlu.mica.ai.ppocr.utils.CollUtil;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 拼多多福袋解析器端到端集成测试（真实 OCR 模型 + 真实样本图）。
 *
 * <p>本测试锁定的是一个<b>曾经踩过的坑</b>：福袋海报是「大字号稀疏数字 + 满屏背景水印」版面，
 * DB 会把数字与水印连成一个整体轮廓，框内文字像素占比只有 ~42%，
 * 框平均分低于默认 {@code detBoxThresh=0.6} 被丢弃 ——
 * 表现为「识别模型完全正常，但 det 一个框都没给，福袋码永远解析不出来」。
 *
 * <p>修复方式是 {@link PddLuckyBagParser#DET_PARAMS} 里的 per-call DB 参数
 * （{@code boxThresh=0.4 / unclipRatio=1.0}）。本测试确保该修复不会回归。
 *
 * <p>为遵守仓库的测试数据脱敏约定，断言只校验福袋码的<b>格式</b>（6~12 位纯数字），
 * 不硬编码任何真实邀请码。
 *
 * <p>依赖仓库根目录 {@code models/ppocr-v6/tiny/} 模型与 {@code test_images/pdd/} 样本图；
 * 缺失时通过 {@link Assumptions} 跳过。
 */
class PddLuckyBagIntegrationTest {

	private static final String DEFAULT_TIER = "tiny";

	/**
	 * 福袋码格式：6~12 位纯数字（与 {@link PddLuckyBagParser} 的解析区间一致）。
	 */
	private static final Pattern CODE_PATTERN = Pattern.compile("\\d{6,12}");

	private static Path findRepositoryRoot() {
		String multiModuleDir = System.getProperty("maven.multiModuleProjectDirectory");
		if (multiModuleDir != null && Files.isDirectory(CollUtil.pathOf(multiModuleDir).resolve("models"))) {
			return CollUtil.pathOf(multiModuleDir);
		}
		Path current = CollUtil.pathOf("").toAbsolutePath();
		while (current != null && !Files.isDirectory(current.resolve("models"))) {
			current = current.getParent();
		}
		if (current == null) {
			throw new IllegalStateException("repository root not found");
		}
		return current;
	}

	@Test
	void parseRealLuckyBagImages() throws Exception {
		Path root = findRepositoryRoot();
		Path modelDir = root.resolve("models/ppocr-v6/" + DEFAULT_TIER);
		Assumptions.assumeTrue(Files.isRegularFile(modelDir.resolve("det.onnx"))
				&& Files.isRegularFile(modelDir.resolve("rec.onnx"))
				&& Files.isRegularFile(modelDir.resolve("dict.txt")),
			"tiny 模型缺失，跳过集成测试");

		Path sampleDir = root.resolve("test_images/pdd");
		Assumptions.assumeTrue(Files.isDirectory(sampleDir),
			"test_images/pdd/ 样本缺失，跳过集成测试");

		PPOcrV6Config config = PPOcrV6Config.builder()
			.detModelPath(modelDir.resolve("det.onnx").toString())
			.recModelPath(modelDir.resolve("rec.onnx").toString())
			.recCharDictPath(modelDir.resolve("dict.txt").toString())
			.build();

		Path[] samples;
		try (Stream<Path> stream = Files.list(sampleDir)) {
			samples = stream
				.filter(p -> p.toString().toLowerCase().endsWith(".jpg"))
				.filter(Files::isRegularFile)
				.sorted()
				.toArray(Path[]::new);
		}
		Assumptions.assumeTrue(samples.length > 0, "test_images/pdd/ 无样本图，跳过集成测试");

		try (PPOcrV6Engine engine = new PPOcrV6Engine(config)) {
			PddLuckyBagParser parser = new PddLuckyBagParser(engine);
			for (Path img : samples) {
				System.out.println("\n========== " + img.getFileName() + " ==========");
				PddLuckyBagResult r = parser.parse(img);
				assertNotNull(r, "解析失败：" + img);

				String code = r.getLuckyBagCode();
				System.out.println("  luckyBagCode: " + code);
				System.out.println("  type:         " + r.getType());

				// 核心断言：福袋码必须能被检出（曾经因 det 框分阈值被丢弃而恒为 null）
				assertNotNull(code, "未解析到福袋码：" + img.getFileName());
				assertTrue(CODE_PATTERN.matcher(code).matches(),
					"福袋码不是 6~12 位纯数字：" + code);
				// 样本目录同时包含「福袋券」与「水果券」两种版面，类型各不相同，
				// 这里只校验类型判定有效（具体类型由 PddLuckyBagParserTest 的 mock 用例覆盖）
				assertNotEquals(PddLuckyBagType.UNKNOWN, r.getType(),
					"福袋类型未能判定：" + img.getFileName());
			}
		}
	}
}
