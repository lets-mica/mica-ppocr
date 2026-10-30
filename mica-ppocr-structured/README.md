# mica-ppocr-structured

在 [`mica-ppocr-core`](../mica-ppocr-core/README.md) 的 OCR 文本框之上，把散落的文字**组织成业务字段**。

提供 10 类证件 / 票据的结构化解析器，以及一套声明式 schema 入口（新增单据类型写一份 schema 即可抽字段）。

## 1. 引入依赖

```xml
<dependency>
    <groupId>net.dreamlu</groupId>
    <artifactId>mica-ppocr-structured</artifactId>
    <version>${mica.ppocr.version}</version>
</dependency>
```

> 本模块依赖 `mica-ppocr-core`（自动传递）。Spring Boot Starter / Solon Plugin 已包含本模块，无需重复引入。

## 2. 已实现的解析器

| 解析器 | 解析类 | 结果类型 | 关键字段 |
|--------|--------|----------|----------|
| 行驶证 | `VehicleLicenseParser` | `VehicleLicenseResult` | `plateNo` / `owner` / `vehicleType` / `vin` / `issueDate` |
| 身份证（正反面自动判定） | `IdCardParser` | `IdCardResult` | `side` / `name` / `gender` / `nation` / `birthDate` / `address` / `idNumber` / `issuingAuthority` / `validFrom` / `validTo` |
| 银行卡 | `BankCardParser` | `BankCardResult` | `cardNumber` / `validDate` / `holderName` / `bankName` / `cardType` |
| 机动车驾驶证 | `DriverLicenseParser` | `DriverLicenseResult` | `licenseNumber` / `name` / `gender` / `nationality` / `address` / `birthDate` / `issueDate` / `vehicleClass` / `issuingAuthority` / `validFrom` / `validTo` |
| 营业执照 | `BusinessLicenseParser` | `BusinessLicenseResult` | `creditCode` / `name` / `type` / `legalPerson` / `registeredCapital` / `establishDate` / `operatingPeriod` / `address` / `businessScope` |
| 发票（新版电子 / 老版增值税自动分发） | `InvoiceParser` | `InvoiceResult` | `version` / `invoiceCode` / `invoiceNo` / `invoiceDate` / `buyerName` / `sellerName` / `items` / `totalAmountUpper` / `totalAmountLower` 等 |
| 火车票 | `TrainTicketParser` | `TrainTicketResult` | `departure` / `arrival` / `trainNumber` / `departureDate` / `departureTime` / `seatNumber` / `seatClass` / `passengerName` / `idNumber` / `amount` 等 |
| 出租车票 | `TaxiReceiptParser` | `TaxiReceiptResult` | `invoiceCode` / `invoiceNo` / `plateNumber` / `date` / `boardingTime` / `alightingTime` / `mileage` / `amount` / `fuelSurcharge` / `totalAmount` / `city` 等 |
| 户口本（常住人口登记卡） | `HouseholdRegisterParser` | `HouseholdRegisterResult` | `householdNo` / `name` / `relationship` / `gender` / `birthPlace` / `ethnicity` / `nativePlace` 等 |
| 拼多多福袋（8 位邀请码 + 类型） | `PddLuckyBagParser` | `PddLuckyBagResult` | `luckyBagCode` / `type` |

> 发票有两个子解析器：`VatInvoiceParser`（老版增值税发票）与 `ElectronicInvoiceParser`（新版电子发票）。
> `InvoiceParser` 是**分发器**，自动判别版本并转交；子解析器不对外暴露为独立 Bean。
>
> 拼多多福袋有两种形态 —— **福袋券**（`PddLuckyBagType.LUCKY_BAG`，「百亿补贴 抽福袋」）与
> **水果券**（`PddLuckyBagType.FRUIT`，「百亿补贴 水果分享券」），版式一致，按标题 / 口号 / 背景水印文案区分；
> 文案缺失时为 `PddLuckyBagType.UNKNOWN`。

## 3. 调用方式

每个解析器都继承 `BaseStructuredParser<R>`，提供 **10 个 `parse(...)` 重载**（5 种入参 × 带/不带 `DbDetParams`）：

| 入参 | `parse(...)` | `parse(..., DbDetParams)` |
|------|--------------|--------------------------|
| `String` 路径 | `parse(String)` | `parse(String, DbDetParams)` |
| `File` | `parse(File)` | `parse(File, DbDetParams)` |
| `Path` | `parse(Path)` | `parse(Path, DbDetParams)` |
| `byte[]` | `parse(byte[])` | `parse(byte[], DbDetParams)` |
| `InputStream` | `parse(InputStream)` | `parse(InputStream, DbDetParams)` |

`parse(...)` 是**一站式**的：内部先跑 OCR 推理，再调 `parseResults(...)` 做结构化。

```java
VehicleLicenseParser parser = new VehicleLicenseParser(engine);
VehicleLicenseResult result = parser.parse(file.getBytes());
```

另有两类调用形式：

- **SPI 形式**：实现/继承 `BaseStructuredParser<R>`，重写 `parseResults(List<PPOcrV6Result>)`。
  已有 OCR 结果时直接调 `parseResults(results)`，不重复推理。
- **静态形式**：部分解析器另提供静态 `parse(List<PPOcrV6Result>)`，拿到 OCR 结果后直接调。

> `DbDetParams` 为 `null` 时使用引擎默认参数；非 `null` 时按本次调用临时覆盖检测阈值，
> **不修改引擎共享状态，多线程安全**。
> 解析器也可通过覆写 `detParams()` 钩子声明**按版面类型的专属 DB 参数**
> （如 `PddLuckyBagParser` 为海报类大字号稀疏数字放宽 `boxThresh`、收紧 `unclipRatio`）。

## 4. 结果结构

所有结构化结果统一继承 `BaseStructuredResult`：

| 字段 | 类型 | 说明 |
|------|------|------|
| `rawResults` | `List<PPOcrV6Result>` | 完整原始 OCR 结果（每个文字框的文本 / 置信度 / 四角坐标），可直接在页面上绘制全部文字框 |
| `fieldBoxes` | `Map<String, List<int[][]>>` | 字段名 → 该字段对应的 OCR 框坐标列表（一个字段可能跨多个框），便于高亮「这个字段来自画面哪几块」 |
| `fieldConfidences` | `Map<String, FieldConfidence>` | 字段级置信度，见 §4.1 |
| `needsReview` | `boolean` | 是否建议人工复核 |
| `reviewReasons` | `List<String>` | 复核原因 |

> ⚠️ **`fieldBoxes` 并非所有解析器都回填**：`invoice`（含 Vat / Electronic）/ `business` / `vehicle` /
> `household` / `pdd` / `train` / `taxi` **会回填**；`idcard` / `driver` / `bankcard` 三个解析器
> 走丢弃 box 的文本版 `LabelMatcher.matchValue(...)`，`fieldBoxes` 为空 map。

### 4.1 字段级置信度

`FieldConfidence` 描述单个字段的可信程度：

| 字段 | 类型 | 说明 |
|------|------|------|
| `field` | `String` | 字段名（与结果类属性名一致） |
| `value` | `String` | 字段值；未取到时为 `null` |
| `score` | `double` | 综合置信度 `[0, 1]`，越大越可信 |
| `ocrScore` | `Float` | OCR 原始置信度（值框的识别分数） |
| `source` | `FieldSource` | 取值来源：`LABEL` / `PATTERN` / `DERIVED` / `BACKFILL` / `MISSING` |
| `violations` | `List<String>` | 违规说明（如「GB 11643 校验位不符」），无违规为空列表 |

置信度基线规则：

| 来源 | 置信度 |
|------|--------|
| `LABEL` | 取 OCR 置信度本身 |
| `PATTERN` | OCR 置信度 × 0.9 |
| `DERIVED` / `BACKFILL` | 0.9 |
| `MISSING` | 0 |
| **违规非空** | **一律归零**（不是打折） |

> ⚠️ **校验不通过时字段值仍然保留**，只是该字段置信度**直接归零**并置位 `needsReview` ——
> 校验位不符只说明「至少认错了一个字符」，不等于「没识别出来」，把值丢掉会让下游比现在更糟。
>
> 「字段缺失」也不等于「值不可信」，是否复核由解析器按版面预期显式标记。

### 4.2 校验位算法

`Validations`（纯函数、零依赖、线程安全）提供三套校验位：

| 方法 | 标准 | 规则 ID |
|------|------|---------|
| `validateIdCard` | GB 11643-1999（居民身份证） | `GB11643` |
| `validateBankCard` | Luhn（银行卡） | `LUHN` |
| `validateCreditCode` | GB 32100-2015（统一社会信用代码） | `GB32100` |

另有 `genderFromIdNumber`（从证件号性别位回填性别）与 `birthDateFromIdNumber`（从证件号回填出生日期）。

三态语义（`ValidationStatus`）：

| 状态 | 含义 |
|------|------|
| `PASS` | 校验通过 |
| `FAIL` | 校验位不符 —— **唯一的硬证据**，说明至少认错了一个字符 |
| `UNCHECKABLE` | 长度或字符集不对，**不能据此断定认错**，不计入违规 |

> 已接入身份证、银行卡、营业执照、驾驶证四个解析器。

## 5. 自定义解析器

实现 `BaseStructuredParser<R>`（抽象类）即可：

```java
public class MyParser extends BaseStructuredParser<MyResult> {

    public MyParser(PPOcrV6Engine engine) {
        super(engine);
    }

    @Override
    public MyResult parseResults(List<PPOcrV6Result> results) {
        MyResult result = new MyResult();
        result.setRawResults(new ArrayList<>(results));

        // 标签定位 + 位置匹配，返回 LabeledMatch（值 + 值框列表 + 命中状态）
        LabeledMatch match = LabelMatcher.matchValueWithBox(results, "证件号");
        if (match.hasValue()) {
            result.setIdNo(match.value());
            LabelMatcher.applyFieldBox(result, "idNo", match);   // 回填 fieldBoxes
        }
        return result;
    }
}
```

公共能力集中在 `LabelMatcher`（静态工具）：

| 能力 | 方法 |
|------|------|
| 标签定位 + 位置匹配 | `matchValue(...)` / `matchValueWithBox(...)` / `matchValueByCenterWithBox(...)` |
| 前缀匹配 | `matchValueFromPrefix(...)` / `matchValueFromPrefixWithBox(...)` |
| 正则兜底 | `matchPattern(...)` / `matchPatternWithBox(...)` |
| 标签 + 值兜底 | `labelOrFallback(...)` / `labelOrFallbackWithBox(...)` |
| 关键字匹配 | `matchValueByKeywordWithBox(...)` / `matchValueByLabelKeywordWithBox(...)` |
| 版面布局兜底 | `collectMultiLineRight(...)` |
| 独占值分配 | `assignExclusiveValues(...)` |
| 字段回填 | `applyFieldBox(result, fieldName, match)` |

- `WithBox` 系列重载返回 `LabeledMatch(value, box)`，便于回填 `fieldBoxes`；
  `LabeledMatch.status` 区分 `FOUND` / `LABEL_WITHOUT_VALUE` / `NOT_FOUND` 三态。
- **标签别名兜底**：`LabelAliases` 覆盖「多字 / 带空格 / 换说法」三类 OCR 走样，
  **仅在规范标签落空时兜底** ⇒ 只增不减，不改变既有命中行为。
- **标签查找顺序**：`完整等于 > 以标签开头 > 片段拼接 > fragment 回退 > 别名兜底`，
  拼接层必须排在 fragment 回退之前（片段天然是标签子串，否则永不触发）。

配套工具（`core` 包）：

| 工具 | 职责 |
|------|------|
| `DateRepair` | 把被 OCR 弄乱分隔符的日期还原成 `yyyy-MM-dd` |
| `Validations` | 校验位算法 + 证件号回填 |
| `FieldConfidence` / `FieldSource` | 字段级置信度与取值来源 |
| `LabelAliases` / `LabelMatchStatus` | 标签别名表与命中三态 |

## 6. 声明式 schema（新增单据类型）

不想再实现一个解析器类时，写一份 schema 即可：

```java
OcrFieldSchema schema = OcrFieldSchema.builder("收据")
    .add()
        .field("orderNo")
        .labels("订单号", "单号")
        .regex("[A-Z0-9]{8,}")
        .rules(FieldRule.length(8, 32))
        .add()
        .field("amount")
        .labels("金额", "合计")
        .normalize(ValueNormalizer.DIGITS)
        .rules(FieldRule.digits(2))
        .add()
    .build();

OcrStructuredResult result = new OcrStructuredParser(engine, schema).parse(bytes);
```

| 类型 | 职责 |
|------|------|
| `OcrFieldSchema` | 字段声明入口（`builder(documentType)` → `add()` → `field(name)`） |
| `FieldSpec` | 单字段声明：字段名 / 标签列表 / 正则 / 规则 / 归一化 / 是否可选 |
| `FieldRule` | 校验规则：`digits(n)` / `length(min, max)` / `date()` / `idCardNumber()` / `bankCardNumber()` / `creditCode()` / 自定义 `Predicate` |
| `ValueNormalizer` | 值归一化：`NONE` / `DIGITS` / `COMPACT` / `DATE`（`digits()` / `compact()` / `asDate()` 三个快捷方法） |
| `ValueLocator` | 值定位：值归属最近标签 + 垂直重叠率 + 间距上限 |
| `OcrStructuredParser` / `OcrStructuredResult` | 通用解析器与结果（`documentType` + 字段 map） |

取值走「标签定位 → 合并框剥值 → 正则兜底」，校验位与字段置信度复用 `Validations` / `FieldConfidence`。
`generic(schema)` 入口在 Spring Boot / Solon 的 `PPOcrTemplate` 上同步提供。

## 7. 本地调试

每个解析器都提供了对应的 `XxxMain` 调试入口（如
[`VehicleLicenseMain`](../mica-ppocr-structured/src/test/java/net/dreamlu/mica/ai/ppocr/structured/parser/vehicle/VehicleLicenseMain.java)），
**直接运行 main 方法**即可做 OCR 推理 + 结构化解析。

`XxxMain` 继承通用基类 [`BaseTest`](../mica-ppocr-structured/src/test/java/net/dreamlu/mica/ai/ppocr/structured/parser/core/BaseTest.java)，
仅需覆写两个方法：

- `newParser(PPOcrV6Engine engine)` —— 返回绑定好泛型的解析器实例（自动注入 OCR engine）
- `printResult(R result)` —— 按证件类型输出字段

模型档位 / 文档方向分类 / 阈值等通用参数定义在 `BaseTest` 的 `TIER` / `USE_DOC_ORIENTATION` /
`DOC_ORIENTATION_THRESH` 等常量中，子类只需声明图片路径：

```java
private static final String IMAGE_PATH = "test_images/vehicle/vehicle1.png";  // 待推理图片
private static final String VIS_PATH   = "test_images/vehicle/vis.png";       // 可视化输出；传 null 跳过
```

以 `test_images/vehicle/vehicle1.png` 为例（模型档次 `tiny`）：

| 输入图片 | 识别结果可视化 |
|---------|---------------|
| ![vehicle1](../test_images/vehicle/vehicle1.png) | ![vis](../test_images/vehicle/vis.png) |

```text
--- 行驶证结构化解析 ---
plateNo:      鲁GH9P12
owner:        盛瑞传动股份有限公司
vehicleType:  小型普通客车
vin:          LJ8F3D5H910700001
issueDate:    2018-02-24
```

> 注意：测试的行驶证来源于网络，如有侵权，请联系删除。

## 8. AI 辅助开发（Skill）

项目内置 [`mica-ppocr-custom-parser`](../.agents/skills/mica-ppocr-custom-parser/SKILL.md) skill，
覆盖从继承 `BaseStructuredParser<R>` / `BaseStructuredResult`、调用 `LabelMatcher` 公共工具、
Spring Boot 自动配置注册，到 `BaseTest` 可视化调试与单测的完整开发链路。

通过 [skills.sh](https://skills.sh/) 一键安装到 Mavis / Claude Code / Cursor 等 AI 编码工具：

```bash
npx skills add lets-mica/mica-ppocr
```

安装后，当你提出「加个 XX 证件 / 票据解析器」「自定义结构化解析」等需求时，
AI 会自动加载该 skill 给出符合项目规范的实现。

针对**已有解析器的批量调优**场景（用户提供图片/PDF 目录，定位哪些字段 F1 偏低并自动给出最小局部修复），
内置 [`ocr-parser-optimizer`](../.agents/skills/ocr-parser-optimizer/SKILL.md) skill：
跑批 → 9 类失败模式诊断（F1 label 残缺 / F3 值截断 / F5 标签值合并框 等） → LabelMatcher / 正则 / 几何阈值最小改动 → 再验证，
输出 `optimization-reports/<parser>-<timestamp>.md` 对比报告。
提「调优 / 修一下 / 看哪些样本解析不对」等需求时会自动加载。
