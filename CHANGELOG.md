# 变更记录

## 发行版本

### v1.2.1 - 2026-09-01
- feat(structured): 优化行驶证结构化解析。
- feat(structured): 发票解析统一入口 + 支持新版电子发票（数电票）。
- fix(structured): 修复发票解析器地址电话/开户行账号多框拼接与发票号码解析。

### v1.2.0 - 2026-08-27
- fix(core): 修复动态分辨率下内存持续增长（github #14 根治）：新增 `enableCpuMemArena`（默认 false）、`enableMemoryPattern`（默认 false）配置，关闭 ONNX Runtime CPU arena / 内存模式优化，临时内存用完即释放，Docker 等内存受限环境不再 OOM；新增 `execMode` 配置（sequential / parallel）暴露 ORT 执行模式。Spring Boot Starter 与 Solon 插件同步暴露三个配置项。
- refactor(core): 全面支持 Java 8（`record` / `List.of` / `Path.of` 等 Java 9+ API 替换为 `CollUtil` 工具与 Lombok `@Value` 风格，Spring Boot Starter 改用 `@Configuration` 兼容 2.5~4.x）。

### v1.1.7 - 2026-08-26
- feat(pdd): 新增拼多多福袋 OCR 结构化解析器（PddLuckyBagParser / PddLuckyBagResult），从「百亿补贴 抽福袋」分享图提取 8 位福袋码（邀请码）。
- feat(idcard): 优化性别解析和住址提取逻辑，增强兼容性。性别解析支持合并框切割（兼容"性别男民族汉"双标签连写合并框及"性""别"字缺失场景）。github #13 感谢 `@iamxiaojianzheng` 贡献。
- fix(idcard): 优化身份证地址跨行解析逻辑与消除多标签合并框告警 gitee #13 感谢 `@xiaojianzheng` 贡献。
- fix(idcard): 提升身份证识别的精度 gitee !2 感谢 `@张海阳` 贡献。
- fix(vehicle): 新增真实 OCR 样本单元测试验证合并框"所有人xxx"正确剥离前缀；测试覆盖车辆类型和所有人的正确解析；保留旧的多语言标签回退逻辑，兼顾更多场景识别。
- fix(household): 修正户籍信息日期识别和关系字段匹配逻辑。`parseRelationship` 对"与户主关系"标签严格 y 中心匹配避免误匹配上下行内容。
- fix(core): 修复 PPOcrV6Engine.decodeMat 泄漏 MatOfByte native buffer。github #14 感谢 `@iamxiaojianzheng` 反馈。

### v1.1.6 - 2026-08-21
- fix(config): 还原 detLimitSideLen、detLimitType 默认组合为 64 + min，更适合证件类解析。

### v1.1.4 - 2026-08-21
- feat(structured): 新增火车票和出租车票 OCR 结构化解析器。
- feat(parser): 新增户口本（常住人口登记卡）OCR 结构化解析器。支持姓名、性别、出生地、民族、籍贯、出生日期、户号等字段的标签匹配和正则兜底识别，处理标签切碎、多行标签及跨框合并等 OCR 异常情况。
- feat(idcard): 兼容 15 位身份证号解析，并增加按身份证号推算出生日期的兜底。gitee #IK94VR 感谢 `@zhanghaiyang` 反馈
- feat(core): 模型路径支持 `classpath:` 前缀，可把模型打进 Spring Boot Fat Jar。tiny、small 模型尺寸较小方便内置，简化部署。
- refactor(config): 文档方向分类阈值由 0.3 调至 0.4。detLimitSideLen、detLimitType 默认组合由 64 + min 改为 960 + max（PaddleX v4 / v5 / v6 的官方推荐组合）。
- refactor(core): 简化 PPOcrTemplate 结构化解析器管理实现。构造器参数从固定多个解析器改为列表形式，用 LinkedHashMap 按类型索引解析器并支持通用 get(Class) 获取；Solon 与 Spring Boot Starter 同步新增户口本解析器注册及参数校验。
- docs(skill): 新增 `mica-ppocr-custom-parser` skill，覆盖自定义结构化解析器全链路。

### v1.1.3 - 2026-08-15
- feat(idcard): 优化身份证多标签合并框解析逻辑。新增正面字段标签数组，支持"性别男民族汉"双标签连写的合并框切分；性别、民族字段解析兼容标签与数值合并场景；身份证号解析加入正则兜底，处理标签残缺或合并场景；补充标签残缺及标签与值合并场景的单元测试。
- refactor(core): 重构结构化解析器基类及测试基类。BaseStructuredParser 由接口改为抽象类，统一持有 PPOcrV6Engine 引擎并提供一站式 parse() 实现，子类构造时绑定引擎、仅需重写 parseResults；BaseTest 支持泛型，统一管理引擎创建、OCR 调用及结构化结果打印；测试辅助方法抽取至 ParserTestSupport，删除静态单例与工具类风格入口，提升扩展性与代码复用性。
- refactor(parser): 优化银行卡、驾驶证、身份证等解析器代码结构。持卡人姓名黑名单、驾驶证非签发机关标签前缀等提为类常量；LabelMatcher 泛型参数改为 Function 并返回不可变 List.copyOf，查询标签框日志级别由 WARN 降为 DEBUG；统一多行拼接 skipTexts 参数为 Set；删除发票解析中过时的字符串截断与片段检测方法，行为完全等价。
- refactor(PPOcrTemplate): 简化 PPOcrTemplate 模板，各个结构化解析器改为链式调用。

### v1.1.2 - 2026-08-13
- feat(solon): 新增 mica-ppocr-solon-plugin Solon 插件适配模块，提供 PPOcrTemplate 一站式封装与结构化解析器自动装配，能力与 Spring Boot Starter 对齐；补充 Solon 端到端集成测试验证插件装配与 OCR 全流程可用性。
- feat(structured): 新增营业执照结构化解析器（BusinessLicenseParser），抽取社会信用代码、单位名称、住址、法定代表人、有效日期至、成立日期、类型、注册资本、经营范围共 9 个字段；基于"标签定位 + 位置匹配"策略覆盖横版/竖版版式，处理 OCR 常见噪声（信用代码合并/截断、名称与类型 fragment 拆字、经营范围多行拼接等）；LabelMatcher 扩展 matchPattern / findLabelBox / collectMultiLineRight 等基础能力供复用；Spring Boot Starter 自动注册 BusinessLicenseParser bean，PPOcrTemplate 新增 parseBusinessLicense(...) 5 种入参重载；配套 5 张真实样本 + 可视化产物。
- refactor(structured): 优化 BusinessLicenseParser 编码规范（行为完全等价，47 个单元测试 + 9 个 starter 测试全绿）：所有字段标签字符串提为 LABEL_* 常量（12 个）消除散落字面量；信用代码长度、置信度阈值等魔术数字集中到"调参常量"分组；TYPE_KEYWORD / LEGAL_PERSON_LABELS / SCOPE_SKIP_FRAGMENTS 等从方法局部提升为类常量；parseType / parseLegalPerson / parseBusinessScope / parseAddress 按语义拆出若干子函数便于单测；stripMergedLabel / stripFragmentPrefix / stripMergedScope 改返回 LabeledMatch 便于日志携带原始框；javadoc 字段命名同步修正（住所→住址，营业期限→有效日期至）并补充分拆方法语义；if 单行加花括号、长行按 if/else 分行、fragment 判空逻辑简化等风格统一。
- feat(structured): 新增增值税发票结构化解析支持（InvoiceParser）；PPOcrTemplate 增添增值税发票多种入参解析方法；Spring Boot 与 Solon 自动配置同步注册 InvoiceParser 解析器单例；测试资源补充 3 张发票 OCR JSON（上海增值税普通发票、湖北增值税普通发票、江苏增值税专用发票）含完整文本及位置信息，为解析器提供多样化版式样本。
- refactor(logging): 结构化解析模块日志级别全面由 info 调整为 debug：营业执照解析、驾驶证签发机关正则兜底、身份证号正则兜底命中、标签剥值及正则兜底、行驶证各字段兜底匹配等日志统一降为 debug，显著降低控制台输出噪音。
- chore(build): 优化 solon 插件编译与配置元数据支持；pom.xml 补充 solon-configuration-processor（scope=provided）并调整 maven-compiler-plugin 的 annotationProcessorPaths；新增 src/main/resources/META-INF/solon/additional-solon-configuration-metadata.json，为 detLimitType 字段补充 min/max enum hints，同时支持 camelCase 与 kebab-case 双写法输入；构建时自动合并生成 solon-configuration-metadata.json。
- docs(readme): 依赖项 groupId 更正为 net.dreamlu；新增营业执照、增值税发票解析器相关说明与代码示例；Solon 插件适配相关文档同步更新。

### v1.1.1 - 2026-08-13
- feat(ocr): 支持 PP-OCRv6 文档方向分类（use_doc_orientation_classify）。使用 PP-LCNet_x1_0_doc_ori 模型（4 类：0°/90°/180°/270°），在 OCR 检测前对整图做方向校正，避免用户侧倒拍/横拍导致识别失败。新增 PPOcrV6Config.useDocOrientationClassify / docOrientationModelPath / docOrientationThresh 配置项；PPOcrV6Engine.runMat 在检测前自动完成方向分类 + 旋转；行为完全向后兼容（默认关闭）。gitee #IK86TX 感谢 `@goalsword` 建议。
- refactor(engine): PPOcrV6Engine 内部代码精简。`run(Path)` / `detect(Path)` 抽出 `loadMat(Path)` 私有方法消除 native-vs-fallback 重复；`closeSessions` 用 for 循环消除 3 段重复 try/catch；`runMat` 拆为"Mat 生命周期管理"和"核心流水线"两层，单层嵌套；`classifyAndRotateDocOrientation` 改用 switch 表达式。净减 12 行，行为完全不变。
- perf(core): 性能优化 + 修 native Mat 泄漏。DocOrientationPreprocessor 修 resizeShort 返回新 Mat 未 release 的泄漏（约 1.5 MB/调用，长期运行 OOM 风险）；CtcLabelDecoder 解码循环 3 合并为 1 次（call(float[][][]) 同步：argmax + max + CTC 单次扫描）；CtcLabelDecoder.stripTrailing 改用 Java 11+ String.stripTrailing；DbPostProcessor.boxScore 去冗余 float[][] 深拷贝；PPOcrTemplate 4×5 解析器便捷方法去中间跳转；9 个测试全部通过。
- docs(readme): 补充 PP-OCRv6 文档方向分类（use_doc_orientation_classify）使用说明；§2 模型目录新增 doc_ori 可选模型；新增 §4.3 完整子章节（模型下载 / Java 代码 / Spring Boot yml / 性能代价 / 与弃用 use_angle_cls 的关系）。
- chore(build): onnxruntime 依赖版本降级为 1.18.0，兼容更多系统版本 gitee #IK8695 感谢 `@goalsword` 反馈

### v1.1.0 - 2026-08-12
- feat(parser): 新增 mica-ppocr-structured 结构化解析模块，支持行驶证、身份证、银行卡、驾驶证 4 类证件；提供 SPI 接口 BaseStructuredParser 与公共骨架 LabelMatcher（标签定位 + 位置匹配 + 正则兜底）。
- feat(starter): 新增 PPOcrTemplate 一站式封装（mica-ppocr-spring-boot-starter），自动装配 PPOcrTemplate 与 4 个结构化解析器 Bean；提供纯 OCR 识别及 4 类证件结构化解析便捷方法。
- feat(core): 结构化结果支持可视化坐标，新增 BaseStructuredResult 抽象类，统一持有 rawResults（完整 OCR 原始框）与 fieldBoxes（字段→坐标列表映射）。
- refactor(core): 公开 API 去掉 Mat 入参；PPOcrV6Engine 的 run / detect 统一委托到 Path 版本（默认 FS 走 OpenCV native 读取，非默认 FS 自动回退 Files.readAllBytes → byte[]），新增 String / File / Path / byte[] / InputStream 5 种入参重载；原 Mat 版重命名为 runMat / detectMat / recognizeMat（public，标记为"已持有 Mat 复用"的高级场景，调用方负责 release）；内部统一 try-finally 释放 Mat，调用方无需任何 native 内存管理。

### v1.0.1 - 2026-08-10
- fix(core): 释放原生推理资源，避免本地句柄泄漏；新增泄漏回归测试并加固构造器清理路径。
- refactor(opencv): OpenCV 加载方法由 loadShared 改为 loadLocally

### v1.0.0 - 2026-08-07
- feat(core): 实现 PP-OCRv6 文字检测与识别核心功能（DB 后处理 + CTC 解码）。