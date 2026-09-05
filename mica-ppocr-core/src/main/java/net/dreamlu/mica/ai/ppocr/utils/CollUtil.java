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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Java 8 兼容的常用 API 兼容垫片。
 *
 * <p>包含两类静态方法：
 * <ol>
 *   <li>{@link #listOf(Object)}/{@link #setOf(Object)}/{@link #mapOf(Object, Object)}：
 *       与 Java 9+ {@code List.of}/{@code Set.of}/{@code Map.of} 行为一致的不可变集合工厂。</li>
 *   <li>{@link #stripTrailing(String)}/{@link #repeat(String, int)}/{@link #readAllBytes(InputStream)}/
 *       {@link #writeString(Path, CharSequence, Charset, OpenOption...)}/{@link #pathOf(String, String...)}：
 *       Java 9/11 标准库 API 的 Java 8 回退实现。</li>
 * </ol>
 *
 * <p>使用 Java 11+ API（{@code String.stripTrailing}/{@code String.repeat}、
 * {@code InputStream.readAllBytes}、{@code Files.writeString}、{@code Path.of}）在
 * {@code -source 1.8 -target 1.8} 编译下能用 JDK 17 rt.jar 解析成功，但运行时
 * 跑在 Java 8 上会 {@code NoSuchMethodError}。统一在此提供 Java 8 兼容实现。
 *
 * <p>提供与 Java 9+ {@code List.of}/{@code Set.of}/{@code Map.of} 行为一致的不可变集合构造方法。
 * 用于在 Java 8 编译目标下替代 {@code List.of(...)} 等语法糖，避免在源码中扩散 {@code Arrays.asList}
 * 或 {@code new HashSet<>(Arrays.asList(...))} 等冗长写法。
 *
 * <p>与 Java 9+ 工厂方法的差异：
 * <ul>
 *   <li>List/Set：返回的列表/集合不可变（{@code add} 抛 {@code UnsupportedOperationException}），
 *       行为与 {@code List.of}/{@code Set.of} 一致；</li>
 *   <li>Map：检测重复键抛 {@code IllegalArgumentException}，与 {@code Map.of} 行为一致。</li>
 * </ul>
 *
 * @author dreamlu
 */
@UtilityClass
public class CollUtil {

	// ========== List ==========

	/**
	 * 返回空的不可变 List。
	 *
	 * @param <T> 元素类型
	 * @return 空的不可变 List
	 */
	public static <T> List<T> listOf() {
		return Collections.emptyList();
	}

	/**
	 * 返回包含 1 个元素的不可变 List。
	 *
	 * @param <T> 元素类型
	 * @param e1  元素
	 * @return 包含指定元素的不可变 List
	 */
	public static <T> List<T> listOf(T e1) {
		return Collections.singletonList(e1);
	}

	/**
	 * 返回包含 2 个元素的不可变 List。
	 *
	 * @param <T> 元素类型
	 * @param e1  第 1 个元素
	 * @param e2  第 2 个元素
	 * @return 包含指定元素的不可变 List
	 */
	public static <T> List<T> listOf(T e1, T e2) {
		return Collections.unmodifiableList(Arrays.asList(e1, e2));
	}

	/**
	 * 返回包含 3 个元素的不可变 List。
	 *
	 * @param <T> 元素类型
	 * @param e1  第 1 个元素
	 * @param e2  第 2 个元素
	 * @param e3  第 3 个元素
	 * @return 包含指定元素的不可变 List
	 */
	public static <T> List<T> listOf(T e1, T e2, T e3) {
		return Collections.unmodifiableList(Arrays.asList(e1, e2, e3));
	}

	/**
	 * 返回包含 4 个元素的不可变 List。
	 *
	 * @param <T> 元素类型
	 * @param e1  第 1 个元素
	 * @param e2  第 2 个元素
	 * @param e3  第 3 个元素
	 * @param e4  第 4 个元素
	 * @return 包含指定元素的不可变 List
	 */
	public static <T> List<T> listOf(T e1, T e2, T e3, T e4) {
		return Collections.unmodifiableList(Arrays.asList(e1, e2, e3, e4));
	}

	/**
	 * 返回包含 5 个元素的不可变 List。
	 *
	 * @param <T> 元素类型
	 * @param e1  第 1 个元素
	 * @param e2  第 2 个元素
	 * @param e3  第 3 个元素
	 * @param e4  第 4 个元素
	 * @param e5  第 5 个元素
	 * @return 包含指定元素的不可变 List
	 */
	public static <T> List<T> listOf(T e1, T e2, T e3, T e4, T e5) {
		return Collections.unmodifiableList(Arrays.asList(e1, e2, e3, e4, e5));
	}

	/**
	 * 返回包含 N 个元素的不可变 List。
	 *
	 * <p>直接包装 {@link Arrays#asList}：避免一次冗余的 {@code new ArrayList<>(...)} 拷贝，
	 * 与 {@code List.of(...)} 行为一致（不可变、定长、拒绝 null）。
	 *
	 * @param <T>      元素类型
	 * @param elements 元素数组
	 * @return 包含指定元素的不可变 List
	 */
	@SafeVarargs
	public static <T> List<T> listOf(T... elements) {
		if (elements == null || elements.length == 0) {
			return Collections.emptyList();
		}
		return Collections.unmodifiableList(Arrays.asList(elements));
	}

	/**
	 * 将 {@link Collection} 转为不可变 List。允许 null 入参（返回空列表）。
	 *
	 * @param <T>  元素类型
	 * @param coll 源集合，可为 null
	 * @return 不可变 List；入参为 null 时返回空 List
	 */
	public static <T> List<T> unmodifiableList(Collection<? extends T> coll) {
		if (coll == null) {
			return Collections.emptyList();
		}
		return Collections.unmodifiableList(new ArrayList<>(coll));
	}

	// ========== Set ==========

	/**
	 * 返回空的不可变 Set。
	 *
	 * @param <T> 元素类型
	 * @return 空的不可变 Set
	 */
	public static <T> Set<T> setOf() {
		return Collections.emptySet();
	}

	/**
	 * 返回包含 1 个元素的不可变 Set。
	 *
	 * @param <T> 元素类型
	 * @param e1  元素
	 * @return 包含指定元素的不可变 Set
	 */
	public static <T> Set<T> setOf(T e1) {
		return Collections.singleton(e1);
	}

	/**
	 * 返回包含 N 个元素的不可变 Set，重复元素抛 {@link IllegalArgumentException}。
	 *
	 * @param <T>      元素类型
	 * @param elements 元素数组
	 * @return 包含指定元素的不可变 Set
	 * @throws IllegalArgumentException 元素重复
	 */
	@SafeVarargs
	public static <T> Set<T> setOf(T... elements) {
		if (elements == null || elements.length == 0) {
			return Collections.emptySet();
		}
		LinkedHashSet<T> set = new LinkedHashSet<>(elements.length);
		for (T e : elements) {
			if (!set.add(e)) {
				throw new IllegalArgumentException("Duplicate element in setOf: " + e);
			}
		}
		return Collections.unmodifiableSet(set);
	}

	// ========== Map ==========

	/**
	 * 返回空的不可变 Map。
	 *
	 * @param <K> 键类型
	 * @param <V> 值类型
	 * @return 空的不可变 Map
	 */
	public static <K, V> Map<K, V> mapOf() {
		return Collections.emptyMap();
	}

	/**
	 * 返回包含 1 个键值对的不可变 Map。
	 *
	 * @param <K> 键类型
	 * @param <V> 值类型
	 * @param k1  键
	 * @param v1  值
	 * @return 包含指定键值对的不可变 Map
	 */
	public static <K, V> Map<K, V> mapOf(K k1, V v1) {
		Map<K, V> map = new LinkedHashMap<>();
		map.put(k1, v1);
		return Collections.unmodifiableMap(map);
	}

	/**
	 * 返回包含 2 个键值对的不可变 Map。
	 *
	 * @param <K> 键类型
	 * @param <V> 值类型
	 * @param k1  第 1 个键
	 * @param v1  第 1 个值
	 * @param k2  第 2 个键
	 * @param v2  第 2 个值
	 * @return 包含指定键值对的不可变 Map
	 * @throws IllegalArgumentException 键重复
	 */
	public static <K, V> Map<K, V> mapOf(K k1, V v1, K k2, V v2) {
		Map<K, V> map = new LinkedHashMap<>();
		map.put(k1, v1);
		putUnique(map, k2, v2);
		return Collections.unmodifiableMap(map);
	}

	/**
	 * 返回包含 3 个键值对的不可变 Map。
	 *
	 * @param <K> 键类型
	 * @param <V> 值类型
	 * @param k1  第 1 个键
	 * @param v1  第 1 个值
	 * @param k2  第 2 个键
	 * @param v2  第 2 个值
	 * @param k3  第 3 个键
	 * @param v3  第 3 个值
	 * @return 包含指定键值对的不可变 Map
	 * @throws IllegalArgumentException 键重复
	 */
	public static <K, V> Map<K, V> mapOf(K k1, V v1, K k2, V v2, K k3, V v3) {
		Map<K, V> map = new LinkedHashMap<>();
		map.put(k1, v1);
		putUnique(map, k2, v2);
		putUnique(map, k3, v3);
		return Collections.unmodifiableMap(map);
	}

	/**
	 * 返回包含 4 个键值对的不可变 Map。
	 *
	 * @param <K> 键类型
	 * @param <V> 值类型
	 * @param k1  第 1 个键
	 * @param v1  第 1 个值
	 * @param k2  第 2 个键
	 * @param v2  第 2 个值
	 * @param k3  第 3 个键
	 * @param v3  第 3 个值
	 * @param k4  第 4 个键
	 * @param v4  第 4 个值
	 * @return 包含指定键值对的不可变 Map
	 * @throws IllegalArgumentException 键重复
	 */
	public static <K, V> Map<K, V> mapOf(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4) {
		Map<K, V> map = new LinkedHashMap<>();
		map.put(k1, v1);
		putUnique(map, k2, v2);
		putUnique(map, k3, v3);
		putUnique(map, k4, v4);
		return Collections.unmodifiableMap(map);
	}

	/**
	 * 通过 {@code Map.Entry} 数组构造不可变 Map（用于 5+ 键值对场景，替代 Java 9+ 的 {@code Map.ofEntries}）。
	 *
	 * @param entries 键值对数组
	 * @param <K>     键类型
	 * @param <V>     值类型
	 * @return 不可变 Map
	 * @throws IllegalArgumentException 键重复
	 */
	@SafeVarargs
	public static <K, V> Map<K, V> mapOfEntries(Map.Entry<K, V>... entries) {
		if (entries == null || entries.length == 0) {
			return Collections.emptyMap();
		}
		Map<K, V> map = new LinkedHashMap<>(entries.length);
		for (Map.Entry<K, V> e : entries) {
			putUnique(map, e.getKey(), e.getValue());
		}
		return Collections.unmodifiableMap(map);
	}

	/**
	 * 简单的 {@code Map.Entry} 实现，工厂方法。
	 *
	 * @param <K>   键类型
	 * @param <V>   值类型
	 * @param key   键
	 * @param value 值
	 * @return 不可变的 {@link Map.Entry}
	 */
	public static <K, V> Map.Entry<K, V> entry(K key, V value) {
		return new AbstractEntry<>(key, value);
	}

	private static <K, V> void putUnique(Map<K, V> map, K key, V value) {
		if (map.put(key, value) != null) {
			throw new IllegalArgumentException("Duplicate key in mapOf: " + key);
		}
	}

	// ========== 可变工厂 ==========

	/**
	 * 提供 {@link HashSet} 形式的可变工厂。
	 * 用于在源码中替换 {@code new HashSet<>(Arrays.asList(...))} 的冗长写法。
	 *
	 * @param <T>      元素类型
	 * @param elements 初始元素
	 * @return 包含初始元素的 {@link HashSet}
	 */
	@SafeVarargs
	public static <T> Set<T> newHashSet(T... elements) {
		return new HashSet<>(Arrays.asList(elements));
	}

	/**
	 * 提供 {@link HashMap} 形式的可变工厂。
	 *
	 * @param <K> 键类型
	 * @param <V> 值类型
	 * @return 空的 {@link HashMap}
	 */
	public static <K, V> Map<K, V> newHashMap() {
		return new HashMap<>();
	}

	// ========== 字符串 / IO / NIO 回退实现 ==========

	/**
	 * Java 11+ {@code String.stripTrailing} 的 Java 8 实现。
	 *
	 * <p>仅去掉尾部 {@link Character#isWhitespace(char) 空白字符}，不剔除 BOM、不剔除零宽字符。
	 * null 视为空串。
	 *
	 * @param s 源字符串
	 * @return 去掉尾部空白后的字符串；入参为 null 时返回 null
	 */
	public static String stripTrailing(String s) {
		if (s == null || s.isEmpty()) {
			return s == null ? null : "";
		}
		int end = s.length();
		while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) {
			end--;
		}
		return end == s.length() ? s : s.substring(0, end);
	}

	// ====================================================================
	// Java 9/11+ 标准库 API 的 Java 8 回退实现
	// ====================================================================

	/**
	 * Java 11+ {@code String.repeat(int)} 的 Java 8 实现。
	 *
	 * @param s     要重复的字符串（null 抛 NPE，与 JDK 行为一致）
	 * @param count 重复次数
	 * @return s 拼接 count 次
	 */
	public static String repeat(String s, int count) {
		if (count < 0) {
			throw new IllegalArgumentException("count is negative: " + count);
		}
		if (count == 0 || s == null || s.isEmpty()) {
			return "";
		}
		if (count == 1) {
			return s;
		}
		StringBuilder sb = new StringBuilder(s.length() * count);
		for (int i = 0; i < count; i++) {
			sb.append(s);
		}
		return sb.toString();
	}

	/**
	 * Java 9+ {@code InputStream.readAllBytes()} 的 Java 8 实现。
	 *
	 * <p>读完整个流直到 EOF；不会自动关闭流。
	 *
	 * @param in 输入流
	 * @return 流的全部字节
	 * @throws IOException IO 异常
	 */
	public static byte[] readAllBytes(InputStream in) throws IOException {
		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		byte[] chunk = new byte[8192];
		int n;
		while ((n = in.read(chunk)) != -1) {
			buf.write(chunk, 0, n);
		}
		return buf.toByteArray();
	}

	/**
	 * Java 11+ {@code Files.writeString(Path, CharSequence, Charset, OpenOption...)} 的 Java 8 实现。
	 *
	 * @param path    目标文件
	 * @param content 待写内容
	 * @param charset 字符集
	 * @param options 写选项
	 * @return 目标路径
	 * @throws IOException IO 异常
	 */
	public static Path writeString(Path path, CharSequence content, Charset charset, OpenOption... options) throws IOException {
		byte[] bytes = content.toString().getBytes(charset);
		return Files.write(path, bytes, options);
	}

	/**
	 * Java 11+ {@code Path.of(String, String...)} 的 Java 8 替代：{@code Paths.get}。
	 *
	 * <p>已签名为 {@code pathOf} 以保证替代品在调用点统一可识别。
	 *
	 * @param first 路径首段
	 * @param more  路径剩余段
	 * @return 拼接得到的 {@link Path}
	 */
	public static Path pathOf(String first, String... more) {
		return Paths.get(first, more);
	}

	/**
	 * Java 16+ {@code Stream.toList()} 的 Java 8 替代：{@code Collectors.toList()}。
	 *
	 * <p>注意：与 {@code Stream.toList()} 不可变语义不同，{@code Collectors.toList()} 返回可变 ArrayList。
	 * 在仅消费场景下语义等价。
	 *
	 * @param <T>    元素类型
	 * @param stream 源流
	 * @return 收集得到的 {@link List}
	 */
	public static <T> List<T> toList(Stream<T> stream) {
		return stream.collect(Collectors.toList());
	}

	private static final class AbstractEntry<K, V> implements Map.Entry<K, V> {
		private final K key;
		private V value;

		AbstractEntry(K key, V value) {
			this.key = key;
			this.value = value;
		}

		@Override
		public K getKey() {
			return key;
		}

		@Override
		public V getValue() {
			return value;
		}

		@Override
		public V setValue(V value) {
			V old = this.value;
			this.value = value;
			return old;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof Map.Entry)) return false;
			Map.Entry<?, ?> that = (Map.Entry<?, ?>) o;
			return java.util.Objects.equals(key, that.getKey())
				&& java.util.Objects.equals(value, that.getValue());
		}

		@Override
		public int hashCode() {
			return (key == null ? 0 : key.hashCode())
				^ (value == null ? 0 : value.hashCode());
		}

		@Override
		public String toString() {
			return key + "=" + value;
		}
	}
}
