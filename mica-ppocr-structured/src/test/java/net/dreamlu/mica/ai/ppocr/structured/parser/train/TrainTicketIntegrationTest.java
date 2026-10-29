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

package net.dreamlu.mica.ai.ppocr.structured.parser.train;

import net.dreamlu.mica.ai.ppocr.config.PPOcrV6Config;
import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Engine;
import net.dreamlu.mica.ai.ppocr.utils.CollUtil;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 火车票解析器端到端集成测试（使用真实 OCR 模型 + 真实样本图）。
 *
 * <p>样本图来源：百度 OCR 火车票演示页（{@link BaiduSampleImageFetcher#fetchTrainTicketSamples}）。
 * 测试样本由 {@code BaiduSampleImageFetcher} 抓取并保存到 {@code test_images/train/}，
 * 缺失时通过 {@link Assumptions} 跳过。
 *
 * <p>依赖仓库根目录 {@code models/ppocr-v6/tiny/} 模型；
 * 模型缺失时同样跳过（模型不随仓库分发）。
 */
class TrainTicketIntegrationTest {

	private static final String DEFAULT_TIER = "tiny";

	private static void print(TrainTicketResult r) {
		System.out.println("  departure:        " + r.getDeparture());
		System.out.println("  arrival:          " + r.getArrival());
		System.out.println("  trainNumber:      " + r.getTrainNumber());
		System.out.println("  departureDate:    " + r.getDepartureDate());
		System.out.println("  departureTime:    " + r.getDepartureTime());
		System.out.println("  seatNumber:       " + r.getSeatNumber());
		System.out.println("  seatClass:        " + r.getSeatClass());
		System.out.println("  passengerName:    " + r.getPassengerName());
		System.out.println("  amount:           " + r.getAmount());
		System.out.println("  ticketNo:         " + r.getTicketNo());
		System.out.println("  invoiceNo:        " + r.getInvoiceNo());
		System.out.println("  eTicketNo:        " + r.getETicketNo());
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
	void parseRealTrainTicketImages() throws Exception {
		Path root = findRepositoryRoot();
		Path modelDir = root.resolve("models/ppocr-v6/" + DEFAULT_TIER);
		Assumptions.assumeTrue(Files.isRegularFile(modelDir.resolve("det.onnx"))
				&& Files.isRegularFile(modelDir.resolve("rec.onnx"))
				&& Files.isRegularFile(modelDir.resolve("dict.txt")),
			"tiny 模型缺失，跳过集成测试");

		Path sampleDir = root.resolve("test_images/train");
		Assumptions.assumeTrue(Files.isDirectory(sampleDir) && sampleDir.toFile().listFiles().length > 0,
			"test_images/train/ 样本缺失，跳过集成测试");

		PPOcrV6Config config = PPOcrV6Config.builder()
			.detModelPath(modelDir.resolve("det.onnx").toString())
			.recModelPath(modelDir.resolve("rec.onnx").toString())
			.recCharDictPath(modelDir.resolve("dict.txt").toString())
			.build();

		try (PPOcrV6Engine engine = new PPOcrV6Engine(config)) {
			TrainTicketParser parser = new TrainTicketParser(engine);
			Path[] samples = Files.list(sampleDir)
				.filter(p -> p.toString().toLowerCase().endsWith(".png"))
				.filter(Files::isRegularFile)
				.sorted()
				.toArray(Path[]::new);
			for (Path img : samples) {
				System.out.println("\n========== " + img.getFileName() + " ==========");
				TrainTicketResult r = parser.parse(img);
				assertNotNull(r, "解析失败：" + img);
				print(r);
			}
		}
	}
}
