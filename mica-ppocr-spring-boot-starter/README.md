# mica-ppocr-spring-boot-starter

mica-ppocr 的 **Spring Boot 自动装配**模块。引入依赖 + 配置模型路径即可用，
自动注册 `PPOcrV6Engine`、10 个结构化解析器与 `PPOcrTemplate`。

> 自动传递 `mica-ppocr-core` + `mica-ppocr-structured`，**无需重复引入**。

## 1. 引入依赖

```xml
<dependency>
    <groupId>net.dreamlu</groupId>
    <artifactId>mica-ppocr-spring-boot-starter</artifactId>
    <version>${mica.ppocr.version}</version>
</dependency>
```

自动装配由 `mica-auto` 注解处理器在编译期生成注册文件完成，同时覆盖
Spring Boot 2.5~2.7（`META-INF/spring.factories`）与 2.7+ / 3.x
（`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`），
**业务方无需 `@Import` 任何配置类**。

自动装配的三个配置类：

| 配置类 | 注册内容 |
|--------|----------|
| `PPOCRAutoConfiguration` | `PPOcrV6Config` + `PPOcrV6Engine` |
| `PdfAutoConfiguration` | `PdfOcrConfig`（PDF 双通道配置） |
| `StructuredParserAutoConfiguration` | 10 个解析器 + `PPOcrTemplate` |

## 2. 完整 yml 配置

**下表与 §3 的配置项总表即为全量配置**，无遗漏项。配置前缀 `mica.ai.ppocr`。

```yaml
mica:
  ai:
    ppocr:
      # ===== 开关：设为 false 时不注入任何 PP-OCR 功能 Bean（引擎 / 解析器 / 模板 / 配置）=====
      # enabled: true                                         # 默认 true
      # ===== 必填：三个模型文件路径 =====
      det-model-path: models/ppocr-v6/tiny/det.onnx          # 检测模型
      rec-model-path: models/ppocr-v6/tiny/rec.onnx          # 识别模型
      rec-char-dict-path: models/ppocr-v6/tiny/dict.txt      # 识别字符字典
      # ===== 检测（DB 后处理）参数 =====
      # det-limit-side-len: 64                                # 检测限制边长（默认 64，证件类场景推荐）
      # det-limit-type: min                                   # 限制类型：min / max（默认 min，限短边）
      # det-max-side-limit: 4000                              # 检测最大边长限制
      # det-thresh: 0.3                                       # 检测像素阈值
      # det-box-thresh: 0.6                                   # 检测框阈值
      # det-unclip-ratio: 1.5                                 # 多边形 unclip 比例
      # ===== 识别参数 =====
      # rec-image-shape: [3, 48, 320]                         # 识别输入 shape [C, H, W]
      # rec-batch-size: 6                                     # 识别批处理大小
      # ===== 可选：文档方向分类（PP-LCNet_x1_0_doc_ori）=====
      # use-doc-orientation-classify: false                   # 是否启用整图 4 方向分类 + 自动旋转
      # doc-orientation-model-path: models/ppocr-v6/doc_ori/doc_ori.onnx  # 启用时必填
      # doc-orientation-thresh: 0.4                           # 置信度阈值 < 此值视为 0°；实测 0.4 是误判丢弃 / 误判旋转的最佳折中
      # ===== 性能 / 运行模式 =====
      # prefer-accelerator: false                             # 是否优先 GPU（默认 false 强制 CPU，保证 bit-exact）
      # intra-op-num-threads: 1                               # ONNX 单算子内并行线程数；推荐 = 物理核数
      # inter-op-num-threads: 1                               # 默认 1，OCR 流水线严格串行，interOp 设大也没用
      # exec-mode: sequential                                 # ONNX 执行模式：sequential / parallel（默认 sequential）
      # enable-cpu-mem-arena: false                           # CPU arena 高水位不归还 OS，动态分辨率下内存持续增长，默认关闭（issue #14）
      # enable-memory-pattern: false                          # 内存模式优化，shape 变化时同样吃内存，默认关闭
      # ===== PDF 双通道（可选）=====
      # pdf:
      #   enabled: true                                       # 是否应用以下 pdf 参数（false = 一律用引擎默认值）
      #   render-dpi: 200                                     # 渲染通道 DPI
      #   min-text-chars: 20                                  # 文本层最少字符数
      #   min-readable-ratio: 0.90                            # 文本层可读字符占比下限
      #   force-ocr: false                                    # 强制所有页走渲染 + OCR
```

### 2.1 配置项总表（全量）

配置前缀统一为 `mica.ai.ppocr`，下表列出**完整配置项路径**（可直接用于 yml / 环境变量 / 配置中心）。

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `mica.ai.ppocr.enabled` | boolean | `true` | **总开关**。`false` 时三个自动配置类整体不生效，容器不注入任何 PP-OCR Bean |
| `mica.ai.ppocr.det-model-path` | String | — | **必填**，检测模型路径 |
| `mica.ai.ppocr.rec-model-path` | String | — | **必填**，识别模型路径 |
| `mica.ai.ppocr.rec-char-dict-path` | String | — | **必填**，识别字符字典路径 |
| `mica.ai.ppocr.det-limit-side-len` | int | `64` | 检测限制边长。与 `det-limit-type` 配合：长/短边超过此值时按比例缩放。默认组合 `64 + min` 面向证件/卡证类小图，保证短边至少 64 像素；通用文档/自然场景可改较大值 + `max` |
| `mica.ai.ppocr.det-limit-type` | String | `min` | 限制类型：`min`（短边限制）/ `max`（长边限制） |
| `mica.ai.ppocr.det-max-side-limit` | int | `4000` | 检测最大边长限制 |
| `mica.ai.ppocr.det-thresh` | float | `0.3` | 检测像素阈值 |
| `mica.ai.ppocr.det-box-thresh` | float | `0.6` | 检测框阈值 |
| `mica.ai.ppocr.det-unclip-ratio` | float | `1.5` | 多边形 unclip 比例 |
| `mica.ai.ppocr.rec-image-shape` | int[] | `[3, 48, 320]` | 识别输入 shape `[C, H, W]`。⚠️ 引擎**只取 `H` 段**，识别宽度的分桶上下限（320 ~ 3200）写死在引擎构造处，改 `W` 不影响运行行为 |
| `mica.ai.ppocr.rec-batch-size` | int | `6` | 识别批处理大小 |
| `mica.ai.ppocr.prefer-accelerator` | boolean | `false` | 是否优先 GPU。默认 `false` 强制 CPU，保证跨平台 bit-exact |
| `mica.ai.ppocr.use-doc-orientation-classify` | boolean | `false` | 是否启用整图 4 方向分类 + 自动旋转（`PP-LCNet_x1_0_doc_ori`） |
| `mica.ai.ppocr.doc-orientation-model-path` | String | — | 方向分类模型路径，**`use-doc-orientation-classify=true` 时必填** |
| `mica.ai.ppocr.doc-orientation-thresh` | float | `0.4` | 置信度阈值，低于此值视为 0°（不旋转）。范围 `[0, 1]` |
| `mica.ai.ppocr.intra-op-num-threads` | int | `1` | ONNX 单算子内并行线程数，**推荐 = 物理核数** |
| `mica.ai.ppocr.inter-op-num-threads` | int | `1` | ONNX 交互线程数。OCR 流水线严格串行，设大也没用 |
| `mica.ai.ppocr.exec-mode` | String | `sequential` | ONNX 执行模式：`sequential`（低内存）/ `parallel`（需 `inter-op-num-threads > 1`，吞吐更高、内存更高） |
| `mica.ai.ppocr.enable-cpu-mem-arena` | boolean | `false` | CPU memory arena。高水位不归还 OS，动态分辨率下内存持续增长直至 OOM（[issue #14](https://github.com/lets-mica/mica-ppocr/issues/14)），默认关闭（吞吐约损 10%） |
| `mica.ai.ppocr.enable-memory-pattern` | boolean | `false` | 内存模式优化。shape 变化时同样吃内存，建议与 `enable-cpu-mem-arena` 成对开启或成对关闭 |
| `mica.ai.ppocr.pdf.enabled` | boolean | `true` | 是否应用以下 pdf 参数。`false` 时引擎一律使用默认值 |
| `mica.ai.ppocr.pdf.render-dpi` | int | `200` | 渲染通道 DPI。调高提升小字精度，代价是渲染耗时与内存 |
| `mica.ai.ppocr.pdf.min-text-chars` | int | `20` | 文本层判定的最少字符数。低于此值视为无文本层，走「渲染 + OCR」 |
| `mica.ai.ppocr.pdf.min-readable-ratio` | double | `0.90` | 文本层判定中可读字符的最低占比。低于此值视为无文本层 |
| `mica.ai.ppocr.pdf.force-ocr` | boolean | `false` | 强制走 OCR 通道（跳过文本层判定）。用于「扫描件却被标了文本层」的场景 |

### 2.2 必填校验

启用 Starter（`enabled=true`）时，`det-model-path` / `rec-model-path` / `rec-char-dict-path`
**任一缺失都会导致启动失败**，报错信息形如：

```text
mica-ppocr 启用失败：[det-model-path] 必须配置（可在 application.yml 中设置 mica.ai.ppocr.enabled=false 关闭该 Starter）
```

### 2.3 总开关 `enabled=false` 的行为

设为 `false` 时，`PPOCRAutoConfiguration` / `PdfAutoConfiguration` / `StructuredParserAutoConfiguration`
**三个配置类整体不生效**，容器里不会注册任何 PP-OCR 功能 Bean（引擎 / 10 个解析器 / `PPOcrTemplate` / 配置对象），
应用照常启动 —— 可用于「同一套代码在部分环境不启用 OCR」的场景。

> ⚠️ **OpenCV 原生库由 `PPOcrV6Engine` 构造期加载**，开关关闭时引擎 Bean 缺席 ⇒ **OpenCV 也不会加载**。
> 业务方若自行使用 `org.opencv.*`，需自行 `nu.pattern.OpenCV.loadLocally()`。

### 2.4 构造期自动预热

`new PPOcrV6Engine(config)` 的构造器内部会跑一次预热 —— 用一张 64×64 的程序化合成笔画图
跑通 det + rec 全链路，把 ONNX Runtime 的计算图优化、内存池初始化与 JIT 提前到初始化期 ——
消除「容器刚起来时第一个请求显著慢于后续」。

实测（tiny 档，独立 JVM 各 9 次采样）：首调惩罚 113ms → 23ms，首个请求 688ms → 615ms；
初始化期代价 tiny 约 60ms / small 约 90ms。

预热是引擎的**初始化语义，没有开关、也不需要业务方调用**；预热失败只记 WARN，不影响引擎可用性。

### 2.5 PDF 双通道配置已接通

`mica.ai.ppocr.pdf.*` 会经 `PPOcrV6Config` 传到 `PPOcrV6Engine`，由引擎的 PDF 入口
（`run(byte[])` / `run(Path)` 嗅探到 `%PDF-` 时）实际消费，影响**文本层可用性判定**与**渲染通道 DPI**；
图片通道不受影响。

`pdf.enabled=false` 时引擎一律使用默认值（200 / 20 / 0.90 / false），两端默认值与
`PdfOcrConfig.defaults()` 保持一致 —— 不配置即保持原有行为。

也可以用代码覆盖：自定义一个 `PdfOcrConfig` Bean 即可（Spring `@Bean`），
**引擎会直接采用它**，无需再改 yml。

## 3. PPOcrTemplate API

直接注入 [`PPOcrTemplate`](../mica-ppocr-spring-boot-starter/src/main/java/net/dreamlu/mica/ai/ppocr/autoconfigure/PPOcrTemplate.java) 即可使用。

`StructuredParserAutoConfiguration` 会先注册 10 个内置解析器与 `PPOcrV6Engine`，
再把 10 个解析器注入 `PPOcrTemplate`（仅当 `PPOcrV6Engine` 存在时才创建模板，避免未配置模型时启动失败）。

### 3.1 纯 OCR：`run(...)` × 5 种入参

返回散落文字框（不做结构化）：

| 入参 | 场景 |
|------|------|
| `run(String)` | 本地文件路径 |
| `run(File)` | `File` 对象 |
| `run(Path)` | 非默认文件系统（ZIP / JIMFS / 内存 FS） |
| `run(byte[])` | Spring `MultipartFile.getBytes()` |
| `run(InputStream)` | URL / S3 / HTTP 下载流 |

每个 `run` 配套一个 `(..., DbDetParams)` 重载，用于按调用临时覆盖检测阈值
（`thresh` / `boxThresh` / `unclipRatio` / `maxCandidates` / `minSize`），**不修改引擎共享状态，线程安全**。

```java
List<PPOcrV6Result> results = ppocr.run(file.getBytes());
```

### 3.2 结构化解析：`xxxXxx().parse(...)` 链式调用

模板暴露 10 个 getter，每个返回**自动配置注入的解析器单例**（已绑定 engine）。
结构化解析统一走解析器自身的 `parse(...)`（同样 5 种入参 × 带/不带 `DbDetParams`）：

| getter | 解析器 | 结果类型 |
|--------|--------|----------|
| `vehicleLicense()` | `VehicleLicenseParser` | `VehicleLicenseResult` |
| `idCard()` | `IdCardParser`（正反面自动判定） | `IdCardResult` |
| `bankCard()` | `BankCardParser` | `BankCardResult` |
| `driverLicense()` | `DriverLicenseParser` | `DriverLicenseResult` |
| `businessLicense()` | `BusinessLicenseParser` | `BusinessLicenseResult` |
| `invoice()` | `InvoiceParser` | `InvoiceResult` |
| `trainTicket()` | `TrainTicketParser` | `TrainTicketResult` |
| `taxiReceipt()` | `TaxiReceiptParser` | `TaxiReceiptResult` |
| `householdRegister()` | `HouseholdRegisterParser` | `HouseholdRegisterResult` |
| `pddLuckyBag()` | `PddLuckyBagParser` | `PddLuckyBagResult` |

```java
// 链式写法：template 拿解析器 → 解析器跑 parse
VehicleLicenseResult r = ppocrTemplate.vehicleLicense().parse(file.getBytes());
IdCardResult         i = ppocrTemplate.idCard().parse(path);
DriverLicenseResult  d = ppocrTemplate.driverLicense().parse(inputStream);
```

### 3.3 通用查表与声明式 schema

```java
// 通用查表：按类型取已注册的解析器（自定义解析器或不想加 getter 时使用）
MyCustomResult r = ppocr.get(MyCustomParser.class).parse(bytes);

// 声明式 schema：新增单据类型无需再实现解析器类
OcrFieldSchema schema = OcrFieldSchema.builder("收据")
    .add().field("orderNo").labels("订单号").regex("[A-Z0-9]{8,}").add()
    .add().field("amount").labels("金额").normalize(ValueNormalizer.DIGITS).add()
    .build();
OcrStructuredResult result = ppocr.generic(schema).parse(bytes);
```

> ⚠️ `get(Class)` 按 `parser.getClass()` 建索引。解析器若被 Spring AOP 代理（CGLIB/JDK），
> 运行时类会变成 `Xxx$$SpringCGLIB$$0`，此时 `get(Xxx.class)` 会抛「未注册解析器」——
> 需要按声明类型查找，或改用类型专用的 getter。

> 这 10 个解析器都是 Bean，可在自己的 `@Configuration` 里覆盖同名 Bean
> （`@ConditionalOnMissingBean` 会让自定义生效），`PPOcrTemplate` 会自动注入你提供的实例。

## 4. 典型用法

```java
@Service
public class OcrService {
    @Autowired
    private PPOcrTemplate ppocr;
    @Autowired
    private PPOcrV6Engine engine;   // 注入 engine，用于自定义解析器

    // 1) Spring Boot 上传（最常用）：template 拿解析器 → parse
    public VehicleLicenseResult recognizeVehicle(MultipartFile file) throws IOException {
        return ppocr.vehicleLicense().parse(file.getBytes());
    }

    // 2) 网络流 / S3 下载流
    public DriverLicenseResult recognizeDriver(URL url) throws IOException {
        try (InputStream in = url.openStream()) {
            return ppocr.driverLicense().parse(in);
        }
    }

    // 3) 纯 OCR（只想要散落文字框，不做结构化）
    public List<PPOcrV6Result> recognizeRaw(byte[] imgBytes) throws IOException {
        return ppocr.run(imgBytes);
    }

    // 4) 自定义解析器场景：直接 new YourParser(engine) → parse
    public <R> R recognizeCustom(Path imagePath, BaseStructuredParser<R> parser) {
        return parser.parse(imagePath);   // parser 已自行持有 engine，无需走 template
    }
}
```

> **并发安全**：`ppocr.xxxXxx()` 每次返回新解析器实例，多线程共享同一个 `PPOcrTemplate` 是安全的；
> `PPOcrV6Engine` 内部 ONNX session 是线程安全的（ORT 保证）。

## 5. 配置覆盖（环境变量 / 配置中心）

业务方可通过 `PPOCRPropertiesCustomizer` 对配置做旁路覆盖，常用于按环境切换 `tiny / small / medium`：

```java
@Bean
public PPOCRPropertiesCustomizer tierEnvCustomizer() {
    return builder -> {
        String tier = System.getenv("PPOCR_TIER");
        if (tier != null) {
            builder.detModelPath("models/ppocr-v6/" + tier + "/det.onnx")
                   .recModelPath("models/ppocr-v6/" + tier + "/rec.onnx")
                   .recCharDictPath("models/ppocr-v6/" + tier + "/dict.txt");
        }
    };
}
```

多个 customizer 时用 `@Order` 显式控制顺序。

> ⚠️ **执行顺序说明**：自动配置是「先校验必填项 → 再应用 customizer」，
> 因此 `det-model-path` / `rec-model-path` / `rec-char-dict-path`
> **必须先在 yml 里填上可读的占位值**，否则启动时就在校验阶段失败，customizer 不会执行。

PDF 通道配置也可以直接用 Bean 覆盖：

```java
@Bean
public PdfOcrConfig pdfOcrConfig() {
    return PdfOcrConfig.builder()
        .renderDpi(300)
        .minTextChars(30)
        .build();
}
```

## 6. 可视化（rawResults + fieldBoxes）

```java
VehicleLicenseResult r = ppocr.vehicleLicense().parse(file.getBytes());

// 画所有文字框（绿线）
for (PPOcrV6Result ocr : r.getRawResults()) {
    drawPolyline(ocr.box(), Color.GREEN);
}
// 高亮车牌字段（红线）
List<int[][]> plateBoxes = r.getFieldBoxes().get("plateNo");
if (plateBoxes != null) {
    for (int[][] box : plateBoxes) drawPolyline(box, Color.RED);
}
```

> ⚠️ `fieldBoxes` 并非所有解析器都回填：`invoice` / `business` / `vehicle` / `household` /
> `pdd` / `train` / `taxi` **会回填**；`idcard` / `driver` / `bankcard` 不回填（空 map）。

## 7. 调优

CPU 线程数、CPU arena、GPU 加速的完整说明见
[根 README 的「调优建议」](../README.md#6-调优建议)。
