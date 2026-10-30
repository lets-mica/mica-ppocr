# mica-ppocr-solon-plugin

mica-ppocr 的 **Solon 插件**模块。引入依赖 + 配置模型路径即可用，
自动注册 `PPOcrV6Engine`、10 个结构化解析器与 `PPOcrTemplate`。

> 自动传递 `mica-ppocr-core` + `mica-ppocr-structured`，**无需重复引入**。

## 1. 引入依赖

```xml
<dependency>
    <groupId>net.dreamlu</groupId>
    <artifactId>mica-ppocr-solon-plugin</artifactId>
    <version>${mica.ppocr.version}</version>
</dependency>
```

插件通过 `META-INF/solon/solon.mica.ai.ppocr.properties` 注册（`solon.plugin` 指向
`net.dreamlu.mica.ai.ppocr.solon.integration.PpocrPlugin`），**Solon 启动时自动加载**，
业务方无需在 `app.yml` 里声明插件。

自动装配的三个配置类：

| 配置类 | 注册内容 |
|--------|----------|
| `PPOCRAutoConfiguration` | `PPOcrV6Config` + `PPOcrV6Engine` |
| `PdfAutoConfiguration` | `PdfOcrConfig`（PDF 双通道配置） |
| `StructuredParserAutoConfiguration` | 10 个解析器 + `PPOcrTemplate` |

## 2. 完整 yml 配置

**配置项与 Spring Boot Starter 完全一致**（同一套 `PPOCRProperties`，前缀 `mica.ai.ppocr`），
逐项说明见 [Starter 的配置项总表](../mica-ppocr-spring-boot-starter/README.md#21-配置项总表全量)。

Solon 端配置文件为 `app.yml`（也支持 `application.yml`）：

```yaml
mica:
  ai:
    ppocr:
      # ===== 开关：设为 false 时不注入任何 PP-OCR 功能 Bean =====
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

### 2.1 必填校验

启用插件（`enabled=true`）时，`det-model-path` / `rec-model-path` / `rec-char-dict-path`
**任一缺失都会导致启动失败**，报错信息形如：

```text
mica-ppocr 启用失败：[det-model-path] 必须配置（可在 app.yml 中设置 mica.ai.ppocr.enabled=false 关闭该 Starter）
```

### 2.2 总开关 `enabled=false` 的行为

设为 `false` 时三个配置类整体不生效（Solon 侧用 `@Condition(onExpression = "${mica.ai.ppocr.enabled:true} == true")`），
容器里不会注册任何 PP-OCR 功能 Bean，应用照常启动。

> ⚠️ **OpenCV 原生库由 `PPOcrV6Engine` 构造期加载**，开关关闭时引擎 Bean 缺席 ⇒ **OpenCV 也不会加载**。
> 业务方若自行使用 `org.opencv.*`，需自行 `nu.pattern.OpenCV.loadLocally()`。

### 2.3 构造期自动预热

`new PPOcrV6Engine(config)` 的构造器内部会跑一次预热（64×64 程序化合成笔画图跑通 det + rec），
把 ONNX Runtime 的计算图优化、内存池初始化与 JIT 提前到初始化期。
预热是引擎的**初始化语义，没有开关、也不需要业务方调用**；失败只记 WARN，不影响引擎可用性。

## 3. PPOcrTemplate API

直接注入 `PPOcrTemplate` 即可使用。

### 3.1 纯 OCR：`run(...)` × 5 种入参

返回散落文字框（不做结构化）：`run(String)` / `run(File)` / `run(Path)` / `run(byte[])` / `run(InputStream)`，
每个都配套一个 `(..., DbDetParams)` 重载用于按调用临时覆盖检测阈值。

```java
@Inject
private PPOcrTemplate ppocr;

List<PPOcrV6Result> results = ppocr.run(file.getBytes());
```

### 3.2 结构化解析：`xxxXxx().parse(...)` 链式调用

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
VehicleLicenseResult r = ppocr.vehicleLicense().parse(file.getBytes());
```

### 3.3 通用查表与声明式 schema

```java
MyCustomResult r = ppocr.get(MyCustomParser.class).parse(bytes);

OcrFieldSchema schema = OcrFieldSchema.builder("收据")
    .add().field("orderNo").labels("订单号").regex("[A-Z0-9]{8,}").add()
    .add().field("amount").labels("金额").normalize(ValueNormalizer.DIGITS).add()
    .build();
OcrStructuredResult result = ppocr.generic(schema).parse(bytes);
```

> **Solon 端 `get(Class)` 采用懒加载**：首次访问时通过 `AppContext#getBean(Class)` 实时查找并缓存，
> 而非构造期批量收集 —— 绕开 Solon 4.x 对 `List<BaseStructuredParser<?>>` 自动注入的不稳定支持，
> 也避免 `@Configuration` 内 `@Bean` 注册顺序未定导致的非确定结果。

## 4. 典型用法

```java
@Controller
public class OcrController {

    @Inject
    private PPOcrTemplate ppocr;

    @Inject
    private PPOcrV6Engine engine;   // 注入 engine，用于自定义解析器

    // 1) 上传文件（最常用）
    @Post
    @Mapping("/ocr/vehicle")
    public VehicleLicenseResult vehicle(@Body File file) throws IOException {
        return ppocr.vehicleLicense().parse(file.getBytes());
    }

    // 2) 网络流 / 对象存储下载流
    @Get
    @Mapping("/ocr/driver")
    public DriverLicenseResult driver(@Body String url) throws IOException {
        try (InputStream in = new URL(url).openStream()) {
            return ppocr.driverLicense().parse(in);
        }
    }

    // 3) 纯 OCR（只想要散落文字框）
    @Get
    @Mapping("/ocr/raw")
    public List<PPOcrV6Result> raw(@Body byte[] bytes) {
        return ppocr.run(bytes);
    }

    // 4) 自定义解析器：直接 new YourParser(engine) → parse
    public <R> R custom(Path imagePath, BaseStructuredParser<R> parser) {
        return parser.parse(imagePath);
    }
}
```

> **并发安全**：`ppocr.xxxXxx()` 每次返回新解析器实例，多线程共享同一个 `PPOcrTemplate` 是安全的；
> `PPOcrV6Engine` 内部 ONNX session 是线程安全的（ORT 保证）。

## 5. 配置覆盖（环境变量 / 配置中心）

```java
@Component
public class TierCustomizer implements PPOCRPropertiesCustomizer {
    @Override
    public void customize(PPOcrV6Config.PPOcrV6ConfigBuilder builder) {
        String tier = System.getenv("PPOCR_TIER");
        if (tier != null) {
            builder.detModelPath("models/ppocr-v6/" + tier + "/det.onnx")
                   .recModelPath("models/ppocr-v6/" + tier + "/rec.onnx")
                   .recCharDictPath("models/ppocr-v6/" + tier + "/dict.txt");
        }
    }
}
```

> ⚠️ **排序说明**：Solon 4.x **没有** `@Order` 注解（那是 Spring 的），多个 customizer 的生效顺序
> 由容器注入 `List` 的顺序决定，**当前不保证可排序**。需要确定顺序时请自行合并为单个 customizer。
>
> ⚠️ **执行顺序说明**：自动配置是「先校验必填项 → 再应用 customizer」，
> 因此三个模型路径**必须先在 yml 里填上可读的占位值**，否则启动时就在校验阶段失败，customizer 不会执行。

PDF 通道配置也可以直接用 Bean 覆盖：

```java
@Configuration
public class MyPdfConfig {
    @Bean
    public PdfOcrConfig pdfOcrConfig() {
        return PdfOcrConfig.builder()
            .renderDpi(300)
            .minTextChars(30)
            .build();
    }
}
```

## 6. 与 Spring Boot Starter 的差异

| 维度 | Solon 插件 | Spring Boot Starter |
|------|-----------|--------------------|
| 接入方式 | 依赖 + yml，插件自动加载 | 依赖 + yml，自动装配 |
| 配置文件 | `app.yml` | `application.yml` |
| 注入注解 | `@Inject` | `@Autowired` |
| 解析器查找 | `get(Class)` 懒加载（`AppContext#getBean`） | 构造期由 `ApplicationContext` 收集并建索引 |
| customizer 排序 | ❌ 无 `@Order`，顺序不保证 | ✅ 支持 `@Order` |
| 自定义 Bean 覆盖 | `@Configuration` + `@Bean` | `@Configuration` + `@Bean` |

> ⚠️ **Solon 端 `get(Class)` 没有 AOP 代理问题**：按声明类型 `AppContext#getBean(Class)` 查找，
> 不像 Spring 端那样受 `Xxx$$SpringCGLIB$$0` 运行时类影响。

## 7. 调优

CPU 线程数、CPU arena、GPU 加速的完整说明见
[根 README 的「调优建议」](../README.md#6-调优建议)。
