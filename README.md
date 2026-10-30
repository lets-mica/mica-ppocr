# mica-ppocr（Java 图片 OCR 识别）

[![Java CI](https://github.com/lets-mica/mica-ppocr/actions/workflows/test-and-build.yml/badge.svg)](https://github.com/lets-mica/mica-ppocr/actions/workflows/test-and-build.yml)
![JAVA 8](https://img.shields.io/badge/JDK-8+-brightgreen.svg)
[![Mica Maven release](https://img.shields.io/maven-central/v/net.dreamlu/mica-ppocr-core.svg?style=flat-square)](https://central.sonatype.com/artifact/net.dreamlu/mica-ppocr-core/versions)
![Mica Maven SNAPSHOT](https://img.shields.io/maven-metadata/v?metadataUrl=https://central.sonatype.com/repository/maven-snapshots/net/dreamlu/mica-ppocr-core/maven-metadata.xml)

> - PP-OCRv6 文字检测 + 识别的 **Java** 实现，纯 ONNX Runtime 推理，**零 PaddlePaddle 依赖**。
> - doc_ori 文档方向分类模型，支持（4 类：0°/90°/180°/270°）在检测前对整图做方向校正，避免用户侧倒拍/横拍导致识别失败。

[✨✨✨推广：**BladeX 物联网平台**✨✨✨iot.bladex.cn](https://iot.bladex.cn?from=mica-mqtt)

---

## 1. 特性

| 能力 | 说明 |
|------|------|
| 文字检测 + 识别 | PP-OCRv6 det + rec，完整复现 DB 后处理 / CTC 贪心解码 |
| 零 Paddle 依赖 | 纯 ONNX Runtime 推理，Java 8 兼容，Windows / Linux / macOS 原生库随 Maven 引入 |
| 结构化解析 | 行驶证 / 身份证 / 银行卡 / 驾驶证 / 营业执照 / 发票 / 火车票 / 出租车票 / 户口本 / 拼多多福袋 共 10 类 |
| 声明式 schema | 新增单据类型写一份 schema 即可抽字段，无需再实现解析器类 |
| PDF 双通道 | 文字型 PDF 直接抽文本层，扫描件自动降级为渲染 + OCR |
| 方向校正 | doc_ori 模型 4 方向分类，检测前整图旋转 |
| 字段级置信度 | 校验位三态（PASS / FAIL / UNCHECKABLE）+ 人工复核出口 |
| 框架接入 | Spring Boot Starter（自动装配）与 Solon Plugin |

## 2. 环境和依赖

| 组件           | 版本    | 说明                       |
|--------------|-------|--------------------------|
| JDK          | 8     | java 8 或以上版本             |
| ONNX Runtime | 1.18.0 | 此版本内置的原生库可兼容更多操作系统版本     |
| OpenCV       | 4.9.0-0 | 含 Windows/Linux/macOS 原生库 |
| JTS          | 1.20.0 | 多边形偏移（pyclipper 等价物）     |
| PDFBox       | 3.0.8  | PDF 双通道（文本层抽取 + 渲染兜底）  |

## 3. 模型目录

模型**已随仓库入库**（`models/ppocr-v6/`），clone 后无需额外下载即可运行：

| 档次 | det 模型 | rec 模型 | 字符表 | 定位 |
|------|----------|----------|--------|------|
| `tiny` | 1.7 MB | 4.3 MB | 约 2855 字符 | 轻量优先，速度快，精度一般 |
| `small` | 9.4 MB | 20.2 MB | 约 2855 字符 | 速度与精度均衡，推荐默认 |
| `medium` | 59.2 MB | 73.0 MB | 约 7180 字符 | 精度优先，覆盖更全字符集 |

> `medium` 的 det/rec 模型以 `.onnx.zip` 形式入库，**需解压为 `det.onnx` / `rec.onnx` 后使用**。

**可选**：文档方向分类模型 `models/ppocr-v6/doc_ori/doc_ori.onnx`（`PP-LCNet_x1_0_doc_ori` 的 ONNX 导出，6.47 MB）。

![模型评分](docs/images/v6acc_opt.png)

## 4. Maven 依赖

按使用场景选一个即可：

| 场景 | 依赖 |
|------|------|
| Spring Boot 应用 | `mica-ppocr-spring-boot-starter`（自动传递 core + structured） |
| Solon 应用 | `mica-ppocr-solon-plugin`（自动传递 core + structured） |
| 纯 Java / 其他框架 | `mica-ppocr-core`（纯 OCR）+ `mica-ppocr-structured`（结构化解析，可选） |

版本号请以 [Maven Central](https://central.sonatype.com/artifact/net.dreamlu/mica-ppocr-core/versions) 上的最新发行版为准（下方 `${mica.ppocr.version}` 替换为实际版本号）。

**Spring Boot Starter**

```xml
<dependency>
    <groupId>net.dreamlu</groupId>
    <artifactId>mica-ppocr-spring-boot-starter</artifactId>
    <version>${mica.ppocr.version}</version>
</dependency>
```

**Solon 插件**

```xml
<dependency>
    <groupId>net.dreamlu</groupId>
    <artifactId>mica-ppocr-solon-plugin</artifactId>
    <version>${mica.ppocr.version}</version>
</dependency>
```

**核心引擎（纯 OCR）**

```xml
<dependency>
    <groupId>net.dreamlu</groupId>
    <artifactId>mica-ppocr-core</artifactId>
    <version>${mica.ppocr.version}</version>
</dependency>
```

**结构化解析（证件 / 票据 → 业务字段）**

```xml
<dependency>
    <groupId>net.dreamlu</groupId>
    <artifactId>mica-ppocr-structured</artifactId>
    <version>${mica.ppocr.version}</version>
</dependency>
```

## 5. 快速开始

**Spring Boot**（一行：检测 → 识别 → 结构化）

```java
@Autowired
private PPOcrTemplate ppocr;

@PostMapping("/ocr/vehicle")
public VehicleLicenseResult vehicle(@RequestParam MultipartFile file) throws IOException {
    return ppocr.vehicleLicense().parse(file.getBytes());
}
```

**纯 Java**

```java
PPOcrV6Config config = PPOcrV6Config.builder()
    .detModelPath("models/ppocr-v6/tiny/det.onnx")
    .recModelPath("models/ppocr-v6/tiny/rec.onnx")
    .recCharDictPath("models/ppocr-v6/tiny/dict.txt")
    .build();
try (PPOcrV6Engine engine = new PPOcrV6Engine(config)) {
    List<PPOcrV6Result> results = engine.run("test_images/vehicle/vehicle1.png");
    for (PPOcrV6Result r : results) {
        System.out.printf("%s  (%.3f)%n", r.text(), r.score());
    }
}
```

## 6. 调优建议

调优项对**所有接入方式**（纯 Java / Spring Boot Starter / Solon 插件）都适用 ——
配置项名称与 `PPOcrV6Config` 参数表一一对应。

### 6.1 CPU 线程数

`intra-op-num-threads`：ONNX 单算子内 OpenMP 并行线程数，建议设置为 CPU 物理核数。

| CPU | 物理核 | 逻辑核（含超线程） | 推荐值 | 说明 |
|-----|--------|------------------|--------|------|
| 主流 8 核 | 8 | 16 | 8 | R7-5800H/6800H、i7-11800H 等 |
| 高端笔记本 | 14 | 20 | 14 | i7-12700H / i9-13900H（P 核） |
| 服务器 | 16~64 | 32~128 | 16~64 | Xeon / EPYC，按物理核数取 |

### 6.2 CPU arena

CPU arena（`enable-cpu-mem-arena`）+ 内存模式优化（`enable-memory-pattern`）在高水位下不归还 OS，
动态分辨率场景内存随历史最大图持续增长直至 OOM（[issue #14](https://github.com/lets-mica/mica-ppocr/issues/14)），
故 **v1.2.0 起默认关闭**（吞吐约损 10%）。输入分辨率固定且追求极致吞吐时可开启，
注意容器内存需按历史最大图预留：

```yaml
mica:
  ai:
    ppocr:
      enable-cpu-mem-arena: true    # 输入分辨率固定 + 追求极致吞吐时开启
      enable-memory-pattern: true   # 与上面成对开启
```

### 6.3 GPU 加速

1. **切换 GPU 包**：在 `mica-ppocr-core/pom.xml` 把 `onnxruntime` 替换为 `onnxruntime_gpu`
   （版本对齐 ONNX Runtime 官方发布），并确保主机已安装匹配的 CUDA / cuDNN。
2. **开启加速**：`PPOcrV6Config.builder().preferAccelerator(true)`，
   或在 `application.yml` 中设 `mica.ai.ppocr.prefer-accelerator: true`。
   Provider 选择由 `OrtProviders` 完成：macOS 优先 `CoreMLExecutionProvider`，
   否则 `CUDAExecutionProvider`，都不可用时回退 CPU。
3. **线程数**：GPU 模式下 `intra-op-num-threads` 设为 `1`（甚至 `0` 让 ORT 自管），
   把并行让给 GPU 自己的 stream；`inter-op-num-threads` 保持默认 `1`。

## 7. 模块文档

各模块的详细用法、完整参数表与配置项说明：

| 模块 | 文档 | 内容 |
|------|------|------|
| `mica-ppocr-core` | [README](mica-ppocr-core/README.md) | 引擎 API 清单、`PPOcrV6Config` 完整参数表、PDF 双通道、方向分类、生命周期与并发 |
| `mica-ppocr-structured` | [README](mica-ppocr-structured/README.md) | 10 类解析器与字段清单、结果结构与字段置信度、自定义解析器、声明式 schema、本地调试 |
| `mica-ppocr-spring-boot-starter` | [README](mica-ppocr-spring-boot-starter/README.md) | **全量 yml 配置项**、`PPOcrTemplate` API、配置覆盖、可视化 |
| `mica-ppocr-solon-plugin` | [README](mica-ppocr-solon-plugin/README.md) | Solon 接入方式、yml 配置、`PPOcrTemplate` API、与 Starter 的差异 |

## 8. 许可证

Apache License Version 2.0

## 9. 微信

![如梦技术](docs/images/dreamlu-weixin.jpg)

**JAVA架构日记**，精彩内容每日推荐！
