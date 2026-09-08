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

import net.dreamlu.mica.ai.ppocr.utils.CollUtil;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CtcLabelDecoder 单元测试。
 */
class CtcLabelDecoderTest {

	@TempDir
	Path tempDir;

	private Path createDict() throws IOException {
		Path dict = tempDir.resolve("dict.txt");
		// blank=0(内置), idx1=你, idx2=好, idx3=A, idx4=B, idx5=C
		CollUtil.writeString(dict, "你\n好\nA\nB\nC\n", StandardCharsets.UTF_8);
		return dict;
	}

	@Test
	void vocabSize_includesBlank() throws IOException {
		CtcLabelDecoder decoder = new CtcLabelDecoder(createDict());
		// 5 个字符 + 1 个 blank = 6
		assertEquals(6, decoder.vocabSize());
	}

	@Test
	void call_simpleDecode() throws IOException {
		// modelOutput: batch=1, time=4, classes(词典+blank)
		// blank=0, A=3, B=4 → 期望输出 "AB"
		float[][][] output = {
			{
				{0.9f, 0.02f, 0.02f, 0.03f, 0.03f},  // blank
				{0.05f, 0.05f, 0.05f, 0.8f, 0.05f},   // A (idx=3)
				{0.05f, 0.05f, 0.05f, 0.8f, 0.05f},   // A (重复，应去重)
				{0.03f, 0.03f, 0.03f, 0.03f, 0.88f}, // B (idx=4)
			}
		};
		CtcLabelDecoder decoder = new CtcLabelDecoder(createDict());
		CtcLabelDecoder.Result result = decoder.call(output);
		assertEquals(1, result.texts().length);
		assertEquals("AB", result.texts()[0]);
		assertTrue(result.scores()[0] > 0.5f);
	}

	@Test
	void decode_manualIndices() throws IOException {
		// idx: 0(blank), 1(你), 1(你重复), 0(blank), 2(好)
		int[][] indices = {{0, 1, 1, 0, 2}};
		float[][] probs = {{0.9f, 0.8f, 0.8f, 0.9f, 0.7f}};
		CtcLabelDecoder decoder = new CtcLabelDecoder(createDict());
		CtcLabelDecoder.Result result = decoder.decode(indices, probs);
		assertEquals("你好", result.texts()[0]);
	}

	@Test
	void decode_withBlankOnly() throws IOException {
		int[][] indices = {{0, 0, 0}};
		float[][] probs = {{0.9f, 0.9f, 0.9f}};
		CtcLabelDecoder decoder = new CtcLabelDecoder(createDict());
		CtcLabelDecoder.Result result = decoder.decode(indices, probs);
		assertEquals("", result.texts()[0]);
		assertEquals(0.0f, result.scores()[0]);
	}

	@Test
	void decode_probsNull() throws IOException {
		int[][] indices = {{1, 2, 3}};
		CtcLabelDecoder decoder = new CtcLabelDecoder(createDict());
		CtcLabelDecoder.Result result = decoder.decode(indices, null);
		assertEquals(1.0f, result.scores()[0]);
	}

	@Test
	void decode_emptySequence() throws IOException {
		int[][] indices = {{}};
		float[][] probs = {{}};
		CtcLabelDecoder decoder = new CtcLabelDecoder(createDict());
		CtcLabelDecoder.Result result = decoder.decode(indices, probs);
		assertEquals("", result.texts()[0]);
		assertEquals(0.0f, result.scores()[0]);
	}

	@Test
	void decode_multiBatch() throws IOException {
		// batch=2: "你" 和 "B"
		int[][] indices = {{0, 1, 2}, {0, 0, 4}};
		float[][] probs = {{0.9f, 0.8f, 0.7f}, {0.9f, 0.85f, 0.6f}};
		CtcLabelDecoder decoder = new CtcLabelDecoder(createDict());
		CtcLabelDecoder.Result result = decoder.decode(indices, probs);
		assertEquals(2, result.texts().length);
		assertEquals("你好", result.texts()[0]);
		assertEquals("B", result.texts()[1]);
	}

	@Test
	void dictFileNotFound() {
		assertThrows(IllegalArgumentException.class, () ->
			new CtcLabelDecoder("/nonexistent/dict.txt"));
	}

	/**
	 * 回归测试：v6 词典的空白 token 行必须原样保留。
	 *
	 * <p>v6 词典中间有一行 U+3000 全角空格（CJK 空格），末尾还有一行 ASCII 空格
	 * （PaddleOCR use_space_char 约定，对应模型最后一个输出类）。
	 * 历史上 parseLines 用 stripTrailing 把它们抹成空串，导致英文单词粘连。
	 */
	@Test
	void decode_preservesSpaceTokens() throws IOException {
		Path dict = tempDir.resolve("dict-space.txt");
		// blank=0(内置), idx1=A, idx2=　(U+3000), idx3=B, idx4=' '(末尾 ASCII 空格)
		CollUtil.writeString(dict, "A\n　\nB\n \n", StandardCharsets.UTF_8);
		CtcLabelDecoder decoder = new CtcLabelDecoder(dict);
		assertEquals(5, decoder.vocabSize());

		// A, blank, ' ', B, blank, U+3000, A → "A B　A"
		int[][] indices = {{1, 0, 4, 3, 0, 2, 1}};
		float[][] probs = {{0.9f, 0.9f, 0.9f, 0.9f, 0.9f, 0.9f, 0.9f}};
		CtcLabelDecoder.Result result = decoder.decode(indices, probs);
		assertEquals("A B　A", result.texts()[0]);
	}

	/**
	 * 回归测试：通过 byte[] 构造时同样保留空白 token 行。
	 */
	@Test
	void decode_preservesSpaceTokensFromBytes() {
		byte[] dictBytes = "A\nB\n \n".getBytes(StandardCharsets.UTF_8);
		CtcLabelDecoder decoder = new CtcLabelDecoder(dictBytes);
		assertEquals(4, decoder.vocabSize());

		int[][] indices = {{1, 3, 2}};
		CtcLabelDecoder.Result result = decoder.decode(indices, null);
		assertEquals("A B", result.texts()[0]);
	}
}
