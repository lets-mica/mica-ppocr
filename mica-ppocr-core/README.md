# mica-ppocr-core

PP-OCRv6 文字检测 + 识别的**核心引擎**，纯 ONNX Runtime 推理，零 PaddlePaddle 依赖。
本模块只做 OCR（检测 → 排序 → 裁剪 → 识别），**不含任何结构化解析**，业务字段抽取见
[`mica-ppocr-structured`](../mica-ppocr-structured/README.md)。

> （默认 CPU 单线程 `intraOp=interOp=1`，保证跨平台确定性输出）。

## 1. 引入依赖

```xml
<dependency>
    <groupId>net.dreamlu</groupId>
    <artifactId>mica-ppocr-core</artifactId>
    <version>${mica.ppocr.version}</version>
</dependency>
```

原生库（ONNX Runtime / OpenCV）由 Maven 按平台自动引入，**无需手动安装**。

## 2. 快速开始

```java
PPOcrV6Config config = PPOcrV6Config.builder()
    .detModelPath("models/ppocr-v6/tiny/det.onnx")
    .recModelPath("models/ppocr-v6/tiny/rec.onnx")
    .recCharDictPath("models/ppocr-v6/tiny/dict.txt")
    .useDocOrientationClassify(true)                                  // 可选：整图方向分类
    .docOrientationModelPath("models/ppocr-v6/doc_ori/doc_ori.onnx")  // 开启后必填
    .docOrientationThresh(0.4f)                                       // < 此值视为 0°；默认 0.4
    .build();

// 引擎实现 Closeable，推荐 try-with-resources
try (PPOcrV6Engine engine = new PPOcrV6Engine(config)) {
    List<PPOcrV6Result> results = engine.run("test_images/vehicle/vehicle1.png");
    for (PPOcrV6Result r : results) {
        System.out.printf("%s  (%.3f)%n", r.text(), r.score());
    }
}
```

模型路径按 §4 的档次目录填写（`models/ppocr-v6/{tiny|small|medium}/`）。

## 3. 公开 API

核心类 [`PPOcrV6Engine`](../mica-ppocr-core/src/main/java/net/dreamlu/mica/ai/ppocr/engine/PPOcrV6Engine.java)。

公开入口只暴露 `String` / `File` / `Path` / `byte[]` / `InputStream` 入参，内部自动完成
OpenCV `Mat` 的解码与 release，**调用方无需关心 native 内存**。

### 3.1 `run(...)` — 完整 OCR

检测 → 排序 → 裁剪 → 识别，返回按阅读顺序排列的文字框列表。

| 入参 | 场景 |
|------|------|
| `run(String)` | 本地文件路径 |
| `run(File)` | `File` 对象 |
| `run(Path)` | 非默认文件系统（ZIP / JIMFS / 内存 FS） |
| `run(byte[])` | 上传字节流 |
| `run(InputStream)` | URL / S3 / HTTP 下载流 |

每个 `run` 都配套一个 `(..., DbDetParams)` 重载，用于**按调用**临时覆盖 DB 后处理参数（见 §5）。

- `run(byte[])` / `run(Path)` 会**嗅探 `%PDF-` 魔数**，命中时自动走 PDF 双通道并平铺所有页结果。
- `run(byte[])` / `run(InputStream)` **不声明 `throws IOException`** —— PDF 解析或流读取失败时
  引擎内部包为 `UncheckedIOException` 抛出，调用方免 try-catch。
- `Path` 重载优先走 native 文件读取，不支持的 `FileSystem` 自动退回 `Files.readAllBytes`。

### 3.2 `detect(...)` — 仅检测

返回 `PPOcrV6Engine.DetectResult`，含 `boxes`（`int[][][]`，N×4×2，顶点顺序左上/右上/右下/左下）
与 `scores`（与 boxes 一一对应）。入参形态同上。

### 3.3 `Mat` 版 —— 高级场景

确需复用已加载 `Mat`（如同一图跑多次推理）时使用：

| 方法 | 说明 |
|------|------|
| `runMat(Mat imgBgr)` | 完整 OCR（`Mat` 的 release 由调用方负责） |
| `detectMat(Mat imgBgr)` | 仅检测 |
| `recognizeMat(List<Mat> imgList)` | 仅识别，返回 `RecognizeResult`（`texts` + `scores`） |

### 3.4 结果结构

`PPOcrV6Result`（Lombok `@Accessors(fluent = true)`）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `text` | `String` | 识别文本 |
| `score` | `float` | 置信度 |
| `box` | `int[][]` | 四顶点坐标（左上、右上、右下、左下） |
| `rotatedDegrees` | `float` | 文本行旋转角度（度） |

## 4. 模型档次

模型**已随仓库入库**（`models/ppocr-v6/`），clone 后无需额外下载：

| 档次 | det 模型 | rec 模型 | 字符表 | 定位 |
|------|----------|----------|--------|------|
| `tiny` | 1.7 MB | 4.3 MB | 约 2855 字符 | 轻量优先，速度快，精度一般 |
| `small` | 9.4 MB | 20.2 MB | 约 2855 字符 | 速度与精度均衡，推荐默认 |
| `medium` | 59.2 MB | 73.0 MB | 约 7180 字符 | 精度优先，覆盖更全字符集 |

> `medium` 的 det/rec 模型以 `.onnx.zip` 形式入库，**需解压为 `det.onnx` / `rec.onnx` 后使用**。

**可选**：文档方向分类模型 `models/ppocr-v6/doc_ori/doc_ori.onnx`（6.47 MB）。

## 5. 按调用覆盖 DB 参数

`DbDetParams` 是一次性覆盖检测后处理阈值的不可变值对象，**不修改引擎共享状态，线程安全**：

```java
// 放宽阈值：海报类大字号稀疏数字
DbDetParams params = DbDetParams.of(0.3f, 0.4f, 1.8f);
List<PPOcrV6Result> results = engine.run("pdd-banner.png", params);
```

| 工厂方法 | 参数 |
|----------|------|
| `DbDetParams.of(thresh, boxThresh, unclipRatio)` | 检测像素阈值 / 检测框阈值 / unclip 比例 |
| `DbDetParams.of(thresh, boxThresh, unclipRatio, maxCandidates, minSize)` | 追加最大候选框数 / 最小框尺寸 |

`thresh` / `boxThresh` / `unclipRatio` 与 `PPOcrV6Config` 的同名项一一对应（见 §6）。

## 6. PPOcrV6Config 完整参数表

`PPOcrV6Config` 是 Lombok `@Builder` 的不可变配置对象。**下表即全量字段**，
Spring Boot / Solon 两端的 yml 配置项与之逐项对应（kebab-case ↔ camelCase）。

### 6.1 模型（必填）

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `detModelPath` | `String` | — | **必填**，检测模型路径 |
| `recModelPath` | `String` | — | **必填**，识别模型路径 |
| `recCharDictPath` | `String` | — | **必填**，识别字符字典路径 |

### 6.2 检测（DB 后处理）参数

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `detLimitSideLen` | `int` | `64` | 检测限制边长。与 `detLimitType` 配合：长/短边超过此值时按比例缩放。默认组合 `64 + min` 面向证件/卡证类小图，保证短边至少 64 像素；通用文档/自然场景可改较大值 + `max` |
| `detLimitType` | `String` | `"min"` | 限制类型：`min`（短边限制）/ `max`（长边限制） |
| `detMaxSideLimit` | `int` | `4000` | 检测最大边长限制 |
| `detThresh` | `float` | `0.3f` | 检测像素阈值 |
| `detBoxThresh` | `float` | `0.6f` | 检测框阈值 |
| `detUnclipRatio` | `float` | `1.5f` | 多边形 unclip 比例 |

### 6.3 识别参数

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `recImageShape` | `int[]` | `{3, 48, 320}` | 识别输入 shape `[C, H, W]`。⚠️ 引擎当前**只取 `H` 段**（`recImageShape[1]`），`W` 段不参与推理；识别宽度的分桶上下限（320 ~ 3200）写死在引擎构造处，改 `W` 不影响运行行为 |
| `recBatchSize` | `int` | `6` | 识别批处理大小 |

### 6.4 文档方向分类

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `useDocOrientationClassify` | `boolean` | `false` | 是否启用整图 4 方向分类 + 自动旋转。默认关闭，与原版完全 bit-exact |
| `docOrientationModelPath` | `String` | — | 方向分类模型路径，**`useDocOrientationClassify=true` 时必填** |
| `docOrientationThresh` | `float` | `0.4f` | 置信度阈值，低于此值视为 0°（不旋转）。范围 `[0, 1]` |

### 6.5 性能 / 运行模式

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `preferAccelerator` | `boolean` | `false` | 是否优先 GPU。默认 `false` 强制 CPU，保证跨平台 bit-exact；置 `true` 后按 CoreML（macOS）> CUDA > CPU 顺序探测 |
| `intraOpNumThreads` | `int` | `1` | ONNX 单算子内并行线程数，**推荐 = 物理核数** |
| `interOpNumThreads` | `int` | `1` | ONNX 交互线程数。OCR 流水线严格串行，设大也没用 |
| `execMode` | `ExecutionMode` | `SEQUENTIAL` | ONNX 执行模式：`sequential`（低内存，PP-OCR 默认足够）/ `parallel`（需 `interOpNumThreads > 1`，吞吐更高、内存更高） |
| `enableCpuMemArena` | `boolean` | `false` | CPU memory arena。高水位不归还 OS，动态分辨率下内存持续增长直至 OOM（issue #14），故默认关闭（吞吐约损 10%） |
| `enableMemoryPattern` | `boolean` | `false` | 内存模式优化。shape 变化时同样吃内存，建议与 `enableCpuMemArena` 成对开启或成对关闭 |
| `pdfOcrConfig` | `PdfOcrConfig` | `defaults()` | PDF 双通道配置，见 §8 |

### 6.6 文档方向分类模型说明

- **模型**：`PP-LCNet_x1_0_doc_ori` 的 ONNX 导出，shape = `[1, 3, 224, 224]`，输出 `[1, 4]`（softmax + argmax）。
  可从 [ModelScope `farming789/pp-lcnet-doc-ori`](https://www.modelscope.cn/models/farming789/pp-lcnet-doc-ori) 直接下载 `model.onnx`，零 Paddle 依赖。
- **性能与代价**：CPU 单图多 ~3 ms；内存多 ~30 MB（加载第三个 ONNX session）。
- **阈值取值依据**（实测 doc_ori 的 4 类 softmax 概率）：
  - `idcard1`（手机横拍、270° 倒置）：score=0.430 → 必须 ≥ 0.4 才能正确旋转
  - `taxi1` / `taxi3`（正向图、误判 180°）：score=0.387 / 0.396 → 必须 > 0.4 才能丢弃
  - 其余样本全部 score < 0.3，0.4 阈值不会误触发
  - ⇒ `0.4` 是当前样本集下「误判丢弃 / 误判旋转」的最佳折中点。

## 7. 生命周期与并发

- `PPOcrV6Engine` 实现 `Closeable`，**推荐 try-with-resources**。
- **OpenCV 原生库在构造期加载**：openpnp 的 OpenCV 不会随 JVM 启动自动加载，
  缺一次 `loadLocally()` 就会在首次 `Imgproc.xxx` 调用时抛 `UnsatisfiedLinkError`。
  引擎构造期加载 ⇒ **构造成功 ⇒ 推理不会撞 native 缺失**；加载失败直接抛 `IllegalStateException`（fail fast）。
- **构造期自动预热**：构造器内部用一张 64×64 程序化合成笔画图跑通 det + rec 全链路，
  把 ONNX Runtime 的计算图优化、内存池初始化与 JIT 提前到初始化期，
  消除「容器刚起来时第一个请求显著慢于后续」。预热是**初始化语义，无开关、无需业务方调用**；
  预热失败只记 WARN，不影响引擎可用性。
- **并发安全**：推理入口持读锁（多线程可并发推理），`close()` 持写锁。
- **`close()` 是有界等待**：最多等 1 秒让在途推理自然结束；超时则引擎立即退役（新请求被拒），
  native 会话**延后**到在途推理结束后由守护线程释放 —— 关停不会被长耗时推理（多页 PDF OCR）拖住，
  也不会出现 native 层崩溃。⚠️ 超时后**在途推理可能被中断**（抛 `IllegalStateException`），属预期行为。

## 8. PDF 双通道

`run(byte[])` / `run(Path)` 嗅探到 `%PDF-` 魔数时自动走 PDF 双通道，**调用方无需区分**：

- **文本层通道**：文字型 PDF 直接抽取文本层坐标（行聚类 + 大间距拆分），不跑 OCR。
- **渲染通道**：扫描件 / 文本层不可用时，用 PDFBox 渲染成位图再走 OCR。

由 `PdfOcrConfig` 控制：

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `enabled` | `boolean` | `true` | 是否应用本组参数。`false` 时一律使用引擎默认值 |
| `renderDpi` | `int` | `200` | 渲染通道 DPI。调高提升小字精度，代价是渲染耗时与内存 |
| `minTextChars` | `int` | `20` | 文本层判定的最少字符数。低于此值视为无文本层，走渲染 + OCR |
| `minReadableRatio` | `double` | `0.90` | 文本层判定中可读字符的最低占比。低于此值视为无文本层 |
| `forceOcr` | `boolean` | `false` | 强制走 OCR 通道（跳过文本层判定）。用于「扫描件却被标了文本层」的场景 |

逐页结果见 `PdfPageResult`（`pageIndex` + `viaOcr`），引擎内部会将多页结果**平铺**返回。

> 线程数、CPU arena、GPU 加速等调优项对所有接入方式都适用，统一放在
> [根 README 的「调优建议」](../README.md#6-调优建议)。

## 9. 底层工具类

`net.dreamlu.mica.ai.ppocr.utils` 下是 numpy / cv2 的 Java 等价实现，可按需直接调用：

| 工具类 | 职责 |
|--------|------|
| `NdArrayUtils` | argmax / max / stack / pad / clip 等张量操作 |
| `BoxUtil` | `sortQuadBoxes`（阅读顺序）、最小面积外接矩形 / 四点 |
| `CropUtil` | 透视变换裁剪；**无效裁剪返回 `null`**，调用方需过滤 |
| `Offset` | pyclipper `PyclipperOffset` 等价实现（JTS `BufferOp` + `JOIN_ROUND`）；面积 / 周长为纯 Java 实现 |
| `ModelResourceLoader` | 模型 / 字典资源加载 |
| `OrtProviders` | ORT 执行 Provider 探测 |
| `PdfMagicDetector` | `%PDF-` 魔数嗅探 |
| `CollUtil` | Java 8 兼容的集合 / IO 工具（`List.of`、`Files.writeString` 等 Java 9+ API 的替代） |
| `BufferedImageUtils` | `BufferedImage` 与 OpenCV `Mat` 互转 |
