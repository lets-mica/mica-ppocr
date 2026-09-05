# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

`mica-ppocr` is a **Java 8 兼容** port of PP-OCRv6 text detection + recognition, running pure
[ONNX Runtime](https://onnxruntime.ai/) inference with **zero PaddlePaddle dependency**. It is a
line-for-line port of the single-file Python reference `ppocrv6_onnx.py` from
[`AIwork4me/ppocrv6_onnx`](https://github.com/AIwork4me/ppocrv6_onnx), reproducing the exact
pre/post-processing (DB post-process, CTC greedy decode, pyclipper-equivalent polygon unclip).

**Bit-exactness with the Python reference is the primary correctness goal.** Defaults favor CPU
single-threaded execution (`intraOp=interOp=1`) to guarantee deterministic, cross-platform output.
When changing numeric logic, verify against the Python implementation rather than just "looks reasonable."

**Java 8 兼容性约束**：源码编译目标为 Java 8（`<maven.compiler.source>1.8</maven.compiler.source>`），
所有 runtime 依赖的字节码均已校验为 Java 8（major 52）。禁止使用 Java 9+ 语言特性和标准库 API：

- **语言特性**：不要用 `record`（用 Lombok `@Value` + `@Accessors(fluent = true)` 替代）；不要用 `instanceof`
  模式匹配、`switch` 表达式、文本块、`var`

- **集合工厂**：不要用 `List.of`/`Set.of`/`Map.of`（用 `CollUtil.listOf/setOf/mapOf` 替代）

- **字符串 API**（Java 11+）：不要用 `String.stripTrailing/stripLeading/strip/isBlank/repeat/lines`，
  统一用 `CollUtil.stripTrailing/repeat`；其余若用到请在 `CollUtil` 添 helper

- **IO API**（Java 9/11+）：不要用 `InputStream.readAllBytes` / `Files.writeString` / `Path.of` / `Stream.toList`，
  统一用 `CollUtil.readAllBytes/writeString/pathOf/toList`

- **编译参数**：`maven.compiler.release` 故意不设置。Lombok 1.18.x + JDK 17+ 在 `release 1.8`
  下会触发"不支持发行版本 1.8"；用 `-source/-target` 时若保留 `release` 也会踩坑。
  且**不要在无** **`--release`** **情况下混用 Java 11+ API**——`javac` 会用 JDK 17 rt.jar 解析，编译通过但
  Java 8 运行时 `NoSuchMethodError`。所有 Java 11+ API 都走 `CollUtil` 兜底。

## Module structure

```
mica-ppocr/                     ← parent pom (packaging=pom)
├── mica-ppocr-core/            ← 核心引擎 + PDF 双通道，零 Spring 依赖
│   └── src/main/java/net/dreamlu/mica/ai/ppocr/
│       ├── engine/PPOcrV6Engine.java     ← run/runMat 等入口（自动嗅探 PDF）
│       ├── engine/PPOcrV6Result.java
│       ├── config/PPOcrV6Config.java
│       ├── preprocessor/DetectionPreprocessor.java, RecognitionPreprocessor.java
│       ├── postprocessor/DbPostProcessor.java, CtcLabelDecoder.java
│       ├── pdf/                          ← PDF 双通道（与 engine 同模块）
│       │   ├── PdfOcrConfig.java         ← renderDpi / minTextChars / minReadableRatio / forceOcr
│       │   ├── PdfTextExtractor.java      ← 文本层坐标抽取（行聚类 + 大间距拆分）
│       │   ├── PdfTextQuality.java       ← 文本层质量评分（字符数 + 可读占比）
│       │   └── PdfPageResult.java        ← per-page 结果（pageIndex + viaOcr）
│       └── utils/{BoxUtil, BufferedImageUtils, CropUtil, Offset, NdArrayUtils, OrtProviders, PdfMagicDetector}.java
├── mica-ppocr-structured/      ← 结构化解析模块：把 OCR 散落文字框组织成业务字段
│   └── src/main/java/net/dreamlu/mica/ai/ppocr/structured/
│       ├── BaseStructuredParser.java    ← SPI 接口 (parseResults)
│       ├── LabelMatcher.java            ← 标签定位 + 位置匹配 + 正则兜底公共骨架
│       └── parser/
│           └── vehicle/                 ← 行驶证解析器（首个实现）
│               ├── VehicleLicenseParser.java
│               └── VehicleLicenseResult.java
├── mica-ppocr-solon-plugin/    ← Solon 自动配置
└── mica-ppocr-spring-boot-starter/  ← Spring Boot 自动配置
    └── src/main/java/net/dreamlu/mica/ai/ppocr/autoconfigure/
        ├── PPOCRAutoConfiguration.java
        ├── PPOCRProperties.java
        ├── PdfAutoConfiguration.java    ← 把 mica.ai.ppocr.pdf.* 绑定为 PdfOcrConfig
        ├── PPOcrTemplate.java           ← 解析器模板（不含 PDF 反射门面）
        └── OpenCVNativeLoader.java
```

## Commands

```bash
mvn -DskipTests package      # 全量构建
mvn package                  # 构建 + 运行测试
mvn test                     # 运行所有测试
mvn test -Dtest=NpUtilTest   # 运行单个测试
```

Native libs (OpenCV, ONNX Runtime) are pulled by Maven for Windows/Linux/macOS — no manual install.

## Model setup (required before running)

Models are **not** in the repo. Place under `models/ppocr-v6/{tier}/`，三档可选：

```
models/ppocr-v6/
├── tiny/        # 轻量，速度快，精度一般 (det 1.7MB + rec 4.3MB)
│   ├── det.onnx
│   ├── rec.onnx
│   └── dict.txt             # ~2855 字符
├── small/       # 平衡档 (det 9.4MB + rec 20.2MB)
│   ├── det.onnx
│   ├── rec.onnx
│   └── dict.txt             # ~2855 字符
└── medium/      # 高精度档 (det 59.2MB + rec 73.0MB)
    ├── det.onnx
    ├── rec.onnx
    └── dict.txt             # ~7180 字符
```

CLI 默认使用 `--tier tiny`；可用 `--tier small|medium` 切换，或用 `--det-model`/`--rec-model`/`--dict` 显式指定覆盖。

模型来源：`E:\codes\ai\mica-ai\model-tools\ppocr\model\out-by-spec`

## Architecture

The pipeline flows: **detect → sort boxes → crop → recognize**.

### mica-ppocr-core

- **`engine/PPOcrV6Engine`** — the orchestrator and only public entry point. Owns the two
  `OrtSession`s (det + rec), is `Closeable` (use try-with-resources), and exposes `detect()`,
  `recognize()`, and the full `run(Mat)`. Accepts a `PPOcrV6Config` (Lombok `@Builder`).
  `run(byte[])` / `run(Path)` 自动嗅探 `%PDF-` 魔数：命中时按 PDF 双通道处理并平铺所有页结果；
  非 PDF 走图片通道。`run(byte[])` / `run(InputStream)` / `parse(byte[])` / `parse(InputStream)`
  均不声明 `throws IOException`——PDF 解析或流读取失败时引擎内部包为 `UncheckedIOException`
  抛出，调用方免 try-catch。

- **`config/PPOcrV6Config`** — `@Getter @Builder` config for all tunables.

- **`engine/PPOcrV6Result`** — Lombok 类（`@Accessors(fluent = true)`，text/score/box/rotatedDegrees），box 为 `int[][]` 四顶点（左上、右上、右下、左下）。

- **`preprocessor/DetectionPreprocessor`** — resize to limit-side constraints, normalize, HWC→NCHW.

- **`postprocessor/DbPostProcessor`** — DB binary-map → contours → boxes.

- **`preprocessor/RecognitionPreprocessor`** — batches crops, resizes, pads to common width.

- **`postprocessor/CtcLabelDecoder`** — loads char dict, CTC greedy decode → text + score.

- **`utils/`** — the numpy/cv2 equivalents:

  - `NdArrayUtils` — argmax/max/stack/pad/clip over float arrays.

  - `BoxUtil` — `sortQuadBoxes` (reading order), minAreaRect/boxPoints.

  - `Offset` — pyclipper `PyclipperOffset` equivalent via JTS `BufferOp` + `JOIN_ROUND`.

  - `CropUtil` — perspective-warp crop; returns `null` for invalid crops;
    `run()` filters these `null`s out — preserve this null-skip contract.

  - `OrtProviders` — picks the ORT execution provider; `forceCpu` (negation of
    `preferAccelerator`) is the default.

### mica-ppocr-structured

- **`BaseStructuredParser<R>`** — `@FunctionalInterface` SPI 接口，规范所有解析器的入口签名 `parseResults(List<PPOcrV6Result>) → R`。

- **`LabelMatcher`** — 公共骨架（`@UtilityClass`）：

  - `matchValue` 标签定位 + 位置匹配；

  - `findLabelBox` 支持 OCR 残缺标签模糊匹配；

  - `matchPattern` / `labelOrFallback` 内容正则兜底；

  - `matchSubstring` OCR 噪声场景的子串提取；

  - 几何工具 `minX/maxX/minY/maxY`。

- **10 个内置解析器**：`bankcard` / `business` / `driver` / `household` / `idcard` /
  `invoice`（含 `Electronic` / `Vat` 与通用 `Invoice` / `UpperMoneyConverter`）/ `pdd` /
  `taxi` / `train` / `vehicle`。每个继承 `BaseStructuredParser<R>`，实现 `parseResults(...)`。
  Spring Boot / Solon 通过 `PPOcrTemplate.xxx()` getter 获取已绑定 engine 的实例。
  调用方也可直接 `new XxxParser(engine).parse(bytes)` 一站式调用。

### mica-ppocr-spring-boot-starter

- **`PPOCRAutoConfiguration`** — auto-wires `PPOcrV6Engine` bean when `mica.ai.ppocr.enabled=true`.

- **`PPOCRProperties`** — `@ConfigurationProperties("mica.ai.ppocr")` binding.

- **`StructuredParserAutoConfiguration`** — 注册 6 个内置解析器与 `PPOcrTemplate`：

  - 每个解析器通过 `new XxxParser(PPOcrV6Engine)` 绑定 engine；

  - `PPOcrTemplate` 通过 `vehicleLicense()` / `idCard()` 等 getter 获取已绑定 engine 的解析器实例。

- **`OpenCVNativeLoader`** — `@AutoConfigureBefore` the main config, eagerly loads OpenCV native libs.

### Porting conventions

Python↔Java mapping: `numpy`→`utils.NdArrayUtils`, `pyclipper`→`utils.Offset` (JTS),
`cv2.minAreaRect`→`Imgproc.minAreaRect`, `np.rot90`→`Core.ROTATE_90_COUNTERCLOCKWISE`,
`@dataclass(frozen=True)`→Lombok `@Value` 类 + `@Accessors(fluent = true)`（保持 `record` 风格的 `xxx()` 访问）。

### Known divergences from Python

- `pyclipper` uses scaled-integer math; JTS `BufferOp` uses doubles → unclip differs by <1px.

- No CoreML provider in ONNX Runtime Java API.

- For CUDA: swap `onnxruntime`→`onnxruntime_gpu` in pom.xml and set `preferAccelerator(true)`.

### 运行时依赖基线（已校验 Java 8 字节码）

| 依赖                       | 版本                                                        | 最低 Java |
| ------------------------ | --------------------------------------------------------- | ------- |
| onnxruntime              | 1.18.0                                                    | Java 8  |
| opencv (openpnp)         | 4.9.0-0                                                   | Java 8  |
| jts-core                 | 1.20.0                                                    | Java 8  |
| slf4j-api                | 2.0.18                                                    | Java 8  |
| slf4j-simple             | 2.0.18（**test scope only** —— 替代原 logback-classic 1.3.15） | Java 8  |
| lombok                   | 1.18.46                                                   | Java 8  |
| spring-boot-dependencies | 2.7.18（替代原 3.5.16，3.x 是 Java 17）                          | Java 8  |
| mica-auto                | 2.3.5（替代原 4.0.1，3.x/4.x 是 Java 17）                        | Java 8  |
| solon                    | 4.0.6（**保留** — 实测所有 class 文件 max major = 52）              | Java 8  |

