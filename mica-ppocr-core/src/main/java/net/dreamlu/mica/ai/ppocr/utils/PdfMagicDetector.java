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

package net.dreamlu.mica.ai.ppocr.utils;

import lombok.experimental.UtilityClass;

/**
 * PDF 魔数嗅探：纯 JDK 字节扫描，给 loader SPI 提供"该输入是否为 PDF"的判定。
 *
 * <p>逻辑：PDF 规范（PDF 32000-1:2008 §7.5.2）允许 header 前有最多 1024 字节的
 * 垃圾数据，所以在前 1024 字节窗口内查找 {@code %PDF-}。
 *
 * <p>被下沉到 core 是为了让 {@code PPOcrV6Engine.run(byte[])} 在没有
 * {@code mica-ppocr-pdf} 模块时也能给出"请引入 pdf 模块"的明确提示，
 * 而不是默默把 PDF 字节当图片解码（OpenCV 会失败但报错信息不友好）。
 *
 * <p>无状态工具类，所有方法线程安全。
 */
@UtilityClass
public class PdfMagicDetector {

	/**
	 * PDF 魔数：{@code %PDF-}。
	 */
	private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F', '-'};
	/**
	 * 魔数嗅探窗口（PDF 规范允许 header 前最多 1024 字节垃圾数据）。
	 */
	private static final int MAGIC_WINDOW = 1024;

	/**
	 * 字节流是否为 PDF。
	 *
	 * <p>在前 1024 字节窗口内查找 {@code %PDF-}。返回 false 的常见情况：
	 * 不是 PDF、字节为空、或字节长度不足以容纳魔数。
	 *
	 * @param bytes 待检字节流
	 * @return true 表示 PDF
	 */
	public static boolean isPdf(byte[] bytes) {
		if (bytes == null || bytes.length < PDF_MAGIC.length) {
			return false;
		}
		int limit = Math.min(bytes.length - PDF_MAGIC.length, MAGIC_WINDOW);
		for (int offset = 0; offset <= limit; offset++) {
			boolean match = true;
			for (int i = 0; i < PDF_MAGIC.length; i++) {
				if (bytes[offset + i] != PDF_MAGIC[i]) {
					match = false;
					break;
				}
			}
			if (match) {
				return true;
			}
		}
		return false;
	}
}
