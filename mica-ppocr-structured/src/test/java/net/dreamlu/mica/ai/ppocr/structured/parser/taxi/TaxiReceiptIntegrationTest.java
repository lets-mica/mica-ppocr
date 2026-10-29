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

package net.dreamlu.mica.ai.ppocr.structured.parser.taxi;

import net.dreamlu.mica.ai.ppocr.config.PPOcrV6Config;
import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Engine;
import net.dreamlu.mica.ai.ppocr.utils.CollUtil;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 出租车票解析器端到端集成测试（使用真实 OCR 模型 + 真实样本图）。
 *
 * <p>样本图来源：百度 OCR 出租车票演示页（{@link TaxiSampleImageFetcher}）。
 * 测试样本由 {@code TaxiSampleImageFetcher} 抓取并保存到 {@code test_images/taxi/}，
 * 缺失时通过 {@link Assumptions} 跳过。
 *
 * <p>依赖仓库根目录 {@code models/ppocr-v6/tiny/} 模型；
 * 模型缺失时同样跳过（模型不随仓库分发）。
 */
class TaxiReceiptIntegrationTest {

	private static final String DEFAULT_TIER = "tiny";

	private static void print(TaxiReceiptResult r) {
		System.out.println("  invoiceCode:       " + r.getInvoiceCode());
		System.out.println("  invoiceNo:         " + r.getInvoiceNo());
		System.out.println("  plateNumber:       " + r.getPlateNumber());
		System.out.println("  date:              " + r.getDate());
		System.out.println("  boardingTime:      " + r.getBoardingTime());
		System.out.println("  alightingTime:     " + r.getAlightingTime());
		System.out.println("  mileage:           " + r.getMileage());
		System.out.println("  amount:            " + r.getAmount());
		System.out.println("  fuelSurcharge:     " + r.getFuelSurcharge());
		System.out.println("  bookingFee:        " + r.getBookingFee());
		System.out.println("  totalAmount:       " + r.getTotalAmount());
		System.out.println("  city:              " + r.getCity());
	}

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
	void parseRealTaxiReceiptImages() throws Exception {
		Path root = findRepositoryRoot();
		Path modelDir = root.resolve("models/ppocr-v6/" + DEFAULT_TIER);
		Assumptions.assumeTrue(Files.isRegularFile(modelDir.resolve("det.onnx"))
				&& Files.isRegularFile(modelDir.resolve("rec.onnx"))
				&& Files.isRegularFile(modelDir.resolve("dict.txt")),
			"tiny 模型缺失，跳过集成测试");

		Path sampleDir = root.resolve("test_images/taxi");
		Assumptions.assumeTrue(Files.isDirectory(sampleDir) && sampleDir.toFile().listFiles().length > 0,
			"test_images/taxi/ 样本缺失，跳过集成测试");

		PPOcrV6Config config = PPOcrV6Config.builder()
			.detModelPath(modelDir.resolve("det.onnx").toString())
			.recModelPath(modelDir.resolve("rec.onnx").toString())
			.recCharDictPath(modelDir.resolve("dict.txt").toString())
			.build();

		try (PPOcrV6Engine engine = new PPOcrV6Engine(config)) {
			TaxiReceiptParser parser = new TaxiReceiptParser(engine);
			Path[] samples = Files.list(sampleDir)
				.filter(p -> p.toString().toLowerCase().endsWith(".png"))
				.filter(Files::isRegularFile)
				.sorted()
				.toArray(Path[]::new);
			for (Path img : samples) {
				System.out.println("\n========== " + img.getFileName() + " ==========");
				TaxiReceiptResult r = parser.parse(img);
				assertNotNull(r, "解析失败：" + img);
				print(r);
			}
		}
	}
}
