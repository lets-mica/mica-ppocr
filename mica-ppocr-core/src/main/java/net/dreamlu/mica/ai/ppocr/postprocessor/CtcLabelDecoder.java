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

package net.dreamlu.mica.ai.ppocr.postprocessor;

import lombok.ToString;
import lombok.experimental.Accessors;
import net.dreamlu.mica.ai.ppocr.utils.ModelResourceLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * CTC greedy decode：argmax → 去连续重复 → 去 blank → 查表出字。
 *
 * <p>对应 Python 端的 CTCLabelDecode.call()。
 */
@ToString
public final class CtcLabelDecoder {

	/**
	 * CTC blank 标签索引。
	 */
	public static final int BLANK = 0;

	private final String[] chars;

	/**
	 * 通过字符字典路径构造解码器。
	 *
	 * <p>支持 {@code classpath:dict.txt} 前缀，把 jar 内字典透明加载。
	 *
	 * @param characterDictPath 字符字典路径（classpath: 前缀或文件系统路径）
	 */
	public CtcLabelDecoder(String characterDictPath) {
		this(ModelResourceLoader.load(characterDictPath));
	}

	/**
	 * 通过字符字典文件 Path 构造解码器。
	 *
	 * @param characterDictPath 字符字典文件
	 */
	public CtcLabelDecoder(Path characterDictPath) {
		if (!Files.isReadable(characterDictPath)) {
			throw new IllegalArgumentException(
				"字符字典不可读: " + characterDictPath.toAbsolutePath());
		}
		this.chars = parseLines(readAllLines(characterDictPath));
	}

	/**
	 * 通过字符字典字节内容构造解码器。
	 *
	 * @param dictBytes UTF-8 编码的字典字节
	 */
	public CtcLabelDecoder(byte[] dictBytes) {
		if (dictBytes == null || dictBytes.length == 0) {
			throw new IllegalArgumentException("字符字典字节为空");
		}
		this.chars = parseLines(readAllLines(dictBytes));
	}

	private static List<String> readAllLines(Path path) {
		try {
			return Files.readAllLines(path, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new RuntimeException("读取字符字典失败: " + path, e);
		}
	}

	private static List<String> readAllLines(byte[] bytes) {
		String content = new String(bytes, StandardCharsets.UTF_8);
		List<String> lines = new ArrayList<>();
		int start = 0;
		int len = content.length();
		for (int i = 0; i < len; i++) {
			if (content.charAt(i) == '\n') {
				int end = i;
				if (end > start && content.charAt(end - 1) == '\r') {
					end--;
				}
				lines.add(content.substring(start, end));
				start = i + 1;
			}
		}
		if (start < len) {
			lines.add(content.substring(start));
		}
		return lines;
	}

	private static String[] parseLines(List<String> lines) {
		List<String> list = new ArrayList<>(lines.size() + 1);
		list.add("blank");
		for (String line : lines) {
			// 字典行必须原样保留：v6 词典的"空格" token 是 U+3000 全角空格
			// （独占一行），stripTrailing 会把它抹成空串导致空格丢失。
			// 对齐 Python 参考实现的 line.rstrip("\n\r")——readAllLines 已剥离换行符。
			list.add(line == null ? "" : line);
		}
		return list.toArray(new String[0]);
	}

	/**
	 * 词表大小（包含 blank）。
	 *
	 * @return 字符字典大小
	 */
	public int vocabSize() {
		return chars.length;
	}

	/**
	 * 解码索引与概率。
	 *
	 * @param indices (B, T) int 索引
	 * @param probs   (B, T) float 概率，可为 null
	 * @return 解码结果
	 */
	public Result decode(int[][] indices, float[][] probs) {
		int b = indices.length;
		String[] texts = new String[b];
		float[] scores = new float[b];
		int vocabSize = chars.length;
		for (int i = 0; i < b; i++) {
			int[] seq = indices[i];
			int t = seq.length;
			StringBuilder sb = new StringBuilder();
			float sum = 0f;
			int count = 0;
			// 单次扫描：同时处理 keep 判定 + 拼接 + 概率累加
			if (t > 0) {
				int prev = seq[0];
				boolean keep = (prev != BLANK);
				if (keep) {
					if (prev >= 0 && prev < vocabSize) {
						sb.append(chars[prev]);
					}
					if (probs != null) {
						sum += probs[i][0];
						count++;
					}
				}
				for (int j = 1; j < t; j++) {
					int cur = seq[j];
					boolean keepCur = (cur != prev) && (cur != BLANK);
					if (keepCur) {
						if (cur >= 0 && cur < vocabSize) {
							sb.append(chars[cur]);
						}
						if (probs != null) {
							sum += probs[i][j];
							count++;
						}
					}
					prev = cur;
				}
			}
			texts[i] = sb.toString();
			// probs == null 时按 1.0f 处理（保持向后兼容）
			scores[i] = (probs == null) ? 1.0f : (count > 0 ? sum / count : 0.0f);
		}
		return new Result(texts, scores);
	}

	/**
	 * 直接对模型输出 (B, T, C) 进行解码。
	 *
	 * <p>单次扫描：argmax + max + CTC 解码 一次完成，避免分配中间的 {@code int[][]}
	 * 和 {@code float[][]} 数组。
	 *
	 * @param modelOutput 模型输出张量
	 * @return 解码结果
	 */
	public Result call(float[][][] modelOutput) {
		int b = modelOutput.length;
		String[] texts = new String[b];
		float[] scores = new float[b];
		int vocabSize = chars.length;
		for (int i = 0; i < b; i++) {
			float[][] sequence = modelOutput[i];
			int t = sequence.length;
			StringBuilder sb = new StringBuilder();
			float sum = 0f;
			int count = 0;
			if (t > 0) {
				// 第 1 步：第 0 个时间步的 argmax + max
				float[] row0 = sequence[0];
				int bestIdx = 0;
				float bestVal = row0[0];
				for (int c = 1; c < row0.length; c++) {
					if (row0[c] > bestVal) {
						bestVal = row0[c];
						bestIdx = c;
					}
				}
				int prev = bestIdx;
				if (prev != BLANK) {
					if (prev >= 0 && prev < vocabSize) {
						sb.append(chars[prev]);
					}
					sum += bestVal;
					count++;
				}
				// 第 2 步起：单次扫描 + CTC 合并
				for (int j = 1; j < t; j++) {
					float[] row = sequence[j];
					int curIdx = 0;
					float curVal = row[0];
					for (int c = 1; c < row.length; c++) {
						if (row[c] > curVal) {
							curVal = row[c];
							curIdx = c;
						}
					}
					if (curIdx != prev && curIdx != BLANK) {
						if (curIdx >= 0 && curIdx < vocabSize) {
							sb.append(chars[curIdx]);
						}
						sum += curVal;
						count++;
					}
					prev = curIdx;
				}
			}
			texts[i] = sb.toString();
			scores[i] = count > 0 ? sum / count : 0.0f;
		}
		return new Result(texts, scores);
	}

	/**
	 * 直接对扁平化的模型输出进行解码。
	 *
	 * <p>与 {@link #call(float[][][])} 逻辑完全一致，但直接在 flat float[] 上操作，
	 * 省掉整个 3D 数组（B×T 个 float[] 子对象 + B×T×C 次拷贝）的分配。
	 *
	 * @param flat 模型输出扁平数据，长度 = b * t * c，行主序
	 * @param b    batch size
	 * @param t    时间步
	 * @param c    类别数（vocab size）
	 * @return 解码结果
	 */
	public Result call(float[] flat, int b, int t, int c) {
		String[] texts = new String[b];
		float[] scores = new float[b];
		int vocabSize = chars.length;
		for (int i = 0; i < b; i++) {
			int base = i * t * c;
			StringBuilder sb = new StringBuilder();
			float sum = 0f;
			int count = 0;
			if (t > 0) {
				// 第 0 个时间步的 argmax + max
				int rowBase = base;
				int bestIdx = 0;
				float bestVal = flat[rowBase];
				for (int k = 1; k < c; k++) {
					float v = flat[rowBase + k];
					if (v > bestVal) {
						bestVal = v;
						bestIdx = k;
					}
				}
				int prev = bestIdx;
				if (prev != BLANK) {
					if (prev >= 0 && prev < vocabSize) {
						sb.append(chars[prev]);
					}
					sum += bestVal;
					count++;
				}
				// 第 1 步起
				for (int j = 1; j < t; j++) {
					rowBase = base + j * c;
					int curIdx = 0;
					float curVal = flat[rowBase];
					for (int k = 1; k < c; k++) {
						float v = flat[rowBase + k];
						if (v > curVal) {
							curVal = v;
							curIdx = k;
						}
					}
					if (curIdx != prev && curIdx != BLANK) {
						if (curIdx >= 0 && curIdx < vocabSize) {
							sb.append(chars[curIdx]);
						}
						sum += curVal;
						count++;
					}
					prev = curIdx;
				}
			}
			texts[i] = sb.toString();
			scores[i] = count > 0 ? sum / count : 0.0f;
		}
		return new Result(texts, scores);
	}

	/**
	 * CTC 解码结果。
	 */
	@lombok.Value
	@Accessors(fluent = true)
	public static class Result {
		/**
		 * 解码后的字符串数组
		 */
		String[] texts;
		/**
		 * 每条字符串的平均置信度
		 */
		float[] scores;
	}
}
