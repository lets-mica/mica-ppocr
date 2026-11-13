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

package net.dreamlu.mica.ai.ppocr.structured.parser.household;

import net.dreamlu.mica.ai.ppocr.config.PPOcrV6Config;
import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Engine;
import net.dreamlu.mica.ai.ppocr.engine.PPOcrV6Result;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.BaseTest;
import net.dreamlu.mica.ai.ppocr.utils.CollUtil;
import org.opencv.core.Mat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 批量跑 household_register1~5 OCR，把原始框坐标保存为 JSON（便于后续测试直接读取，跳过 ONNX 推理），
 * 同时输出每张户的结构化解析字段，便于人工核对期望值。
 *
 * <p>输出目录：{@code src/test/resources/ocr-json/household_register/}，文件：{@code household_register{N}.json}。
 */
public class HouseholdRegisterDumpMain extends BaseTest<HouseholdRegisterParser, HouseholdRegisterResult> {

	private static final String[] IMAGES = {
		"test_images/household_register/household_register1.png",
		"test_images/household_register/household_register2.png",
		"test_images/household_register/household_register3.png",
		"test_images/household_register/household_register4.png",
		"test_images/household_register/household_register5.png",
	};

	public static void main(String[] args) throws IOException {
		Path outDir = Paths.get("mica-ppocr-structured/src/test/resources/ocr-json/household_register");
		Files.createDirectories(outDir);
		System.out.println("OCR JSON 输出目录: " + outDir.toAbsolutePath());
		new HouseholdRegisterDumpMain().run(outDir);
	}

	private static String nameOf(String imgPath) {
		String f = Paths.get(imgPath).getFileName().toString();
		int dot = f.lastIndexOf('.');
		return dot > 0 ? f.substring(0, dot) : f;
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

	private void run(Path outDir) throws IOException {
		String detModel = "models/ppocr-v6/" + TIER + "/det.onnx";
		String recModel = "models/ppocr-v6/" + TIER + "/rec.onnx";
		String dict = "models/ppocr-v6/" + TIER + "/dict.txt";
		String docOriModel = "models/ppocr-v6/doc_ori/doc_ori.onnx";

		PPOcrV6Config config = PPOcrV6Config.builder()
			.detModelPath(detModel)
			.recModelPath(recModel)
			.recCharDictPath(dict)
			.useDocOrientationClassify(USE_DOC_ORIENTATION)
			.docOrientationModelPath(docOriModel)
			.build();

		try (PPOcrV6Engine engine = new PPOcrV6Engine(config)) {
			for (String imgPath : IMAGES) {
				String name = nameOf(imgPath);
				System.out.println("\n" + CollUtil.repeat("=", 60));
				System.out.println(">>> " + name + " <<<");
				System.out.println(CollUtil.repeat("=", 60));
				Mat img = org.opencv.imgcodecs.Imgcodecs.imread(imgPath);
				if (img == null || img.empty()) {
					System.err.println("无法读取图片: " + imgPath);
					continue;
				}
				List<PPOcrV6Result> results = engine.runMat(img);

				// 1) 保存 JSON
				Path jsonPath = outDir.resolve(name + ".json");
				CollUtil.writeString(jsonPath, toJson(results), StandardCharsets.UTF_8);
				System.out.println("OCR JSON 已保存: " + jsonPath + " (" + results.size() + " boxes)");

				// 2) 输出结构化结果
				System.out.println("\n--- 结构化解析 [" + name + "] ---");
				printResults(engine, results);
				img.release();
			}
		}
	}

	// ====================================================================
	// 手写 JSON 序列化（无第三方依赖）
	// ====================================================================

	@Override
	protected HouseholdRegisterParser newParser(PPOcrV6Engine engine) {
		return new HouseholdRegisterParser(engine);
	}

	@Override
	protected void printResult(HouseholdRegisterResult r) {
		System.out.println("户号                       " + r.getHouseholdNo());
		System.out.println("姓名                       " + r.getName());
		System.out.println("与户主关系                 " + r.getRelationship());
		System.out.println("性别                       " + r.getGender());
		System.out.println("出生地                     " + r.getBirthPlace());
		System.out.println("民族                       " + r.getEthnicity());
		System.out.println("籍贯                       " + r.getNativePlace());
		System.out.println("出生日期                   " + r.getBirthDate());
		System.out.println("公民身份号码               " + r.getIdNumber());
		System.out.println("身高                       " + r.getHeight());
		System.out.println("文化程度                   " + r.getEducation());
		System.out.println("服务处所                   " + r.getWorkplace());
		System.out.println("何时由何地迁来本市(县)     " + r.getMoveToCityDate());
		System.out.println("何时由何地迁往本址         " + r.getMoveToAddress());
		System.out.println("登记日期                   " + r.getRegistrationDate());
	}
}
