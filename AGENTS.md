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

- **NIO API**（Java 11+，看着像 JDK 8 但不是）：`FloatBuffer.allocateDirect` 是 **Java 11+**，
  JDK 8 只有 `ByteBuffer.allocateDirect` —— 要 direct FloatBuffer 就用
  `ByteBuffer.allocateDirect(n).order(ByteOrder.nativeOrder()).asFloatBuffer()`。
  同类易误用的还有 `FloatBuffer.mismatch`（8）/ `CharBuffer` 的部分方法（11+）。

- **单字符字面量**：单个字符用 char 字面量 `'-'`，不要写字符串字面量 `"-"`（分隔符拼接处尤其明显）。
  三个必须绕开的坑：
  - **左侧是数字时不能换** —— `int + '-' + int` 会从字符串拼接变成**数值加法**。
    例 `resizeShort + "/" + cropSize`（`resizeShort` 是 `int`）必须保持 `"/"`；
  - **删除类 replace 不能换** —— Java 没有空字符字面量，`replace(' ', '')` 是**语法错误**，
    `replace(" ", "")` 必须保持字符串版；`replace(c1, c2)` 只在两参数都是非空字符时才可用；
  - **左侧是泛型类型变量时不能换** —— `K + '='` 编译器无法判定是算术还是拼接，直接报
    "运算符 '+' 的类型不明确"；`K + "="` 才安全（String 操作数决定走 `String.valueOf`）；
  - **API 收 `String`/`CharSequence` 的不能换** —— `contains`/`split`/`matches`/`startsWith`/`endsWith`/
    `indexOf`/`replaceAll`/`Pattern.compile` 的参数，以及 `Set<String>` 的元素、`assertEquals("X", ...)`、
    `box("1", ...)` 这类按字面量比对的调用，一律保持字符串字面量。

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
│       │   ├── PdfTextExtractor.java      ← 文本层坐标抽取（行聚类 + 大间距拆分，point 坐标）
│       │   ├── PdfPageGeometry.java       ← point → 渲染图像素换算（旋转 + DPI 缩放，零 PDFBox 引用）
│       │   ├── PdfTextQuality.java       ← 文本层质量评分（字符数 + 可读占比）
│       │   ├── PdfPageResult.java        ← per-page 结果（pageIndex + viaOcr）
│       │   ├── PdfRuntime.java           ← 唯一直接触碰 PDFBox API 的类
│       │   ├── PdfDependency.java        ← PDFBox 可用性探测（零 PDFBox 引用）
│       │   └── PdfOcrBridge.java         ← 渲染通道 → 引擎 OCR 链路的回调
│       └── utils/{BoxUtil, BufferedImageUtils, CollUtil, CropUtil, ModelResourceLoader,
│                   NdArrayUtils, Offset, OrtProviders, PdfMagicDetector}.java
├── mica-ppocr-structured/      ← 结构化解析模块：把 OCR 散落文字框组织成业务字段
│   └── src/main/java/net/dreamlu/mica/ai/ppocr/structured/parser/
│       ├── core/                          ← SPI + 公共骨架
│       │   ├── BaseStructuredParser.java   ← SPI 抽象基类（parseResults + 10 个 parse 重载）
│       │   ├── BaseStructuredResult.java   ← 结果基类（rawResults + fieldBoxes + 字段置信度）
│       │   ├── LabelMatcher.java           ← 标签定位 + 位置匹配 + 正则兜底公共骨架
│       │   ├── Validations.java            ← 校验位算法（GB 11643 / GB 32100 / Luhn）+ 证件号回填
│       │   ├── FieldConfidence.java        ← 字段级置信度（值 / 分数 / 来源 / 违规）
│       │   ├── FieldSource.java            ← 字段取值来源枚举（LABEL/PATTERN/DERIVED/BACKFILL/MISSING）
│       │   ├── ParsedField.java            ← 字段解析结果（值 + 来源 + OCR 置信度）
│       │   ├── ValidationResult.java       ← 单条校验结论（PASS/FAIL/UNCHECKABLE）
│       │   ├── ValidationStatus.java       ← 校验三态枚举
│       │   ├── LabelMatchStatus.java       ← 标签命中三态（FOUND/LABEL_WITHOUT_VALUE/NOT_FOUND）
│       │   ├── LabelAliases.java           ← 标签别名表（规范标签 → OCR 认错的写法）
│       │   └── DateRepair.java             ← 日期修复（分隔符被认错时还原 yyyy-MM-dd）
│       ├── generic/                       ← 通用解析器（声明式 schema 入口，与存量 10 个强类型 parser 并行）
│       │   ├── OcrFieldSchema.java         ← 声明式 schema（字段名 / 标签 / 正则 / 规则 / 归一化）
│       │   ├── OcrStructuredParser.java    ← 通用解析器（extends BaseStructuredParser）
│       │   ├── OcrStructuredResult.java    ← 通用结果（extends BaseStructuredResult）
│       │   ├── FieldSpec.java              ← 单字段声明
│       │   ├── FieldRule.java              ← 字段校验规则（复用 Validations）
│       │   ├── ValueNormalizer.java        ← 值归一化（NONE / DIGITS / COMPACT / DATE）
│       │   └── ValueLocator.java           ← 值定位（值归属最近标签 + 垂直重叠率 + 间距上限）
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
│       └── PPOcrTemplate.java
└── mica-ppocr-spring-boot-starter/  ← Spring Boot 自动配置
    └── src/main/java/net/dreamlu/mica/ai/ppocr/autoconfigure/
        ├── PPOCRAutoConfiguration.java
        ├── PPOCRProperties.java
        ├── PPOCRPropertiesCustomizer.java
        ├── PdfAutoConfiguration.java      ← 把 mica.ai.ppocr.pdf.* 绑定为 PdfOcrConfig
        ├── StructuredParserAutoConfiguration.java  ← 注册 10 个内置解析器 + PPOcrTemplate
        └── PPOcrTemplate.java             ← 解析器模板（10 个 getter + generic(schema) + run 重载）
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
  `onnx vocab = dict 行数 + 1`（那个 +1 就是 blank），如 tiny=7180、small/medium=18714 ——
  实测 dict 行数为 tiny 7179 / small·medium 18713，`CtcLabelDecoder.parseLines` 先 `add("blank")` 再逐行加入）。
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

- **OpenCV 原生库加载（引擎独占，红线）** — `PPOcrV6Engine` 构造期调用
  `nu.pattern.OpenCV.loadLocally()`（`ensureOpenCvLoaded()`），Starter / Plugin **不再有**
  `OpenCVNativeLoader`：
  - **为什么**：openpnp 的 OpenCV 不会随 JVM 启动自动加载 native 库，缺一次 `loadLocally()`
    就会在首次 `Imgproc.xxx` / `Core.xxx` 调用时抛 `UnsatisfiedLinkError`。
    让 starter / plugin / 业务代码各自记住调一次，是「谁忘了谁炸」陷阱，
    且故障被推迟到**首个推理请求**才暴露。改由引擎构造期加载 ⇒ **构造成功 ⇒ 推理不会撞 native 缺失**。
  - **加载失败 fail fast**：构造期直接抛 `IllegalStateException`（保留原始 cause）。
    注意必须 `catch Throwable` —— `UnsatisfiedLinkError` 是 `Error` 不是 `Exception`。
  - **不要自己写 `if (!loaded)` 标志位**：openpnp 每次都把 native 二进制解压到临时目录后
    `System.load(path)`，而 `System.load` 按**路径**记账 ⇒ 解压到不同临时路径会重复加载同一份
    native，造成两份静态状态分裂。openpnp 内部是 holder-class 单例，天然幂等 + 线程安全，
    直接调 `OpenCV.loadLocally()` 即可。
  - **副作用**：`mica.ai.ppocr.enabled=false` 时引擎 bean 不存在 ⇒ **OpenCV 也不再加载**，
    业务方自行使用 `org.opencv.*` 需自己 `loadLocally()`。
  - **测试侧约定**：测试代码**不要**再无脑加 `OpenCV.loadLocally()` ——
    只有「**不经引擎**（直接或**间接**）触达 `org.opencv.*`」的测试才需要自己加载，
    且应**集中在一个 `@BeforeAll`** 里
    （如三个预处理单测用 `Mat` / `Imgproc`、
    demo 与 dump 工具用 `Imgcodecs.imread` / 画框）；
    **只走引擎**的测试一律不需要 —— 引擎构造时已加载。
    ⚠️ 判断依据是「用 OpenCV 的时刻**早于**引擎构造」，不是「测试里出现了 org.opencv」。
    ⚠️ **间接依赖同样要加载**：测试类没 import 任何 `org.opencv.*` ≠ 不依赖 native。
    例如 `BoxUtil.orderMinAreaBoxPoints(float[][])` 内部构造 `MatOfPoint2f` 并调
    `Imgproc.minAreaRect` —— 调用方测试**看不到** opencv，却会在
    `new MatOfPoint2f()` 处抛 `UnsatisfiedLinkError: org.opencv.core.Mat.n_Mat()`。
    ⇒ 判定方法：看被测方法**内部**是否触达 native，而不是看测试文件的 import。
    ✅ **已消除的间接依赖**：`Offset.area()` / `Offset.perimeter()` 原先内部构造
    `MatOfPoint2f` 调 `Imgproc.contourArea` / `arcLength`，导致 `OffsetTest` 必须自行
    `loadOpenCv()`；现已改为纯 Java（shoelace + `Math.hypot` 边长和，与 OpenCV 逐位等价），
    `OffsetTest` 不再需要加载 native。**新增纯几何方法时优先走这条路线** ——
    既省掉 JNI + native 分配（单次 ~550ns → ~12ns），又消除一类隐式 native 依赖。

- **引擎生命周期锁（并发红线，改引擎时必须保留）** — `PPOcrV6Engine` 用一把**公平模式
  `ReentrantReadWriteLock`** 保护 native 会话的生命周期：
  - 推理入口（`run(Path/byte[])` / `runMat` / `detectMat` / `recognizeMat`）持**读锁** → 多线程可并发推理，互不阻塞；
  - `close()` 持**写锁** → 「释放 native ONNX 会话」永远不会与在途推理重叠。
  - `closed` 字段是 `volatile`（`isClosed()` 不持锁读取，靠它保证跨线程可见性）。
  - `warmedUp` 字段同理（`isWarmedUp()`），只有预热**成功**才置 true。

  **为什么必须有这把锁**：ORT 的 `OrtSession` 释放后底层 native 句柄被 free。若在
  `detSession.run(...)` / `recSession.run(...)` 执行期间释放，在途 native 代码会访问已释放内存 ——
  表现为 **SIGSEGV（JVM 级崩溃，不可捕获）**，或 session 元数据被读成垃圾
  （实测报 `ORT_INVALID_ARGUMENT - Invalid input name: x`）。单纯的 `volatile boolean closed`
  只能保证「关闭后不再接受新调用」，**挡不住「关闭与在途调用重叠」**。

  ⇒ **新增推理入口时必须在第一行 `lifecycleReadLock.lock()`**，
  否则会重新打开这个崩溃窗口。回归测试见
  `mica-ppocr-core/src/test/java/net/dreamlu/mica/ai/ppocr/engine/PPOcrV6EngineLifecycleTest.java`
  （用包级 `activeReaderCount()` 精确观测在途状态，不靠 sleep 猜时机）。

  **嵌套取锁是安全的**（JDK 17 实测）：`run(Path/byte[])` 持读锁后内部再调 `runMat(...)` 会**再次**
  取读锁；即使此时有写锁排队，**重入获取读锁依然成功，不会自死锁**（公平 / 非公平模式均如此）。
  ⇒ 不要为了「避免嵌套」去重构调用链。

  **close() 的语义：有界等待 + 超时延迟释放**（2026-10-29 改造，取代原「无限阻塞等待」）：
  - `close()` 最多等 `closeWaitTimeoutMillis`（默认 **1000ms**，包级可见、测试可改）让在途推理自然结束；
  - **未超时** → 持写锁释放全部 native 会话（干净关闭，与旧版一致）；
  - **已超时** → 引擎立即「退役」（`closed=true`、新请求被拒），但**不释放** native 会话
    （在途推理还在用它，强行释放就是 use-after-free ⇒ SIGSEGV）；
    释放动作延后到在途推理结束后，由守护线程 `ppocr-close-deferred` 完成。
  - ⇒ 关停不会再被长耗时推理（多页 PDF OCR）拖住，同时**绝不会**出现 native 层崩溃。
  - 超时后**在途推理可能被中断**（下一个 `requireOpen()` 检查点抛 `IllegalStateException`）——
    关停场景下属预期行为。
  - `close()` 幂等由 `closeInitiated`（`AtomicBoolean` CAS）保证：只有第一次调用执行关闭流程。

  ⚠️ **A/B 实测（三条红线都有回归护栏）**：
  - 把等待改成无限（`tryLock(Long.MAX_VALUE, NANOSECONDS)`）→ 测试红：
    「close() 不应被在途推理拖住，实际耗时 1325 ms」；
  - 删掉 `scheduleDeferredSessionRelease()` → 测试红：「native 会话仍未释放（静默泄漏）」
    （由包级 `isDeferredReleaseCompleted()` 观测点锁住）；
  - 把超时路径改成「不等就直接释放 native 会话」→ **JVM 直接 SIGSEGV 崩溃**
    （`hs_err_pid` 里 `Problematic frame: C [onnxruntime.dll+0x60021f]`）——
    这就是「不能简单不等就释放」的实证依据。

- **`engine/PPOcrV6Engine` 的构造期预热（私有方法 `warmUp()`）** — 用一张**程序化合成的笔画图**
  （64×64，1 行 2 字位，纯 OpenCV 几何绘制，零字体依赖、不依赖任何真实素材）跑通 det + rec 全链路，
  触发 ONNX Runtime 的计算图优化、内存池初始化与 JIT 编译，消除「首次推理显著慢于后续」。
  - **必须用真实笔画而非纯白图**：纯白图 det 检不出框，rec 分支根本不会执行到，
    等于只预热了一半。测试 `PPOcrV6EngineWarmUpTest` 锁定了这一点。
  - **不要改用 AWT 文本渲染**：文本依赖系统字体，极简 / 无头容器里可能渲染成空白 ⇒ det 检出 0 框，
    静默退化成「只预热一半」。几何笔画零字体依赖，各环境表现一致。
  - **不要放大预热图**：预热成本随图变大而涨，消罚收益却有天花板。实测（tiny 档，福袋码图
    1200×1500）：320×320/6框 = 成本 188ms / 残留惩罚 17ms；120×120/2框 = 72ms / 44ms；
    **64×64/1框 = 60ms / 23ms** ⇒ 大图只多消 20ms 却多付 120ms。
  - **⚠️ 也不能缩小到 30×30**：det 预处理是 `limitType=min, limitSideLen=64`
    ⇒ **短边 < 64 才放大到 64，短边 ≥ 64 原样进模型**。30×30 靠「被放大」才被检出，
    det 参数一换成 PaddleOCR 常见的 `limitType=max, limitSideLen=960` 就 **0 框**
    ⇒ rec 分支不被预热 ⇒ **静默**退化成「只预热 det 一半」，无任何报错。
    实测两种 det 参数下的检出框数：30×30 = 1 / **0**，60×60 = 1 / 1，64×64 = 1 / 1，120×120 = 2 / 2。
    ⇒ **取 64×64 = det 的 limit_side_len**：预热图尺寸 = det 实际输入尺寸，零插值、笔画逐像素精确，
    且不依赖「小图会被放大」这条容易被改掉的实现细节。
    该约束由 `PPOcrV6EngineWarmUpTest#warmUpImageIsDetectableUnderAnyDetLimitConfig()` 锁住
    （已 A/B 验证：把图缩到 30×30 测试会红）。
  - **失败只记 WARN，不影响引擎可用性**（引擎可用性在构造期已校验）；
    预热是优化手段，不该让应用起不来。
  - **实测收益量级（别高估）**：独立 JVM 各 9 次采样，CPU EP、tiny 档、福袋码图。
    首调惩罚是**固定成本**（~110~140ms 的类加载 / JIT / arena 首触），**与图片大小、模型大小基本无关**
    ⇒ **负载越轻、占比越大**：福袋码图 1200×1500 占 ~20%，2988×2199 大图只占 8%，small 档占 7%。
    预热（64×64）把首调惩罚从 **113ms 压到 23ms（消掉 80%）**、首调延迟 688→615ms，
    自身花 60ms ⇒ **「构造→首个结果」基本打平**（中位 907→900ms）。
    ⇒ 预热的价值是**把 90ms 惩罚从用户请求挪到启动期**，不是净提速；
    且惩罚是**每 JVM 一次**（同 JVM 内重建引擎已无惩罚，实测首调 1677.9 vs 次调 1720.6）。
  - **构造期自动预热（私有方法，无开关）**：`PPOcrV6Engine` 构造器在初始化完成后
    **自动**调用私有的 `warmUp()`，把 ONNX Runtime 的计算图优化、内存池初始化与 JIT
    提前到初始化期，消除「容器刚起来时第一个请求显著慢于后续」。
    ⇒ **调用方不需要（也不应当）显式调用**：预热是引擎的<b>初始化语义</b>，不是可选开关。
    Starter / 插件的 `ppocrV6Engine(...)` Bean 因此只剩「new 一个引擎」这一件事。
    曾经的 `mica.ai.ppocr.warm-up-on-init` 配置项与公开的 `warmUp()` 方法均已**删除**。
    ⚠️ 预热成本 tiny ~60ms / small ~88ms（64×64 预热图下实测），却能消掉 ~90ms 的首调惩罚。
    ⚠️ 注意档位差异：rec 首次运行的成本主要来自「批里 crop 的数量与宽度」而非 session 初始化 ——
    旧 320×320 预热图（6 个 crop）下 small 档要 ~460ms，64×64（1 个小 crop）只要 ~88ms。
    ⇒ **不要再加回开关或公开方法**；预热失败只记 WARN，不会让引擎构造失败。
  - **⚠️ `isWarmedUp()` 是包级可见的测试观测点，不是公开 API**：
    预热失败只记 WARN、引擎照常可用 ⇒ 该状态几乎恒为 `true`，公开出去信息量低、
    且容易被误读成「引擎未就绪」；但**必须保留** —— 它是唯一能锁住
    「构造器确实调用了 `warmUp()`」的断言点。删掉后，构造器里那行预热调用被误删时，
    **全部测试仍然照常通过**（静默回归）。
    Starter / 插件侧的容器级断言因此改为「**预热失败不得让容器启动失败**」
    （用 `doc_ori` 模型冒充 rec 模型制造失败，零新增素材）。

- **`config/PPOcrV6Config`** — `@Getter @Builder` config for all tunables.

- **`engine/PPOcrV6Result`** — Lombok 类（`@Accessors(fluent = true)`，text/score/box/rotatedDegrees），box 为 `int[][]` 四顶点（左上、右上、右下、左下）。

- **`preprocessor/DetectionPreprocessor`** — resize to limit-side constraints, normalize, HWC→NCHW.

  - ⚠️ **三个预处理器的「逐行读 Mat」不要改成「整块读」**（已实测后决定维持现状）：
    `Mat.get(row, 0, byte[])` 每次调用实际是 **2 次 JNI**（`type()` + `nGetB`），
    逐行读 rec 预处理一次 OCR 要 1728 次调用 = 3456 次边界穿越；
    改成 `Mat.get(0, 0, byte[])` 整块读确实快 **3.4×**（5 411 → 1 602 ns/图），
    但**端到端只省 0.008%**（一次 OCR 1 671 ms 里省 137 µs），
    代价是 Java 侧瞬时峰值内存涨 16~450 KB（整块 byte[] vs 复用的行缓冲）。
    ⇒ 收益在噪声级别，不值当。**别再重复排查这一项。**
    附：整块读要求 `byte[]` 长度**精确等于** `h*w*channels` —— OpenCV 只校验「长度是通道数倍数」，
    传大了会越界读（就是 `RecognitionPreprocessor` javadoc 里警告的「跨行读」陷阱）。

  - ✅ **归一化改用 256 项查表**（2026-11-12）：uint8 像素值只有 256 种，
    而归一化公式的入参是离散的 ⇒ 把表达式**按字节值预先求值**成查表，
    内层循环从「3 次浮点乘 + 3 次浮点减 + 3 次浮点除」降为「3 次数组取值」。
    det 预处理 20.5 → 19.4 ms。
    ⚠️ **查表项必须用与原表达式完全相同的运算顺序求值**
    （det：`(v * scale - mean) / std`；rec：`v / 127.5f - 1.0f`），
    **换成乘以倒数会让末位漂移**（实测 `0.3651854` → `0.36518544`），
    det / rec 输入不再与 Python 参考实现 bit-exact ⇒ 由三个预处理器的 bit-exact 回归测试拦下。
    ⚠️ 这是**算术**层改动，与上面那条「逐行读 vs 整块读」的**读取策略**红线无关，两者互不冲突。

- **`postprocessor/DbPostProcessor`** — DB binary-map → contours → boxes。
  配合 [`DbDetParams`](mica-ppocr-core/src/main/java/net/dreamlu/mica/ai/ppocr/postprocessor/DbDetParams.java) 不可变值对象，
  通过 `engine.detectMat(Mat, DbDetParams)` 可按调用临时覆盖阈值（thresh/boxThresh/unclipRatio），
  引擎不修改任何共享状态，并发安全（[issue #24](https://github.com/lets-mica/mica-ppocr/issues/24)）。

  - ✅ **二值化改为一次 Java 遍历直出 0/255 掩码**（2026-11-12）：
    原先走 OpenCV 的 `threshold` → `Core.multiply(255)` → `convertTo(CV_8U)` 三趟链，
    每次 OCR 要在 native 侧过两遍 CV_32F 中间结果（约 21 MB）；
    三趟链的语义等价于一句话 `u8[i] = (prob[i] > thresh) ? 255 : 0`，
    直出后 DB 后处理 10.2 → 6.8 ms（-33%），native 分配/释放量降到约 2.7 MB。
    ⚠️ 阈值比较是**严格大于**（与 `THRESH_BINARY` 一致）：`prob[i] == thresh` 取 0（背景）。
    ⚠️ 掩码**逐行写入**而非攒成整块 `byte[]` 再一次 put：两者耗时相当（6.7 vs 6.8 ms），
    但整块写法每次多分配一份 h×w 的 Java 堆数组（1920×1408 即 2.7 MB），逐行写法只用一行缓冲。
    回归测试见 `DbPostProcessorTest`（掩码与 OpenCV 三趟链逐位对比 + 框坐标 golden）。

  - ⚠️ **不要再提「crop arena」与「可复用 scratch」**（2026-11-12 实测否决）：
    lw.PPOCR.Java 的 `PerspectiveCrop.Workspace.cropAll`（多框裁剪共享 byte[] arena）
    与 `DbPostprocess.Decoder`（常驻 scratch 实例）这两个借鉴点，实测收益为
    **36 次 `new Mat` + release 仅 0.022 ms**（裁剪阶段总耗时约 30 ms）、
    复用掩码 Mat 仅额外省 0.55 ms —— **都在噪声级别**。
    裁剪阶段的成本几乎全在 `warpPerspective` 的 native 计算本身，不在分配。
    ⇒ 裁剪侧的「省分配」思路已证伪，别再重复排查这一项。

    ✅ **全量 Mat 分配点已逐个实测（2026-11-12），全部是噪声**，
    下表是完整清单，**别再在这些点上重复排查**：

    | 位置 | 每次 OCR 的分配量 | 实测耗时 |
    |---|---|---|
    | `boxScore` 的 `Mat.zeros` + `MatOfPoint` | 每候选框 | 20 框合计 **0.28 ms** |
    | `BoxUtil.orderMinAreaBoxPoints` 的 `MatOfPoint2f` + `boxMat` | 2×候选框 + 1×裁剪框 | 20 次合计 **0.077 ms** |
    | `CropUtil.rotateCropImage` 的 src/dst/transform/cropped | 4~5 × 裁剪框 | 36 次合计 **0.022 ms** |
    | rec 预处理的每 crop resize Mat | 每裁剪框 | 20 crop 合计 **0.007 ms** |
    | doc_ori 的 `resizeShort` 输出 + 中心裁剪 clone | 每次 1 块（约 0.4 / 0.15 MB） | 低于缺页阈值，**不划算** |
    | **det resize 输出 Mat（8.1 MB）** | 每次 det 1 块 | 复用**无收益**（见下） |
    | **det 概率图 Mat（10.8 MB）** | 每次 det 1 块 | 复用**无收益**（见下） |
    | **DB 二值化掩码 Mat（2.7 MB）** | 每次 det 1 块 | 复用**无收益**（见下） |
    | **PDF 渲染页 BGR Mat（11.6 MB）** | 每页 1 块 | 复用**无收益**（见下） |
    | **doc_ori 旋转整图（10.9 MB）** | 仅在判定非 0° 时 | 复用**无收益**（见下） |

    ⚠️ **为什么小 Mat 复用没收益**：块小到 CRT 堆直接复用同一段地址，页始终是热的，
    没有缺页。**大块（≳1 MB）虽然可能拿到全新未映射的页，但真实链路里这一点也拿不到**
    —— 见下面「Mat 复用池已实测否决」。
    ⇒ 扩池前先看**单块字节数**，别按「调用次数多」排序。

- ❌ **OpenCV Mat 复用池（`MatPool`）已实测否决并撤销**（2026-11-12）：
  曾实现过一套「按尺寸 + 类型匹配」的 Mat 复用池，覆盖 det resize 输出、det 概率图、
  DB 二值化掩码、PDF 渲染页 BGR、doc_ori 旋转整图共 5 处，
  **最终实测收益为 0，已连同实现、测试、文档一并撤掉**。别再重做这个方向。

  ⚠️ **这个方向有一个非常逼真的假象，务必记住**：
  孤立微基准里，`new Mat` + `put` + `release` 写 6.3 MB 要 **1.007 ms**，
  而复用同一块缓冲写同样的数据只要 **0.249 ms** —— 差 **0.75 ms**，
  看起来就是「首次写入新页的缺页开销」，而且配重实验（插入 96 MB 配重逼分配器给全新页）
  证实差距确实随缺页增加（0.724 → 0.925 ms）。
  **但这个差距在真实推理链路里完全不存在。**

  **决定性实验**（把真实 ORT det 推理插进同一个微基准，其余完全相同）：

  | 场景 | `new` + `put` + `release` | 池复用 | 差距 |
  |---|---|---|---|
  | 不含 ORT 推理 | 2.185 ms | 0.999 ms | **1.187 ms** |
  | **含 ORT det 推理** | 220.488 ms | 220.487 ms | **0.001 ms** |

  ⇒ **一旦链路里有 ORT 推理，CRT 堆上的内存流动就让那块缓冲始终保持 committed，
  「免缺页」的好处一分钱也拿不到。** 微基准之所以骗人，是因为它测量的是
  「堆上只有这一块大 buffer 在流动」的孤立情形，与真实链路（ORT arena 反复分配/释放）
  的堆状态完全不同。

  **真实引擎的最终 A/B**（det 阶段，同引擎逐块交错、开/关各 20 次 × 20 块）：
  开复用中位 **360.728 ms/次**、关复用中位 **361.668 ms/次**，
  配对差中位 **-0.606 ms/次**，**开复用更快的块 9/20** —— 等于抛硬币，
  效应量落在 ±3 ms 的块间噪声里，测不出来。

  ⚠️ **代价却是实打实的**：池上限 64 MiB，跑一遍 44 张异构样图后常驻 **60.0 MB**
  native 内存（命中 65.9%，其余是尺寸不匹配的 miss）；而收益为 0。
  ⇒ 纯亏：**用 60 MB 常驻 native 内存换 0 收益**，还多了 5 处「归还池 vs release」
  的正确性负担（漏归还 = 泄漏，误 release = use-after-free）。已撤销。

  ⚠️ **方法论教训（比结论本身更重要）**：这次的坑是**「微基准的效应 ≈ 真实链路的效应」
  这个假设根本不成立**。做 OpenCV 层的性能优化时：
  - 微基准只用来**排除**方向（分配本身便宜、小块无收益），**不能用来证明收益**；
  - 收益必须落在**真实调用链**上测，且中途不能有「会冲掉缓存 / 改变堆状态」的大动作；
  - A/B 的开/关两侧必须**共享同一份 JIT、同一份缓存、同一份堆状态**，
    否则测到的差异可能全部来自被控变量之外的东西。

- **`preprocessor/RecognitionPreprocessor`** — batches crops, resizes, pads to common width.

  - ✅ 归一化同样改用 256 项查表（`v / 127.5f - 1.0f`），算术层改动，
    逐位等价约束与 `DetectionPreprocessor` 的 LUT 同理（见上）。

- **`postprocessor/CtcLabelDecoder`** — loads char dict, CTC greedy decode → text + score.

- **`utils/`** — the numpy/cv2 equivalents:

  - `NdArrayUtils` — argmax/max/stack/pad/clip over float arrays.

  - `BoxUtil` — `sortQuadBoxes` (reading order), minAreaRect/boxPoints.

  - `Offset` — pyclipper `PyclipperOffset` equivalent via a pure-Java rounded offset
    (每边外法向平移 + 顶点圆弧，每 90° 8 段，段数**四舍五入**).

  - `CropUtil` — perspective-warp crop; returns `null` for invalid crops;
    `run()` filters these `null`s out — preserve this null-skip contract.

  - `OrtProviders` — picks the ORT execution provider；`PPOcrV6Config.preferAccelerator`
    默认 `false`（即强制 CPU，保证跨平台 bit-exact），置 `true` 时按
    CoreML (macOS) > CUDA > CPU 顺序探测。

### PDF 双通道：依赖可选 + 类加载隔离（改引擎时必须保留）

**PDFBox 是可选依赖**（`mica-ppocr-core/pom.xml` 里 `<optional>true</optional>`）——
只做图片 OCR 的项目不该被动背上 pdfbox + fontbox + commons-logging。
`mica-ppocr-structured` / starter / plugin **不再向下游传递** pdfbox；
需要处理 PDF 输入的项目显式引入 `org.apache.pdfbox:pdfbox:3.0.8` 即可。

⚠️ **类加载隔离是这条约束的技术前提，改引擎时必须保留**：

- **`PdfRuntime` 是唯一允许直接触碰 PDFBox API 的类**（`Loader` / `PDDocument` /
  `PDFRenderer` / `ImageType` / `PdfTextExtractor`）。引擎侧一律不出现这些类型。
- **`PdfDependency` 刻意不引用任何 PDFBox 类型**（只用类名字符串 `org.apache.pdfbox.Loader` 探测）。
  若把探测逻辑与 PDFBox 类型写进同一个类，该类链接时的字节码校验就会连带加载 PDFBox ——
  缺依赖时会在探测跑起来之前先抛 `NoClassDefFoundError`。
- **`PPOcrV6Engine` 通过私有嵌套类 `PdfRuntimeHolder` 延迟链接 `PdfRuntime`**：
  引擎的常量池里不出现任何 PDFBox 类型，缺依赖时引擎自身照常构造、照常跑图片 OCR。
  ⚠️ **不要图省事把 `PdfRuntime` 直接写回引擎方法里** —— 那会让「只跑图片」的项目直接起不来。
- 缺依赖时引擎收到 PDF 输入抛 **`IllegalStateException`**（消息指明要补的依赖），
  不是 `NoClassDefFoundError`；`run(...)` 的「unchecked、免 try-catch」契约不变。

回归护栏：`mica-ppocr-core/src/test/java/net/dreamlu/mica/ai/ppocr/pdf/PdfOptionalDependencyTest.java`
—— ①缺依赖报错可读 ②缺依赖不影响图片推理 ③引擎 class 文件里**没有** PDFBox 类引用
（常量池里的类引用是 `org/apache/pdfbox/...` 斜杠形式；错误文案里的依赖坐标是点分字符串常量，不算）。

### PDF 双通道：框坐标单位统一为渲染图像素（改 PDF 通道时必须保留）

两条通道产出的框必须落在**同一坐标系**（按 `renderDpi` 渲染的位图像素）：

- **文本层通道**抽出的坐标是 **PDF point**（1/72 英寸），**已按裁剪框归一**（裁剪框左上角为原点、y 向下），
  且**不含页面 `/Rotate` 旋转** —— PDFBox 的 `getXDirAdj/getYDirAdj` 是「文本方向调整」坐标，
  页面旋转调整的是 `getX/getY`，两者不是一回事；
- **渲染通道**的框是渲染位图的像素坐标（`PDFRenderer` 按**裁剪框** + `/Rotate` 渲染）。

⇒ `PdfRuntime` 用 `PdfPageGeometry` 做「旋转 → 缩放」换算后再输出：
`/Rotate` 90/180/270 各有对应的轴交换公式，最后统一乘 `renderDpi / 72` 换成像素。
`renderDpi` 因此**同时是两条通道的坐标单位基准**。

⚠️ **别把文本层坐标直接当像素用**：同一份 PDF 里电子页与扫描页混排时，
两套坐标系的框会混在同一个结果列表里，下游 `fieldBoxes` 回填位置会整体错位。
回归护栏：`mica-ppocr-core/src/test/java/net/dreamlu/mica/ai/ppocr/pdf/PdfTextLayerCoordinateTest.java`
—— 把页渲染出来取**墨迹包围盒**当真值，逐边校验文本层框；
覆盖 4 种 `/Rotate`、裁剪框偏移、双通道混排。**A/B 实测**：去掉换算后该类 6 个用例全红。
纯数学部分另见 `PdfPageGeometryTest`（零 PDFBox / 零 OpenCV）。

⚠️ **竖排文本仍是文本层通道的盲区**：PDFBox 的「文本方向调整」会把竖排**摆正**成水平，
坐标与渲染图对不上 —— 这类版式要靠 `forceOcr=true` 兜底（页面 `/Rotate` 则已正确处理，不再是盲区）。

### mica-ppocr-structured

- **`BaseStructuredParser<R>`** — SPI **抽象类**（v1.1.3 起由接口改为抽象类），规范所有解析器的入口签名
  `parseResults(List<PPOcrV6Result>) → R`。基类同时提供 10 个 `final` 的 `parse(...)` 一站式重载
  （`String` / `File` / `Path` / `byte[]` / `InputStream` × 带/不带 `DbDetParams`），内部完成 OCR 推理后再调 `parseResults`。

- **`BaseStructuredResult`** — 结果基类，统一持有 `rawResults`（完整 OCR 原始框）与 `fieldBoxes`（字段 → 坐标列表）。
  ⚠️ `fieldBoxes` 目前**并非所有解析器都回填**：`invoice`（Vat + Electronic）/ `business` / `vehicle` /
  `household` / `pdd` / `train` / `taxi` 会回填；`idcard` / `driver` / `bankcard` 三个解析器
  仍走丢弃 box 的文本版 `LabelMatcher.matchValue(...)`，`fieldBoxes` 为空 map。

  **正确性层（v1.2.5 起）** —— 另持有 `fieldConfidences`（字段 → `FieldConfidence`）、
  `needsReview`（是否建议人工复核）与 `reviewReasons`（复核原因）。
  ⚠️ **校验不通过时字段值仍然保留**，只是该字段置信度**直接归零**（不是打折）并置位 `needsReview` ——
  校验位不符只说明"至少认错了一个字符"，不等于"没识别出来"，把值丢掉会让下游比现在更糟。

- **`Validations`**（`core/`）— 校验位算法集合（纯函数、零依赖、线程安全）：
  `validateIdCard`（GB 11643-1999）/ `validateBankCard`（Luhn）/ `validateCreditCode`（GB 32100-2015），
  另含 `genderFromIdNumber`（从证件号性别位回填性别）与 `birthDateFromIdNumber`。
  三态语义（`ValidationStatus`）：`PASS` / `FAIL`（校验位不符，**唯一的硬证据**）/ `UNCHECKABLE`
  （长度或字符集不对，**不能据此断定认错**，不计入违规）。
  ⚠️ **GB 11643 校验码表的下标是 `sum % 11`**，不是 `(12 - sum % 11) % 11` ——
  后者会把标准示例 `11010519491231002X`（余 2 → X）误判成不通过。

- **`FieldConfidence` / `FieldSource` / `ParsedField` / `ValidationResult`**（`core/`）— 字段级置信度模型。
  `FieldSource` 区分取值路径：`LABEL`（标签定位）/ `PATTERN`（正则兜底）/ `DERIVED`（由其它字段推算）/
  `BACKFILL`（回填）/ `MISSING`（未取到）。
  ⚠️ **`MISSING`（值压根没取到）不等于"值不可信"**：字段置信度对缺失字段**不触发** `needsReview`，
  是否要复核由解析器按版面预期显式 `markNeedsReview(...)`（如身份证正面应有姓名/性别/民族等 6 个字段）。

- **`DateRepair`**（`core/`）— 日期修复：把被 OCR 弄乱分隔符的日期还原成 `yyyy-MM-dd`。
  ⚠️ **"纯数字 8 位"必须排在"结构化切分"之前**：结构化路径会把 `2017-05-1.2` 切成 `2017-05-01`（丢掉 12 的 2），
  而纯数字路径给出正确的 `2017-05-12`。非法日期（月份 13、2 月 30 日）宁可不修。

- **`BaseStructuredParser.detParams()`** — 解析器声明默认 DB 检测参数的钩子（默认 `null` = 引擎默认）。
  `parse(...)` 一站式方法会带上它；显式传入的 `DbDetParams` 优先，`null` 时回落到本钩子。
  子类可按自己的**版面类型**声明专属检测参数，不影响同引擎上的其它解析器。

- **`LabelMatcher`** — 公共骨架（`@UtilityClass`）：

  - `matchValue` / `matchValueWithBox` 标签定位 + 位置匹配（`WithBox` 版返回 `LabeledMatch(value, box)`，
    供解析器回填 `fieldBoxes`）；

  - `findLabelBox` 支持 OCR 残缺标签模糊匹配；
    ⚠️ **查找顺序有讲究**：`完整等于 > 以标签开头 > 相邻片段拼接 > fragment 回退 > 别名兜底`。
    **拼接层必须排在 fragment 回退之前** —— 标签的片段天然是标签的子串，fragment 回退必然先命中单个碎片框，
    拼接层将永远触发不到；而拼接出的框覆盖整段标签，几何范围比碎片框更准，用它定位右侧的值更可靠。

  - `matchPattern` / `labelOrFallback` 内容正则兜底；

  - `matchSubstring` OCR 噪声场景的子串提取；

  - 几何工具 `minX/maxX/minY/maxY`。

  **标签别名表**（`core/LabelAliases.java`）— 规范标签 → OCR 认错的多种写法。
  覆盖三类走样："多字"（公民身份号码 → 公民身份证号码）、"带空格"（姓名 → 姓 名）、
  "换说法"（初次领证日期 → 初次领证年月日）。只在规范标签完全找不到时兜底 ⇒ **只增不减**；
  `LabelAliases.setEnabled(false)` 可线上止血。

  **unmatched 语义**（`core/LabelMatchStatus.java`）— `LabeledMatch` 新增 `status` 字段，区分三态：
  `FOUND`（标签与值都取到）/ `LABEL_WITHOUT_VALUE`（标签在、值没识别出来，**值得复核**）/
  `NOT_FOUND`（压根没这个标签，如身份证反面的"姓名"，属正常）。
  ⚠️ `LabeledMatch` 的 2 参构造器保留，源码兼容；新增 3 参构造器带状态。

- **10 个内置解析器**（容器注册的 bean）：`bankcard` / `business` / `driver` / `household` / `idcard` /
  `invoice` / `pdd` / `taxi` / `train` / `vehicle`。
  其中 `InvoiceParser` 是**发票分发器**（按版式分派到 `ElectronicInvoiceParser` / `VatInvoiceParser`，
  二者**不继承** `BaseStructuredParser`，只提供 `parseResults(...)` 静态入口）；
  其余 9 个直接继承 `BaseStructuredParser<R>`。
  Spring Boot / Solon 通过 `PPOcrTemplate.xxx()` getter（10 个）获取已绑定 engine 的解析器实例。
  调用方也可直接 `new XxxParser(engine).parse(bytes)` 一站式调用。

- **发票明细表的列归属判定**（`invoice/InvoiceTableParser`，改这张表必须保留）——
  列区间 = 「表头框左右各放宽 `xTolerance`」，值框归属该列的判定口径是
  **「值框中心 或 值框左边缘落在列区间内」**：
  - ⚠️ **不能只看中心**：票面数值列常是「表头居中 + 值右对齐」，
    值框整体落在表头框右侧，中心会越过表头右缘整整一个框宽。
    实测数电票（200 DPI 渲染）：金额列表头 `金　额` 框 `[1153,1228]`、
    值框 `99.62` 为 `[1244,1308]`、中心 `1276` —— 越过表头右缘 48px，
    只看中心 ⇒ **整列漏值，明细金额丢失**。
  - ⚠️ **也不能用「区间重叠」**：增值税票里单价列没有对应表头，
    单价值框与金额列仅搭接 3px，重叠判定会把两行金额串成 `99.6299.62`。
  - 「中心或起点」是原「只看中心」的**超集** ⇒ 原先能匹配的框一个都不会丢。
  回归护栏：`InvoiceParserTest#parse_tableValueRightAlignedToHeader_keepsAmount`。

- **通用解析器**（`generic/`，v1.2.5 起）— **声明式 schema 入口**，与上面 10 个强类型 parser
  **并行存在**（不替换、不迁移存量 API）。新增单据类型写一份 `OcrFieldSchema` 即可抽取字段，
  无需再实现一个解析器类。

  - `OcrFieldSchema.builder("采购合同").field("contractNo").labels("合同编号", "合同号")
    .compact().add().build()` —— 每个字段可声明：标签列表（首个为规范标签，其余为别名）、
    内容正则（全匹配语义）、校验规则、归一化方式、是否可选。
  - `FieldRule` 内置 `digits(n)` / `length(min,max)` / `date()` / `idCardNumber()` / `bankCardNumber()` /
    `creditCode()` / `of(name, predicate)`；身份证 / 银行卡 / 信用代码三类**直接委托 `Validations`**，
    与存量解析器共用同一套校验位实现，不存在两套口径。
  - `ValueNormalizer`：`NONE` / `DIGITS` / `COMPACT` / `DATE`。
    ⚠️ **归一化在"取值之后、校验之前"执行** —— 调用方拿到的值与规则判定依据的值永远是同一个。
    ⚠️ 身份证号请用 `COMPACT`（保留末位 `X`），**不要用 `DIGITS`**（会把 `X` 丢掉）。
  - `OcrStructuredResult`：`values`（字段名 → 值）/ `missingFields` / 继承来的 `fieldConfidences` /
    `needsReview` / `reviewReasons` / `fieldBoxes`；另有 `allTrusted()` 与 `getNeedingReviewFields()`。
  - **取值路径**（按优先级）：标签定位 → 合并框剥值 → 内容正则兜底 → 字段缺失。
    标签路径取到的值不符合 schema 正则时改走正则兜底；正则也兜不到就**保留标签值**，
    交给规则校验暴露问题 —— 宁可保留可能有误的值，也不丢值。
  - ⚠️ **`ValueLocator` 是通用层内部专用**（包级可见），与存量 `LabelMatcher` **行为完全不同**：
    存量用「x 中心 > 标签中心 + `dx*10+dy` 打分 + 完全 y 重叠」，通用层用三层判定 ——
    垂直重叠率 ≥ 0.3、水平间距 ≤ 标签高度 × 8、**值归属最近的标签**。
    第三条是关键：驾照版面里「性别」的真值「女」未被检出时，若不做归属判定就会越过
    「国籍」标签抓到「Nationaliy中国」，静默产出错误字段 —— 宁可判定为缺失，也不能给错值。
    ⚠️ **存量 `LabelMatcher` 一行未动**（它是 10 个强类型 parser 的公共骨架，改默认行为会动到
    既有的回填与修正逻辑）；两层判定并存是有意的隔离，不是重复实现。
  - **复核触发**：schema 里声明的字段即"这张单据应该有该字段"，缺失时（除非标了 `optional()`）
    触发人工复核 —— 这是阶段二「缺失字段由解析器按版面预期显式标记」原则的通用化。
  - Spring Boot / Solon 通过 `PPOcrTemplate.generic(schema)` 获取已绑定 engine 的通用解析器实例。

### mica-ppocr-spring-boot-starter

- **`PPOCRAutoConfiguration`** — auto-wires `PPOcrV6Engine` bean when `mica.ai.ppocr.enabled=true`.

  ⚠️ **`mica.ai.ppocr.enabled` 是整个 Starter 的总开关**：`PPOCRAutoConfiguration` /
  `PdfAutoConfiguration` / `StructuredParserAutoConfiguration` 三个配置类**都**受它约束
  （Spring: `@ConditionalOnProperty`；Solon: `@Condition(onExpression = "${mica.ai.ppocr.enabled:true} == true")`），
  关闭时**不注入任何 PP-OCR 功能 Bean**，容器照常启动。
  ⚠️ 关闭时 **OpenCV 原生库也不会加载** —— 它由 `PPOcrV6Engine` 构造期独占加载，
  引擎 bean 缺席 ⇒ native 不加载。业务方若自行使用 `org.opencv.*`，
  需自行 `nu.pattern.OpenCV.loadLocally()`（否则 `UnsatisfiedLinkError`）。
  解析器 bean 以 `PPOcrV6Engine` 为构造参数注入 —— 若解析器配置类不受同一开关约束，
  引擎 bean 缺席会导致 `NoSuchBeanDefinitionException` → **容器 refresh 失败**
  （P0-2 回归：Spring 端 `StructuredParserAutoConfiguration` 曾漏掉该闸门）。
  **新增自动配置类时必须一并挂上这个闸门。**

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
  `pdf.enabled=false` 时 bean 仍存在，但取 `PdfOcrConfig.defaults()`（忽略 yml 的 pdf 参数）；
  `mica.ai.ppocr.enabled=false` 时该 bean **不存在**。

- **OpenCV 原生库加载** — 由 `PPOcrV6Engine` 构造期独占完成（`ensureOpenCvLoaded()`），
  Starter / Plugin **不再有** `OpenCVNativeLoader`。理由与约束见「core 引擎」一节。

### mica-ppocr-solon-plugin

与 Spring Boot Starter **能力对齐、类名一一对应**（`net.dreamlu.mica.ai.ppocr.solon.*`），
差异只在框架注解：Solon 用 `@BindProps` + `@Condition`，Spring 用 `@ConfigurationProperties` +
`@ConditionalOnProperty` / `@ConditionalOnMissingBean`。

- **`PpocrPlugin`**（`integration/` 包）— 插件入口，**显式 `beanMake` 各自动配置类**：
  `PPOCRProperties` / `PPOCRAutoConfiguration` /
  `PdfAutoConfiguration` / `StructuredParserAutoConfiguration`。

### Porting conventions

Python↔Java mapping: `numpy`→`utils.NdArrayUtils`, `pyclipper`→`utils.Offset` (纯 Java 圆角外扩),
`cv2.minAreaRect`→`Imgproc.minAreaRect`, `np.rot90`→`Core.ROTATE_90_COUNTERCLOCKWISE`,
`@dataclass(frozen=True)`→Lombok `@Value` 类 + `@Accessors(fluent = true)`（保持 `record` 风格的 `xxx()` 访问）。

### Known divergences from Python

- `pyclipper` uses scaled-integer math and quantizes its output to integers, while
  `Offset.unclip` works in doubles → the unclip polygons differ by <1px.
  ⚠️ **The dominant source of the final-box divergence is elsewhere, and was previously
  misattributed to JTS**: `BoxUtil.orderMinAreaBoxPoints(float[][])` routes the float polygon
  through `MatOfPoint` (CV_32S), truncating coordinates to integers before `minAreaRect`.
  Measured on 3827 real detection boxes, dropping that truncation lifts the final-box
  agreement with the Python reference from 5.1% to 55.4% (ideal Minkowski geometry: 55.2%) —
  i.e. the truncation, not the unclip implementation, is the main gap.
  Fixing it moves ~98% of boxes by ≤3px ⇒ it is a **separate, deliberate change**, not a
  drive-by cleanup.

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

- **`detMaxSideLimit` 是默认组合下唯一的缩放闸门（默认 4000 → 1920）** ——
  `detLimitType=min` + `detLimitSideLen=64` 时，**短边 ≥ 64 的图一律按原分辨率送进检测模型**
  （`computeResizedHW` 只在 `max(rh, rw) > maxSideLimit` 时才做二次缩放）。
  原默认 4000 对 2K~4K 图几乎不生效 ⇒ 行驶证 2988×2199 的 det 输入是 **2208×2976（657 万像素）**，
  而 det 推理占整条链路的 **74%**。
  - **实测收益（tiny 档，7 轮 × 10 次取中位）**：行驶证 1638 → **951 ms（1.72×）**、
    发票 1495 → **1223 ms（1.22×）**、PDF 通道（forceOcr，A4@200dpi）1374 → **1149 ms（1.20×）**；
    出租车票 756 → 764 ms（持平，框数未变说明 det 输入未变）。
  - **1920 是实测安全线**（tiny 档，44 张样图 × 10 个解析器逐字段比对）：
    **无任何字段变为 null**；6 个业务字段有单字抖动（4 变差、2 变好），
    其余全部一致；**1536 起出现静默串值**（发票号码被错配到别的框），
    **1280 出现车牌丢字**（"NBU1346" → "BU1346"）。
  - **断崖曲线（44 图 × 10 解析器 vs 4000 基线，统计「业务字段值变化数 / 字段直接消失数」）**：

    | detMaxSideLimit | det 输入（行驶证） | 字段变化 | 字段消失 | 受影响图片 | 行驶证耗时 | 发票耗时 |
    | --- | --- | --- | --- | --- | --- | --- |
    | 4000（原默认） | 2208×2976 | 0 | 0 | 0 | 1638 ms | 1495 ms |
    | **1920（现默认）** | 1408×1920 | **10** | **0** | 8 / 44 | **951 ms** | **1223 ms** |
    | 2000 | 1472×2016 | 18 | 0 | 8 / 44 | 914 ms | 1258 ms |
    | 2048 | 1504×2048 | 14 | **1** | 7 / 44 | 971 ms | 1355 ms |
    | 1536 | 1120×1536 | 54 | 2 | 22 / 44 | — | — |
    | 1280 | 928×1280 | 62 | 9 | 29 / 44 | 622 ms | — |
    | 960 | 704×960 | **81** | **18** | **32 / 44** | 485 ms | — |

    ⇒ **断崖在 1536 ~ 1920 之间**，1920 是唯一测下来"零字段丢失"的档位。
    速度曲线是平滑的、精度曲线是断崖式的：1920 → 960 能再快一倍（行驶证 951 → 485 ms），
    代价是**银行卡号、VIN、火车票身份证号与姓名、身份证有效期整字段消失**，
    并出现教科书级串值（出租车票 `FuelSurcharge` 1 → 40.60，把票价认成了燃油附加费）。
    ⇒ **分辨率不是速度的正确杠杆**：det 推理占整链路 74%，要再快应换 det 模型
    （档位 / 量化 / GPU），而不是继续降输入分辨率。
  - ⚠️ **1920 ~ 2048 区间内耗时差异基本在噪声内**（行驶证 951 / 914 / 971 ms，非单调；
    发票 1223 / 1258 / 1355 ms，单调但 2048 已慢 11%）
    ⇒ 这一带的取值取舍**只能看字段级验收数据**，不能靠"更大的值更安全"或性能直觉决定。
  - ⚠️ **检测质量对分辨率<b>不是单调的</b>，别以为"调大更安全"** ——
    提高分辨率会改变 det 框的**切分方式**（可能把两行并成一个框、也可能切得更碎），
    所以更高分辨率可能反而更差。实测：2000（18 处变化）比 1920（10 处）差，
    2048（14 处 + **丢了 1 个字段**）也比 1920 差。
    典型反例 `idcard1`：1920 / 4000 下这张身份证**什么都没识别到**（只有 `Side=UNKNOWN`），
    2000 下反而多认出一个**校验位不符的身份证号** `912920119961106259`（`needsReview=true`）。
    ⇒ **1920 是在测过的点里"零字段丢失"且最快的档位**，
    不是"随便取的整数"。1921~3999 区间未逐档扫描过，
    若要改这个默认值必须**重新跑字段级验收**（44 图 × 10 解析器），不能凭"更大更安全"推断。
  - ⚠️ **`detMaxSideLimit` 会向上对齐到 `STRIDE=32` 的倍数** ——
    1920 / 2048 整除（名实相符），但 **2000 → 62.5 进位到 2016**（配置名与实际输入对不上）。
    选值时优先挑 32 的整数倍。
  - ⚠️ **降 det 分辨率只伤「检测召回」，不伤「识别精度」** ——
    裁剪是从**原图**切的（`CropUtil.cropByPolys(imgBgr, sortedBoxes)`），
    rec 的输入质量与 det 输入分辨率无关 ⇒ 小字区域是**检不出来**（框直接没了），
    而不是"认糊了"。所以症状以「字段消失 / 标签值错配」为主，不是「置信度普遍下降」。
  - ⚠️ **检测分辨率是引擎级的，没有 per-call 覆盖** ——
    `DbDetParams` 只有 thresh / boxThresh / unclipRatio / maxCandidates / minSize，
    不含分辨率 ⇒ 想让「发票用高分辨率、行驶证用低分辨率」做不到，只能全局一个值。
  - ⚠️ **三处默认值必须同步改**：`core` 的 `PPOcrV6Config`、
    `spring-boot-starter` 的 `PPOCRProperties`、`solon-plugin` 的 `PPOCRProperties`。
    后两者通过 `PPOcrV6Config.from(Source)` 把自己的默认值**无条件覆盖**核心默认值 ——
    只改核心那一处，Spring Boot / Solon 应用里改动**根本不生效**。
  - ⚠️ 用 `detLimitType=max` 且 `detLimitSideLen` > 1920 的场景行为会变
    （原本按长边缩放，现在被 1920 二次截断），这类用户需自行调大本值。
  - ⚠️ **降分辨率会让「(小写)￥400.00」里的 ¥ 被认成汉字「洋」** ⇒ 老版增值税发票的
    「价税合计（小写）」取不到标签内提取，只能靠位置锚定兜底，而兜底会命中读序更靠前的
    **明细表「金额」列（不含税金额）** —— 实测 5 张发票里 2 张的价税合计被取错
    （￥400.00 → ￥377.36、￥2676.24 → ￥2524.75）。
    修法见下一条：**大写反推兜底**。
  - ⚠️ **引擎会返回「空文本 + 置信度 0」的框，这是既有行为，不是新 bug** ——
    `runOnMat` 把 rec 的空串解码结果原样塞进 `PPOcrV6Result`，不过滤。
    44 张样图里改前就有 8 张含此类框（taxi1 / train5 / business4 / vehicle2 /
    household_register×3 / idcard1）。降低检测分辨率只会让**更多**图出现（行驶证 vehicle1 也多了一个），
    因为 det 在空白区域会误检出框。
    ⇒ 断言 OCR 结果时**只能对非空文本的框校验置信度**；
    要在引擎层过滤空框属于独立议题（会改变所有调用方看到的框数），未做。
- **发票「价税合计」的大写反推兜底（VAT 与电子票两条链路都要有）** ——
  中文大写金额是结构化表达，OCR 几乎无误识 ⇒ 以**大写反推的小写**为基准校验 OCR 小写值，
  整数部分差异 ≥ 1 元即判定 OCR 误识，用反推值覆盖（角分差异如 21.79 vs 21.80 保留 OCR 值）。
  - ⚠️ **`ElectronicInvoiceParser` 与 `VatInvoiceParser` 两条链路都必须实现** ——
    曾经只有电子票那条有，老版 VAT 漏了，于是同一类「金额串位」在 VAT 上无人兜底。
    `InvoiceParser` 分发到哪条链路取决于**有没有「校验码」标签**（有 → VAT），
    所以 5 张老版样图全部走 VAT 分支。
  - 回归测试：`InvoiceParserTest#parse_vat_totalLower_fallsBackToUpperCaseWhenYuanSymbolMisread`
    （撤掉兜底即红，报 `expected: <￥400.00> but was: <￥377.36>`）。

**别做**：调大 `DbDetParams.minSize` 过滤碎片（会连小字标签一起丢，如行驶证「所有人」变 null）。

### 性能优化的红线：改内存 / 耗时，不改数值

预处理是 bit-exact 的高危区。三处预处理（det / rec / doc_ori）已做过**内存融合**：
归一化结果直接写入 CHW / NCHW 输出数组，不再 materialize 中间 HWC 数组，也不再生成
CV_32F 中间 Mat。实测收益：

| 场景 | 堆分配 | 耗时 |
| --- | --- | --- |
| det 预处理（3000×4000 图） | 275 MB → 137 MB（-50%） | 110 ms → 42 ms（2.6×） |
| rec 预处理（50 框 × 1200px 宽） | 79.1 MB → 39.8 MB（-50%） | 21.1 ms → 15.9 ms（-25%） |

**ONNX Runtime 输出张量的读取路径（1.18.0，坑很深）**：

`OnnxTensor#getFloatBuffer()` **本身就已经拷贝了两次** —— 实现是
「`getBuffer()`（native → 堆 ByteBuffer）→ `asFloatBuffer()` → `FloatBuffer.allocate(capacity)` → `put(src)`」，
返回的是一块**堆数组支撑**的 `HeapFloatBuffer`。于是代码里再写
`outTensor.getFloatBuffer().get(new float[n])` 就是**第三次拷贝**，白白多拷一遍全量张量：

- rec 输出每批 B×T×C = 6×40×7180 = **6.9 MB**（tiny 档），每帧数批
- det 输出在原图分辨率下（2208×2976）可达 **26 MB**

⇒ **取数一律走 `PPOcrV6Engine.toFloatArray(FloatBuffer, int)`**（包级可见，仅供测试对拍）：
`buf.hasArray()` 为真且长度恰好匹配时直接返回 `buf.array()`，**零拷贝**；否则兜底回一次批量拷贝。
`get(float[])` 是逐值拷贝，与底层数组是同一批 float 的同一批位模式 ⇒ **逐位相同**，识别结果不变。

三个必须记住的细节：

1. **`getFloatBuffer()` 每次调用都重新拷贝**，返回的是**新的** `HeapFloatBuffer` ⇒
   断言零拷贝时**只能取一次** buffer 再比 `array()`，取两次比的是两个不同实例。
2. **`getByteBuffer()` 返回的是 BIG_ENDIAN 的堆 buffer**（`ByteBuffer.allocate` 的默认值），
   而张量数据是 native 小端 ⇒ 直接 `asFloatBuffer()` 会**读出乱数**，
   必须 `order(ByteOrder.nativeOrder())` 后再取。
3. **「直接从 FloatBuffer 解码」是负收益** —— `FloatBuffer.get(int)` 带边界检查与间接寻址，
   实测比 `float[]` 解码**慢 50%~90%**（tiny 档单批：float[] 约 1.1~1.4 ms，FloatBuffer 约 2.0~2.7 ms）。
   整体净收益还取决于 `getFloatBuffer()` 自身耗时的波动，两次测量净收益**正负翻转**（+0.42 ms / -0.60 ms）
   ⇒ **不要走这条路**；要省就省**拷贝**，别改解码容器。`getByteBuffer` 直解同样是负收益。

**实测收益**（tiny 档，单线程 CPU；分配量用 `ThreadMXBean#getThreadAllocatedBytes` 精确统计
Java 侧堆分配，峰值存活堆用 1ms 采样线程取中位数）：

| 样图 | 每次 OCR 的堆分配 | 峰值存活堆 | 端到端耗时 |
| --- | --- | --- | --- |
| 行驶证 vehicle1（36 框） | 235.7 MB → **160.2 MB（−32%）** | 170.8 MB → **126.8 MB（−26%）** | 1641 → 1621 ms（−1.2%） |
| 发票 invoice1（73 框） | 306.3 MB → **186.2 MB（−39%）** | 205.7 MB → **111.3 MB（−46%）** | 1513 → 1478 ms（−2.3%） |
| 出租车票 taxi1（53 框） | 166.8 MB → **99.7 MB（−40%）** | 106.0 MB → **67.8 MB（−36%）** | 767 → 750 ms（−2.2%） |

⇒ **耗时只降 1%~2%**（det 推理占 74%，这里省的是拷贝不是推理），
**但堆分配降 32%~40%、峰值存活堆降 26%~46%** —— 这项优化的价值主要在**内存与 GC 压力**，不在速度。
并发场景下每个在途请求各省 75~120 MB 分配量。

**String 实例方法每次调用都重新编译正则（structured 模块已全量预编译）**：

`String#matches` / `String#replaceAll` / `String#split` 的入参是**正则字符串**，
每次调用都会 `Pattern.compile` 一遍。解析层的「标签 × 候选框」扫描路径上逐框调用，
一次解析要编译上百次同一份正则 ⇒ 全部提为 `static final Pattern`：

| API | String 实例方法 | 预编译 Pattern | 结论 |
| --- | --- | --- | --- |
| `matches` | 119 ns | 22 ns | **改**，5.4× |
| `replaceAll` | 184 ns | 86 ns | **改**，2.1× |
| `split("-")` | 10 ns | 44 ns | **不改**，String 快 4.2× |
| `split("\\.")` | 11 ns | 26 ns | **不改**，String 快 2.4× |

⚠️ **`split` 有 JDK 快路径，不要改**：`String#split` 对「单字符非元字符」与
「`\` + 非字母数字的两字符转义」走**免编译快路径**（用 `indexOf` 切），
换成 `Pattern#split` 反而**慢 2~4 倍**。只有多字符元字符类（如 `split("[-/.年月日]")`）
才真的编译正则，那种才值得改（实测 1.79×）。

等价性依据：`String#matches(r)` ≡ `Pattern.compile(r).matcher(s).matches()`，
`String#replaceAll(r, rep)` ≡ `Pattern.compile(r).matcher(s).replaceAll(rep)`，
两者 flags 均为 0 ⇒ 预编译**逐位等价**。

**实测收益**（tiny 档，解析层基准，中位数）：

| 解析器 | 解析耗时 | 每次解析的堆分配 |
| --- | --- | --- |
| InvoiceParser | 0.311 → 0.239 ms（−23%） | 1,109,568 B → **444,376 B（−60%）** |
| TaxiReceiptParser | 0.221 → 0.172 ms（−22%） | 500,920 B → **158,776 B（−68%）** |
| VehicleLicenseParser | 0.195 → 0.151 ms（−23%） | 423,560 B → **158,872 B（−63%）** |
| IdCardParser | 0.092 → 0.071 ms（−22%） | 205,720 B → **72,832 B（−65%）** |

⇒ 解析耗时降 ~22%，**分配量降 60%~68%** —— 每次解析不再新建上百个临时正则对象。
代价：52 个 `Pattern` 常量约 **40 KB** 静态堆 + 一次性编译 **~50~90 µs**（类加载时一次，可忽略）。

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
| pdfbox                   | 3.0.8（PDF 双通道依赖，**optional：不向下游传递**，需要时显式引入）      | 52 | Java 8  |
| slf4j-api                | 2.0.20                                                    | 52 | Java 8  |
| slf4j-simple             | 2.0.20（**test scope only** —— 替代原 logback-classic 1.3.15） | 52 | Java 8  |
| lombok                   | 1.18.48                                                   | 52 | Java 8  |
| spring-boot-dependencies | 2.7.18（替代原 3.5.16，3.x 是 Java 17）                          | 52 | Java 8  |
| mica-auto                | 2.3.5（替代原 4.0.1，3.x/4.x 是 Java 17）                        | 52 | Java 8  |
| solon                    | 4.1.1（**保留** — 实测所有 class 文件 max major = 52）              | 52 | Java 8  |

> 升级任何依赖后，**必须重跑一次字节码校验**（`javap -v -cp <jar> <类>` 看 major version）。
> 版本号漂移会让本表「已校验 Java 8」的结论失效。

---

## 变更记录（CHANGELOG）写作规范

`CHANGELOG.md` 是**面向使用者的发布说明**，不是开发过程记录。三条硬规则：

1. **一条改动 = 一条 bullet = 一段话，零子条。**
   与已发布段落（如 v1.2.4）同款写法：`feat(scope): 标题 —— 一段话讲清改了什么、带来什么效果。`
   不使用 `  - ` 子条分层。

2. **只写「变了什么」与「效果 / 行为要点」，不写「怎么实现」。**
   实现细节（逐项字段枚举、算法推导、A/B 实测数据、源码级对照）留在 `AGENTS.md` 里，
   CHANGELOG 不重复。两处内容分工：
   `CHANGELOG` = 变了什么 → `AGENTS.md` = 怎么实现（含"为什么这么取舍"）。

3. **同一能力只留最终态与效果，周期内的中间调整不单独留条目。**
   判断依据是**该状态是否在已发布版本里存在过**：
   - 从未发布过的新增能力（如构造期预热）⇒ 只写一条「新增了什么 + 效果」，
     其中途的 public→private 调整、新增又删掉的配置项**不单独成条**；
   - 已发布能力的缺陷修复 ⇒ 写一条 `fix(...)`，把行为变化说清楚。

> ⚠️ **对调用方可见的行为变更必须留**（如校验失败时置信度归零、`close()` 超时后在途推理
> 可能被中断、`enabled=false` 时 OpenCV 原生库不再加载）；纯内部重构不留。

**长度参照**：已发布段落平均 ~414 字符 / 最长 728（v1.2.4）。单条超过 ~750 就该回头删从句，
而不是加子条。