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

package net.dreamlu.mica.ai.ppocr.structured.parser.invoice;

import net.dreamlu.mica.ai.ppocr.config.PPOcrV6Config;
import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Engine;
import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Result;
import net.dreamlu.mica.ai.ppocr.utils.CollUtil;
import org.opencv.core.Mat;
import org.opencv.imgcodecs.Imgcodecs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 批量跑 invoice1~invoice5 老版增值税发票 OCR，把原始框坐标保存为 JSON（便于后续测试直接读取，跳过 ONNX 推理），
 * 同时输出每张发票的结构化解析字段，便于人工核对期望值。
 *
 * <p>输出目录：{@code src/test/resources/ocr-json/invoice/}，文件：{@code invoice{N}.json}。
 */
public class InvoiceDumpMain {

	private static final String[] IMAGES = {
		"test_images/invoice/invoice1.jpg",
		"test_images/invoice/invoice2.jpg",
		"test_images/invoice/invoice3.jpg",
		"test_images/invoice/invoice4.jpg",
		"test_images/invoice/invoice5.jpg",
		"test_images/invoice/invoice6.jpg",
	};

	public static void main(String[] args) throws IOException {
		Path outDir = Paths.get("mica-ppocr-structured/src/test/resources/ocr-json/invoice");
		Files.createDirectories(outDir);
		System.out.println("OCR JSON 输出目录: " + outDir.toAbsolutePath());

		PPOcrV6Config config = PPOcrV6Config.builder()
			.detModelPath("models/ppocr-v6/tiny/det.onnx")
			.recModelPath("models/ppocr-v6/tiny/rec.onnx")
			.recCharDictPath("models/ppocr-v6/tiny/dict.txt")
			.useDocOrientationClassify(true)
			.docOrientationModelPath("models/ppocr-v6/doc_ori/doc_ori.onnx")
			.build();

		try (PPOcrV6Engine engine = new PPOcrV6Engine(config)) {
			InvoiceParser dispatcher = new InvoiceParser(engine);
			for (String imgPath : IMAGES) {
				String name = nameOf(imgPath);
				System.out.println("\n" + CollUtil.repeat("=", 60));
				System.out.println(">>> " + name + " <<<");
				System.out.println(CollUtil.repeat("=", 60));
				Mat img = Imgcodecs.imread(imgPath);
				if (img == null || img.empty()) {
					System.err.println("无法读取图片: " + imgPath);
					continue;
				}
				List<PPOcrV6Result> results = engine.runMat(img);

				// 1) 保存 JSON
				Path jsonPath = outDir.resolve(name + ".json");
				CollUtil.writeString(jsonPath, toJson(results), StandardCharsets.UTF_8);
				System.out.println("OCR JSON 已保存: " + jsonPath + " (" + results.size() + " boxes)");

				// 2) 输出结构化结果（走分发器：电子版优先 → 20 位号码判别失败回退老版）
				System.out.println("\n--- 结构化解析 [" + name + "] ---");
				printResult(dispatcher.parseResults(results));
				img.release();
			}
		}
	}

	private static String nameOf(String imgPath) {
		String f = Paths.get(imgPath).getFileName().toString();
		int dot = f.lastIndexOf('.');
		return dot > 0 ? f.substring(0, dot) : f;
	}

	private static void printResult(InvoiceResult inv) {
		System.out.println("发票代码       " + inv.getInvoiceCode());
		System.out.println("发票号码       " + inv.getInvoiceNo());
		System.out.println("开票日期       " + inv.getInvoiceDate());
		System.out.println();
		System.out.println("--- 购买方 ---");
		System.out.println("名称           " + inv.getBuyerName());
		System.out.println("税号           " + inv.getBuyerTaxNo());
		System.out.println("地址电话       " + inv.getBuyerAddressPhone());
		System.out.println("开户行账号     " + inv.getBuyerBankAccount());
		System.out.println();
		System.out.println("--- 销售方 ---");
		System.out.println("名称           " + inv.getSellerName());
		System.out.println("税号           " + inv.getSellerTaxNo());
		System.out.println("地址电话       " + inv.getSellerAddressPhone());
		System.out.println("开户行账号     " + inv.getSellerBankAccount());
		System.out.println();
		System.out.println("--- 明细 ---");
		for (InvoiceItem item : inv.getItems()) {
			System.out.println("商品/服务名称  " + item.getGoodsName());
			System.out.println("金额           " + item.getAmount());
			System.out.println("税率           " + item.getTaxRate());
			System.out.println("税额           " + item.getTaxAmount());
		}
		System.out.println();
		System.out.println("--- 合计 ---");
		System.out.println("价税合计(大写) " + inv.getTotalAmountUpper());
		System.out.println("价税合计(小写) " + inv.getTotalAmountLower());
		System.out.println();
		System.out.println("--- 底栏 ---");
		System.out.println("收款人         " + inv.getPayee());
		System.out.println("复核人         " + inv.getReviewer());
		System.out.println("开票人         " + inv.getIssuer());
	}

	private static String toJson(List<PPOcrV6Result> results) {
		StringBuilder sb = new StringBuilder();
		sb.append("[\n");
		for (int i = 0; i < results.size(); i++) {
			PPOcrV6Result r = results.get(i);
			sb.append("  {");
			sb.append("\"text\":").append(jsonStr(r.text())).append(',');
			sb.append("\"score\":").append(String.format("%.6f", r.score())).append(',');
			sb.append("\"rotatedDegrees\":").append(r.rotatedDegrees()).append(',');
			int[][] b = r.box();
			sb.append("\"box\":[");
			for (int j = 0; j < 4; j++) {
				sb.append('[').append(b[j][0]).append(',').append(b[j][1]).append(']');
				if (j < 3) sb.append(',');
			}
			sb.append(']');
			sb.append('}');
			if (i < results.size() - 1) sb.append(',');
			sb.append('\n');
		}
		sb.append(']');
		return sb.toString();
	}

	private static String jsonStr(String s) {
		StringBuilder sb = new StringBuilder();
		sb.append('"');
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '\"':
					sb.append("\\\"");
					break;
				case '\\':
					sb.append("\\\\");
					break;
				case '\n':
					sb.append("\\n");
					break;
				case '\r':
					sb.append("\\r");
					break;
				case '\t':
					sb.append("\\t");
					break;
				default:
					if (c < 0x20) {
						sb.append(String.format("\\u%04x", (int) c));
					} else {
						sb.append(c);
					}
					break;
			}
		}
		sb.append('"');
		return sb.toString();
	}
}
