# AGENTS.md

This file provides guidance to AI coding agents when working with code in this repository.

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
│       ├── preprocessor/{DetectionPreprocessor, RecognitionPreprocessor, DocOrientationPreprocessor}.java
│       ├── postprocessor/{DbDetParams, DbPostProcessor, CtcLabelDecoder, DocOrientationPostprocessor}.java
│       ├── pdf/                          ← PDF 双通道（与 engine 同模块）
│       │   ├── PdfOcrConfig.java         ← renderDpi / minTextChars / minReadableRatio / forceOcr
│       │   ├── PdfTextExtractor.java      ← 文本层坐标抽取（行聚类 + 大间距拆分）
│       │   ├── PdfTextQuality.java       ← 文本层质量评分（字符数 + 可读占比）
│       │   └── PdfPageResult.java        ← per-page 结果（pageIndex + viaOcr）
│       └── utils/{BoxUtil, BufferedImageUtils, CollUtil, CropUtil, ModelResourceLoader,
│                   NdArrayUtils, Offset, OrtProviders, PdfMagicDetector}.java
├── mica-ppocr-structured/      ← 结构化解析模块：把 OCR 散落文字框组织成业务字段
│   └── src/main/java/net/dreamlu/mica/ai/ppocr/structured/parser/
│       ├── core/                          ← SPI + 公共骨架
│       │   ├── BaseStructuredParser.java   ← SPI 抽象基类（parseResults + 10 个 parse 重载）
│       │   ├── BaseStructuredResult.java   ← 结果基类（rawResults + fieldBoxes）
│       │   └── LabelMatcher.java           ← 标签定位 + 位置匹配 + 正则兜底公共骨架
│       ├── vehicle/     ← 行驶证          ├── idcard/      ← 身份证
│       ├── bankcard/    ← 银行卡          ├── driver/      ← 机动车驾驶证
│       ├── business/    ← 营业执照        ├── invoice/     ← 发票（Invoice 分发 + Electronic / Vat）
│       ├── train/       ← 火车票          ├── taxi/        ← 出租车票
│       └── household/   ← 户口本          └── pdd/         ← 拼多多福袋
├── mica-ppocr-solon-plugin/    ← Solon 自动配置
│   └── src/main/java/net/dreamlu/mica/ai/ppocr/solon/
│       ├── integration/PpocrPlugin.java   ← 插件入口（显式 beanMake 各自动配置类）
│       ├── PPOCRAutoConfiguration.java
│       ├── PPOCRProperties.java
│       ├── PPOCRPropertiesCustomizer.java
│       ├── PdfAutoConfiguration.java
│       ├── StructuredParserAutoConfiguration.java
│       ├── PPOcrTemplate.java
│       └── OpenCVNativeLoader.java
└── mica-ppocr-spring-boot-starter/  ← Spring Boot 自动配置
    └── src/main/java/net/dreamlu/mica/ai/ppocr/autoconfigure/
        ├── PPOCRAutoConfiguration.java
        ├── PPOCRProperties.java
        ├── PPOCRPropertiesCustomizer.java
        ├── PdfAutoConfiguration.java      ← 把 mica.ai.ppocr.pdf.* 绑定为 PdfOcrConfig
        ├── StructuredParserAutoConfiguration.java  ← 注册 10 个内置解析器 + PPOcrTemplate
        ├── PPOcrTemplate.java             ← 解析器模板（10 个 getter + run 重载）
        └── OpenCVNativeLoader.java
```

## Commands

```bash
mvn -DskipTests package      # 全量构建
mvn package                  # 构建 + 运行测试
mvn test                     # 运行所有测试
mvn test -Dtest=CtcLabelDecoderTest   # 运行单个测试
mvn package -Dmaven.compiler.release=8 # 按 Java 8 ct.sym 校验标准库调用（CI 用的就是这条）
```

> 最后一条很关键：默认构建走 `-source/-target 1.8`，javac 不会按 Java 8 的标准库签名校验，
> **Java 9+ API 残留能编译通过但运行时 `NoSuchMethodError`**。加了 `-Dmaven.compiler.release=8`
> 才会在编译期拦下（CI 的 `test-and-build.yml` 就是这么做的，JDK 25 下必须传 `8` 而不是 `1.8`）。

Native libs (OpenCV, ONNX Runtime) are pulled by Maven for Windows/Linux/macOS — no manual install.

## Model setup

模型**已随仓库入库**（`models/ppocr-v6/`），clone 后无需额外下载即可运行：

```
models/ppocr-v6/
├── tiny/        # 轻量，速度快，精度一般 (det 1.7 MiB + rec 4.3 MiB)
│   ├── det.onnx
│   ├── rec.onnx
│   └── dict.txt             # ~2855 字符
├── small/       # 平衡档 (det 9.4 MiB + rec 20.2 MiB)
│   ├── det.onnx
│   ├── rec.onnx
│   └── dict.txt             # ~2855 字符
├── medium/      # 高精度档 (det.onnx.zip 32 MiB + rec.onnx.zip 65 MiB)
│   ├── det.onnx.zip        # 解压后 ~59 MiB
│   ├── rec.onnx.zip        # 解压后 ~73 MiB
│   └── dict.txt             # ~7180 字符
└── doc_ori/     # 可选：文档方向分类模型 (6.5 MiB)
    └── doc_ori.onnx
```

> `medium` 档以 `.onnx.zip` 形式入库（`.gitignore` 里 `*.zip` 被 `!models/ppocr-v6/medium/*.zip` 反选），
> **需解压为 `det.onnx` / `rec.onnx` 后使用**；`tiny` / `small` / `doc_ori` 已是可直接使用的 `.onnx`。

CLI 默认使用 `--tier tiny`；可用 `--tier small|medium` 切换，或用 `--det-model`/`--rec-model`/`--dict` 显式指定覆盖。

模型来源：`E:\codes\ai\mica-ai\model-tools\ppocr\model\out-by-spec`

> ⚠️ 模型体积较大（`models/` 合计约 140 MiB，`.git` 约 324 MiB）。若需瘦身仓库，
> 应把 `models/` 移出 git 跟踪并改为构建期下载 / CI 制品分发，而不是让每个 clone 都拉全量模型。

**dict.txt 格式约定（勿破坏）**：

- 词典中间有一行 **U+3000 全角空格**（tiny 第 777 行、small/medium 第 620 行），是 CJK 空格 token。
- 词典**最后一行是 ASCII 空格**（PaddleOCR `use_space_char` 约定：模型最后一个输出类 = 追加的空格，
  `onnx vocab = dict 行数 + blank + space`，如 tiny=7180、small/medium=18714）。
- 这两行都是"纯空白行"，任何 `strip`/`rstrip` 处理词典都会把它们抹掉，导致识别结果丢空格、英文单词粘连。
  `CtcLabelDecoder` 已按 Python 参考实现对齐（仅剥 `\n\r`，有回归测试锁定）；重新从 `inference.yml`
  导出词典时必须保留这两个 token（`model-tools/ppocr/convert.py` 已处理）。

## Test data privacy (测试数据隐私处理 — 强制要求)

`mica-ppocr-structured` 涉及 **身份证 / 银行卡 / 驾驶证 / 行驶证 / 营业执照 / 户口本 / 发票** 等高敏单据，
任何 `src/test/**` 测试数据、示例图、golden JSON、用例 fixture **必须在入库前做隐私脱敏处理**，否则禁止提交。
Agent 在生成、补全或 review 测试用例时，必须主动按以下规则处理：

> ⚠️ **当前仓库待整改（2026-10-25 审计发现，尚未修复）**：
> `test_images/**`（53 个文件）与 `mica-ppocr-structured/src/test/resources/ocr-json/**`（21 个文件）
> 仍是**未脱敏的真实证件**：含完整身份证号、银行卡号、车牌 / VIN、真实姓名与人脸照片，
> 且已推送到公开的 GitHub / Gitee / GitCode 仓库。整改前请勿再新增同类数据；
> 整改方案（是否重写 git 历史）待定，详见下方「误处理流程」。

### 1. 禁止入库的原始数据

- 真实姓名、身份证号、银行卡号、手机号、地址、车牌、发动机号、车架号（VIN）、统一社会信用代码、税号等。
- 真实证件、票据的原始扫描件 / 拍照（含背景水印、印章、二维码、人脸头像等可逆推身份的要素）。
- 任何带 EXIF / GPS / 设备指纹的原始图片或 PDF。
- 真实发票号、订单号、流水号等可关联到具体自然人或企业的编号。

### 2. 脱敏处理要求（缺一不可）

- **图像层脱敏**：人脸头像、照片、二维码区域用实心矩形 / 高斯模糊 / 马赛克覆盖；保留版面结构即可。
- **文本层脱敏**：
  - 身份证号 → 仅保留前 6 位 + `********`，或替换为 `110000YYYYMMDDXXXX`；
  - 姓名 → 替换为 `测试用户A` / `测试用户B` ……
  - 银行卡号 → 仅保留后 4 位，其余 `*`；
  - 手机号 → `138****0000`；
  - 车牌 / VIN / 发动机号 → 替换为 `京A·00001` / `LSVXXXXXXX0000000`；
  - 地址 → 替换为 `XX省XX市XX区XX路X号`；
  - 发票号 / 订单号 → 用 `INV-0001` / `ORD-0001` 这类占位。
- **元数据脱敏**：提交前用 `exiftool -all= file.jpg` 或等价命令清掉 EXIF / GPS / 创建时间 / 设备型号。
- **文件命名脱敏**：测试资源路径中不要带真实姓名 / 编号 / 时间戳，用语义化命名
  （`idcard-front-blurred.png`、`invoice-vat-sample.png`）。

### 3. 优先使用合成 / Mock 数据

- 优先用 **程序化生成** 的合成证件图片（OpenCV + PIL / Java `BufferedImage` 渲染模板 + 假字段），
  而不是真实证件扫描件。
- 优先用 **Mock 解析输入**（`List<PPOcrV6Result>` 直接构造 `text/box/score`），跳过真实图片，
  既避免隐私风险，又让单测稳定不依赖模型输出。
- Golden JSON 中的字段值统一用占位符（`"name": "测试用户A"`、`"idNo": "110000********"`）。

### 4. 提交前自检 checklist

- [ ] 所有 `src/test/resources/**/*.png|jpg|jpeg|pdf` 均已脱敏（人脸 / 二维码覆盖、字段替换）。
- [ ] 所有 `*.json` / `*.txt` golden 文件中无真实身份证号 / 银行卡号 / 手机号 / 车牌。
- [ ] `git diff --staged --name-only` 中没有可疑的 `real-` / `original-` / `用户姓名-` 命名的资源。
- [ ] 大文件（>1MB）走 Git LFS 或外部存储，不直接进仓。
- [ ] CI / pre-commit 钩子若配置了 `detect-secrets` / `gitleaks`，本地必须先跑通。

### 5. 误处理流程

若发现已经入库的真实敏感数据：

1. **不要**直接 `git rm` + `commit`——那只是删引用，文件仍在历史里。
2. 用 `git filter-repo` / `BFG Repo-Cleaner` 重写历史，必要时强制推送到所有 fork。
3. 同步检查 wiki、issue、release notes、CI artifact、外部文档站是否泄漏。
4. 在 PR / 内部群同步声明影响范围，必要时通报合规。

## Architecture

The pipeline flows: **detect → sort boxes → crop → recognize**.

### mica-ppocr-core

- **`engine/PPOcrV6Engine`** — the orchestrator and only public entry point. Owns the two
  `OrtSession`s (det + rec)，is `Closeable` (use try-with-resources)，exposes `run(...)` / `detect(...)` /
  `recognizeMat(...)`（Mat 版见下）。Accepts a `PPOcrV6Config` (Lombok `@Builder`).
  `run(byte[])` / `run(Path)` 自动嗅探 `%PDF-` 魔数：命中时按 PDF 双通道处理并平铺所有页结果；
  非 PDF 走图片通道。`run(byte[])` / `run(InputStream)` 均不声明 `throws IOException`
  ——PDF 解析或流读取失败时引擎内部包为 `UncheckedIOException` 抛出，调用方免 try-catch。
  （`parse(...)` 一站式结构化方法在 `mica-ppocr-structured` 的 `BaseStructuredParser` 上，引擎本身没有 `parse`。）

- **`config/PPOcrV6Config`** — `@Getter @Builder` config for all tunables.

- **`engine/PPOcrV6Result`** — Lombok 类（`@Accessors(fluent = true)`，text/score/box/rotatedDegrees），box 为 `int[][]` 四顶点（左上、右上、右下、左下）。

- **`preprocessor/DetectionPreprocessor`** — resize to limit-side constraints, normalize, HWC→NCHW.

- **`postprocessor/DbPostProcessor`** — DB binary-map → contours → boxes。
  配合 [`DbDetParams`](mica-ppocr-core/src/main/java/net/dreamlu/mica/ai/ppocr/postprocessor/DbDetParams.java) 不可变值对象，
  通过 `engine.detectMat(Mat, DbDetParams)` 可按调用临时覆盖阈值（thresh/boxThresh/unclipRatio），
  引擎不修改任何共享状态，并发安全（[issue #24](https://github.com/lets-mica/mica-ppocr/issues/24)）。

- **`preprocessor/RecognitionPreprocessor`** — batches crops, resizes, pads to common width.

- **`postprocessor/CtcLabelDecoder`** — loads char dict, CTC greedy decode → text + score.

- **`utils/`** — the numpy/cv2 equivalents:

  - `NdArrayUtils` — argmax/max/stack/pad/clip over float arrays.

  - `BoxUtil` — `sortQuadBoxes` (reading order), minAreaRect/boxPoints.

  - `Offset` — pyclipper `PyclipperOffset` equivalent via JTS `BufferOp` + `JOIN_ROUND`.

  - `CropUtil` — perspective-warp crop; returns `null` for invalid crops;
    `run()` filters these `null`s out — preserve this null-skip contract.

  - `OrtProviders` — picks the ORT execution provider；`PPOcrV6Config.preferAccelerator`
    默认 `false`（即强制 CPU，保证跨平台 bit-exact），置 `true` 时按
    CoreML (macOS) > CUDA > CPU 顺序探测。

### mica-ppocr-structured

- **`BaseStructuredParser<R>`** — SPI **抽象类**（v1.1.3 起由接口改为抽象类），规范所有解析器的入口签名
  `parseResults(List<PPOcrV6Result>) → R`。基类同时提供 10 个 `final` 的 `parse(...)` 一站式重载
  （`String` / `File` / `Path` / `byte[]` / `InputStream` × 带/不带 `DbDetParams`），内部完成 OCR 推理后再调 `parseResults`。

- **`BaseStructuredResult`** — 结果基类，统一持有 `rawResults`（完整 OCR 原始框）与 `fieldBoxes`（字段 → 坐标列表）。
  ⚠️ `fieldBoxes` 目前**并非所有解析器都回填**：`invoice`（Vat + Electronic）/ `business` / `vehicle` /
  `household` / `pdd` / `train` / `taxi` 会回填；`idcard` / `driver` / `bankcard` 三个解析器
  仍走丢弃 box 的文本版 `LabelMatcher.matchValue(...)`，`fieldBoxes` 为空 map。

- **`BaseStructuredParser.detParams()`** — 解析器声明默认 DB 检测参数的钩子（默认 `null` = 引擎默认）。
  `parse(...)` 一站式方法会带上它；显式传入的 `DbDetParams` 优先，`null` 时回落到本钩子。
  子类可按自己的**版面类型**声明专属检测参数，不影响同引擎上的其它解析器。

- **`LabelMatcher`** — 公共骨架（`@UtilityClass`）：

  - `matchValue` / `matchValueWithBox` 标签定位 + 位置匹配（`WithBox` 版返回 `LabeledMatch(value, box)`，
    供解析器回填 `fieldBoxes`）；

  - `findLabelBox` 支持 OCR 残缺标签模糊匹配；

  - `matchPattern` / `labelOrFallback` 内容正则兜底；

  - `matchSubstring` OCR 噪声场景的子串提取；

  - 几何工具 `minX/maxX/minY/maxY`。

- **10 个内置解析器**（容器注册的 bean）：`bankcard` / `business` / `driver` / `household` / `idcard` /
  `invoice` / `pdd` / `taxi` / `train` / `vehicle`。
  其中 `InvoiceParser` 是**发票分发器**（按版式分派到 `ElectronicInvoiceParser` / `VatInvoiceParser`，
  二者**不继承** `BaseStructuredParser`，只提供 `parseResults(...)` 静态入口）；
  其余 9 个直接继承 `BaseStructuredParser<R>`。
  Spring Boot / Solon 通过 `PPOcrTemplate.xxx()` getter（10 个）获取已绑定 engine 的解析器实例。
  调用方也可直接 `new XxxParser(engine).parse(bytes)` 一站式调用。

### mica-ppocr-spring-boot-starter

- **`PPOCRAutoConfiguration`** — auto-wires `PPOcrV6Engine` bean when `mica.ai.ppocr.enabled=true`.

- **`PPOCRProperties`** — `@ConfigurationProperties("mica.ai.ppocr")` binding，
  实现 `PPOcrV6Config.Source` 接口后由 `PPOcrV6Config.from(properties)` 一行转换。

- **`PPOCRPropertiesCustomizer`** — 配置旁路覆盖钩子（按环境切 `tiny`/`small`/`medium` 等）。

- **`StructuredParserAutoConfiguration`** — 注册 10 个内置解析器与 `PPOcrTemplate`：

  - 每个解析器通过 `new XxxParser(PPOcrV6Engine)` 绑定 engine；

  - `PPOcrTemplate` 通过 `vehicleLicense()` / `idCard()` 等 getter 获取已绑定 engine 的解析器实例。

- **`PdfAutoConfiguration`** — 把 `mica.ai.ppocr.pdf.*` 绑定为 `PdfOcrConfig` bean。
  ⚠️ 该 bean **就是引擎使用的配置**（不是只读副本）：`PdfAutoConfiguration.pdfOcrConfig` →
  `PPOCRAutoConfiguration.ppocrV6Config` 注入 → `PPOcrV6Config.pdfOcrConfig` →
  `PPOcrV6Engine`，由引擎的 PDF 入口（`run(byte[])` / `run(Path)` 嗅探到 `%PDF-` 时）实际使用。
  业务方自定义同名 bean（`@ConditionalOnMissingBean` 让自动配置退让）会**直接改变引擎行为**。
  `pdf.enabled=false` 时 bean 仍存在，但取 `PdfOcrConfig.defaults()`（忽略 yml 的 pdf 参数）。

- **`OpenCVNativeLoader`** — `@AutoConfigureBefore` the main config, eagerly loads OpenCV native libs.

### mica-ppocr-solon-plugin

与 Spring Boot Starter **能力对齐、类名一一对应**（`net.dreamlu.mica.ai.ppocr.solon.*`），
差异只在框架注解：Solon 用 `@BindProps` + `@Condition`，Spring 用 `@ConfigurationProperties` +
`@ConditionalOnProperty` / `@ConditionalOnMissingBean`。

- **`PpocrPlugin`**（`integration/` 包）— 插件入口，**显式 `beanMake` 各自动配置类**：
  `PPOCRProperties` / `OpenCVNativeLoader` / `PPOCRAutoConfiguration` /
  `PdfAutoConfiguration` / `StructuredParserAutoConfiguration`。

### Porting conventions

Python↔Java mapping: `numpy`→`utils.NdArrayUtils`, `pyclipper`→`utils.Offset` (JTS),
`cv2.minAreaRect`→`Imgproc.minAreaRect`, `np.rot90`→`Core.ROTATE_90_COUNTERCLOCKWISE`,
`@dataclass(frozen=True)`→Lombok `@Value` 类 + `@Accessors(fluent = true)`（保持 `record` 风格的 `xxx()` 访问）。

### Known divergences from Python

- `pyclipper` uses scaled-integer math; JTS `BufferOp` uses doubles → unclip differs by <1px.

- No CoreML provider in ONNX Runtime Java API.

- For CUDA: swap `onnxruntime`→`onnxruntime_gpu` in pom.xml and set `preferAccelerator(true)`.

### 检测参数按版面分场景（DB boxScore 的语义陷阱）

DB 后处理的 `boxScore` = 框（minAreaRect）内**概率图的平均值**，本质是「框内文字像素占比」。
对「**大字号稀疏数字 + 满屏背景水印**」版面（拼多多福袋 / 邀请码海报），
DB 会把多个大数字与水印连成一个整体轮廓，框内真正笔画只占 ~42%，
`score` 因此低于默认 `detBoxThresh=0.6` 而被**直接丢弃**——
现象是「rec 单独跑同一张图完全正确，但 det 一个框都没给」，极易误判为识别模型问题。

**排查手法**：dump det 的概率图存 PNG（目标区域纯白高响应 = 模型看见了，是后处理毙掉的），
再复刻 `DbPostProcessor.extractBoxes` 逐条打印 `origRect / minSideLen / score / PASS|DROP(原因)`。

**正确解法**：用 **per-call** 的 `DbDetParams`（不改引擎共享状态）：

- 海报 / 分享图：`DbDetParams.of(0.3f, 0.4f, 1.0f)`
  （放宽 `boxThresh` 让稀疏大字符框过关 + 收紧 `unclipRatio` 到 1.0，避免外扩把上下水印卷进来丢字）。
  已落地在 `PddLuckyBagParser.DET_PARAMS`，由 `BaseStructuredParser.detParams()` 钩子注入。

- 证件 / 票据：沿用引擎默认（`min/64` + `0.6/1.5`），**不要**改成 `detLimitType=max` + 大
  `detLimitSideLen` —— 那会缩小整图，小字成片漏检（实测出租车票框数 55→26）。

**别做**：调大 `DbDetParams.minSize` 过滤碎片（会连小字标签一起丢，如行驶证「所有人」变 null）。

### 性能优化的红线：改内存 / 耗时，不改数值

预处理是 bit-exact 的高危区。三处预处理（det / rec / doc_ori）已做过**内存融合**：
归一化结果直接写入 CHW / NCHW 输出数组，不再 materialize 中间 HWC 数组，也不再生成
CV_32F 中间 Mat。实测收益：

| 场景 | 堆分配 | 耗时 |
| --- | --- | --- |
| det 预处理（3000×4000 图） | 275 MB → 137 MB（-50%） | 110 ms → 42 ms（2.6×） |
| rec 预处理（50 框 × 1200px 宽） | 79.1 MB → 39.8 MB（-50%） | 21.1 ms → 15.9 ms（-25%） |

**改预处理数值前必须知道的三件事**：

1. **uint8 → CV_32F 与 uint8 → 提升为 float 逐位相同**（0~255 在 float32 精确可表示）。
   所以「先转 float 再算」与「直接提升再算」结果一致 —— 这是省掉 CV_32F 中间 Mat 的依据。
2. **OpenCV Mat 视图走引用计数**：父 Mat `release()` 后子视图仍可读 ⇒ 释放顺序必须是
   「先子视图、后本体」。写反了不会立刻报错，只是 native 缓冲滞留到 GC。
3. **`Mat.get(row, 0, byte[])` 不是「读一行」，而是「从 (row, 0) 起连续读满 `data.length` 字节」**。
   OpenCV 有一道**前置校验**：数组长度必须是**通道数的整数倍**，否则抛
   `UnsupportedOperationException`（Provided data element number (N) should be multiple of
   the Mat channels count (C)）。校验通过后仍有三个坑：
   - **行缓冲比一行长**（通道数 &lt; 预期，如 1 通道按 3 通道定长）→ **跨行读**，把相邻行数据当本行像素；
   - **行缓冲比一行短**（通道数 &gt; 预期）→ 通道步长错位，首个像素对、后续全错；
   - **1 通道永远静默**：任意长度都是 1 的倍数，恒能绕过上面那道校验
     （灰度图 → 必然跨行读）。&gt; 3 通道则视缓冲长度能否被通道数整除 ——
     整除则静默错位，不整除才抛异常。
   ⇒ 行缓冲长度必须**严格等于** `cols() * channels()`，并在入口校验 `type()`。
   另：非连续 Mat（窄 ROI，行 stride &gt; 宽度×elemSize）读取会跨行，必须先 `clone()`；
   `Mat.get(row, col, double[])` **不支持 CV_8UC3**（type 16），CV_8UC3 用 `byte[]`；
   非 CV_8U 的 Mat 用 `byte[]` 读会抛 `UnsupportedOperationException`。

**回归护栏（不要删！）**：`DetectionPreprocessorTest` / `RecognitionPreprocessorTest` /
`DocOrientationPreprocessorTest` 里各有一个 `reference*` 方法，是**旧实现**的副本，
充当「数值真相源」，用 `assertArrayEquals` 与新实现逐位比对。
它们看起来像重复代码，但**不是死代码** —— 删掉参考实现等于删掉 bit-exact 护栏，
后续任何数值改动都会静默漂移。新增优化时按同样模式：旧算法留在测试里当参照。

### 运行时依赖基线（已校验 Java 8 字节码）

版本以根 `pom.xml` 的 `<properties>` 为准；下表「字节码」列为本轮用
`javap -v` 实测的 class 文件 major version（**52 = Java 8**）。

| 依赖                       | 版本                                                        | 字节码 | 最低 Java |
| ------------------------ | --------------------------------------------------------- | --- | ------- |
| onnxruntime              | 1.18.0                                                    | 52 | Java 8  |
| opencv (openpnp)         | 4.9.0-0                                                   | 52 | Java 8  |
| jts-core                 | 1.20.0                                                    | 52 | Java 8  |
| pdfbox                   | 3.0.8（PDF 双通道依赖，随 core 传递；下游不需要可 exclude）          | 52 | Java 8  |
| slf4j-api                | 2.0.20                                                    | 52 | Java 8  |
| slf4j-simple             | 2.0.20（**test scope only** —— 替代原 logback-classic 1.3.15） | 52 | Java 8  |
| lombok                   | 1.18.48                                                   | 52 | Java 8  |
| spring-boot-dependencies | 2.7.18（替代原 3.5.16，3.x 是 Java 17）                          | 52 | Java 8  |
| mica-auto                | 2.3.5（替代原 4.0.1，3.x/4.x 是 Java 17）                        | 52 | Java 8  |
| solon                    | 4.1.1（**保留** — 实测所有 class 文件 max major = 52）              | 52 | Java 8  |

> 升级任何依赖后，**必须重跑一次字节码校验**（`javap -v -cp <jar> <类>` 看 major version）。
> 版本号漂移会让本表「已校验 Java 8」的结论失效。