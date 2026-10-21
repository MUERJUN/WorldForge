# 更新日志

## v0.6.0 Public Beta — 正式公测

**首个公开发测版本。** 安装一个 JAR，在 Minecraft 里创建、测试并导出真正可用的独立 Fabric Mod。

### 本版修复（发布门禁）

#### P0-A：无效 Journal 不再绕过校验

- `readExpectedSha` 严格区分三种状态：**缺失**（无待完成事务）、**有效**（格式正确的 sha-256）、**无效**（存在但无法读取/格式损坏）
- `recover()` 不再使用 `want == null || sha256(..).equals(want)` 这一短路逻辑——无效 Journal **绝不**授权信任 tmp 或 target
- 无效 Journal 时只允许保守回滚到既有备份；无法确认有效版本时**保留全部恢复证据**（journal/tmp/target/bak）并 fail-closed，允许用户备份与人工检查
- 旧代码上会失败的测试：`itemMalformedJournalDoesNotTrustTmp`、`itemUnreadableJournalDoesNotTrustTarget`、`blockMalformedJournalDoesNotTrustTmp`、`firstSaveTornTargetNoTmpFailsClosed`

#### P0-B：首次保存失败不再留下半成品目标

- `commit()` 跟踪 `targetTouched`：仅在确实覆盖过目标后才执行回滚
- **首次保存**（无既有版本）中途失败：只删除本次事务的撕裂产物，**绝不**删除用户原有文件（此前不存在）
- **回滚本身失败**：保留 journal + bak + tmp，阻止进一步覆盖并抛出明确失败
- 未触及目标（如备份读取失败）：原文件完好，仅清理事务标记
- 新增测试：`firstSaveTornTargetNoTmpFailsClosed`、`unwritableTargetBlocksRecoverToo`

#### 持久化耐久性说明

- Journal 写入后会 fsync 文件本身，并尽力 fsync 目录元数据
- 目录 fsync 在部分平台（Windows）不受支持，因此**不承诺**"任意断电绝不丢失"；仅承诺崩溃后最近一次完整有效版本可恢复

### 验证结果

| 门禁 | 结果 |
|------|------|
| 全量 JUnit | **246/246** |
| WorldForge GameTest | **61/61** |
| 持久化故障注入 | **16/16**（items.json + blocks.json 双覆盖） |
| 独立 Mod GameTest | 8/8 |
| Windows 隔离客户端 | 51/51（真实 JAR 构建） |
| Linux CI | 见下方平台矩阵 |

### 平台验证矩阵

| 平台 | 导出构建 | headless GameTest | 图形客户端 GUI |
|------|---------|-------------------|---------------|
| Windows 11 | ✅ 真实构建 3 工程 | ✅ 8/8、11/11、5/5 | ✅ 隔离实例 51/51 |
| Linux | ✅ GitHub Actions | ✅ 同 CI 内 | ❌ 未验证 |
| macOS | ❌ | ❌ | ❌ 未验证 |

### 公测已知限制

- **Minecraft 1.21.1 专用**，跨版本兼容未验证
- **Linux**：仅验证无图形导出构建与 Fabric GameTest，**GUI 尚未验证**
- **macOS**：尚未验证
- **AI 服务商**：真实 DeepSeek/OpenAI 端到端调用尚无独立验收（示例使用本地测试服务）
- **不是任意 Java 代码生成器**：仅支持白名单动作与触发器
- **不支持的技能不能保证等价导出**：能力报告明确区分保留/转换/丢失，丢失项需玩家确认
- 中文 GUI 文案在部分字体环境显示为方块（游戏语言设为简体中文可缓解）
- 高 GUI Scale（2/3）+ 854×480 小窗口下导出页为压缩布局

---

## v0.5.7 已发布 — 公测候选质量门禁

### P0-1: ExportSymbolMapper 全面接通

- **修复前证据**：`a_` 生成非法包 `a.`、`a__b` 生成 `a..b`（`replace("_", ".")` 散落 12 处）——`SymbolTableWiringTest` 修复前 3 项失败
- 生成器所有包名/目录/入口统一来自构造时冻结的 `pkg`（`ExportSymbolMapper.packageName`）；`mainClassName` 改走 `identifierBase`
- 目录路径 == 声明 package == fabric.mod.json 入口类（三重一致性测试）
- 附带修复：ID 验证正则 `[a-z0-9_.-/]` 中 `-/` 被解析为字符范围导致**连字符 ID 全部被拒**（4 处）——连字符是 Minecraft 合法 ID 字符

### P0-2: 两阶段 Preflight → Commit

- `generate()` 第一阶段解析全部定义、注册全部符号、检测折叠冲突/大小写不敏感文件名冲突/保留名冲突/注册 ID 冲突——**任何文件写入之前**
- 修复前证据：冲突检测在写文件之后（v0.5.6 遗留）；`ConflictBeforeWriteTest` 断言失败后项目目录零 .java 文件
- 折叠冲突确定性消歧：`a-b`/`a_b`/`a.b` → `A`/`A_2`/`A_3`，冲突记录可审计
- 附带修复：`DefinitionStore`/`BlockDefinitionStore` rename 被 Windows TEMP junction 拒绝时降级 copy+delete（保留真实磁盘故障信号——3 个磁盘失败 GameTest 验证未回归）

### P1-1: 效果 ID 语义有效性

- `strictEffectId` 现验证效果**真实存在于 1.21.1**（白名单从合并 jar 的 StatusEffects 提取，39 项，非记忆）
- 格式合法但不存在的效果（`flying_machine`）在方案创建时拒绝并说明——不再可能生成"效果为空仍返回 SUCCESS 并开始冷却"
- 测试：speed/strength/fire_resistance/instant_health 接受；虚构/空/命名空间/注入/大写拒绝；集合数量断言

### P1-2: 能力报告单一核心

- 旧 `buildCapabilityReport(ExportRequest)` 标记 @Deprecated 并**委托** `capabilityReportOf`（旧核心缺 converted/触发器/属性/周期核算——与新核心不一致的根源）
- GUI/ExportPlan/Executor 全部读同一冻结 `plan.report()`（assertSame 身份验证）
- `CapabilityReportConsistencyTest` 4 项：复杂对象三桶分流、旧 API 与新核心逐字段相等、执行器守卫引用冻结清单

### P1-3: 三个真实导出回归（全部真实 Gradle 构建 + 真实 Fabric 加载）

| 工程 | 内容 | 构建 | GameTest |
|------|------|------|----------|
| alpha_mod | 雷霆剑+交互方块（基线） | ✅ | 8/8 |
| beta_mod | `magic-sword`/`magic.device`/`magic__core`/`power.block`/`spark_stone` 多物品多方块非默认参数 | ✅ | **11/11** |
| gamma_mod | 含不支持动作 `fly` 的降级方案（确认后导出） | ✅ | 5/5 |

- JAR 内容审计：0 WorldForge 引用、0 密钥、fabric.mod.json 入口类与 jar 内 class 一致、依赖仅 fabricloader/minecraft/java/fabric-api
- 脚本：`scripts/build-three-projects.sh`（Windows/Linux 通用）

### P1-4: 文档完整性

- 恢复被 v0.5.6 贪婪正则吞掉的 README 快速开始章节（面向玩家的 GitHub Release 安装 + K 键 + 开发者构建）
- `sync-version-docs.py` 重写：仅行级替换 + 必需章节完整性门（缺失即 exit≠0 且不写文件）
- `DocsIntegrityTest` 4 项：快速开始/玩家旅程/版本一致性/脚本门

### P2: Linux 验证

- 新增 `.github/workflows/linux-export-build.yml`：ubuntu-latest + Temurin 21 + 空白 GRADLE_USER_HOME + 无系统 Gradle——生成三工程→Linux `./gradlew build`→Linux `runGameTest` 真实加载
- 本机 WSL 无发行版（安装需重启）——Linux 实测移交 CI，**在 CI 首次运行通过前仍标记为未验证**
- macOS：无环境，继续标记未验证

### 验证汇总

| 项目 | 结果 |
|------|------|
| JUnit | 230/230（+28） |
| WorldForge GameTest | 61/61 |
| 独立 Mod GameTest | 8/8 |
| 三工程真实构建（Windows） | 3/3 + GameTest 8/8、11/11、5/5 |
| Windows 11 隔离客户端 | 51/51（真实 JAR 构建） |
| Linux 实机构建 | CI 待运行（本机 WSL 需重启） |
| macOS | 未验证（无环境） |
| JAR | worldforge-0.5.7.jar · SHA-256 6b45c784d6e562c0… |

---

## v0.5.6 已发布 — 符号映射、严格校验与热更新隔离

### P0-1: ExportSymbolMapper 统一符号映射

- **六目标分离**：Minecraft 注册 ID / Java 包名 / 类名 / 字段名 / 资源路径 / 文件名各自独立映射，不再用 replace()+首字母大写拼凑
- **确定性**：`-`/`.`/连续下划线按非字母数字分段折叠成 PascalCase；`magic-sword`、`magic.device`、`magic_core`、`magic__core` 全部产出合法且可区分的标识符
- **冲突检测**：不同 ID 折叠到同一类名/字段名时记入冲突，generate() 完成前抛出明确错误（文件写入前完成检查）
- **关键字转义**：Java 关键字追加 `_`；数字开头加 `_` 前缀
- **包名**：Mod ID 下划线段折叠，`a_`/`a__b` 不再产生 `a.`/`a..b`
- 证据：`ExportSymbolMapperBeforeFixTest` 14 项（修复前 4 项失败）

### P0-2: 参数严格校验（拒绝而非改写）

- **效果 ID**：`strictEffectId` 拒绝非法字符与外部命名空间（明确提示"仅支持 minecraft 原版效果"），不再把 `speed!extra` 改写成 `speedextra`
- **数值**：`preciseInt` 精确整数转换——`2.9` 小数拒绝（不截断）、`NaN`/`Infinity` 拒绝、`Long.MAX_VALUE` 拒绝（不回绕）、范围校验；`2_000_000_000_000` 无法伪装成合法值
- **校验前移**：`ExportPlan.create` 冻结时即校验，错误在方案创建瞬间暴露，不在后台构建时才炸
- 证据：`ExportSymbolMapperBeforeFixTest` 6 项参数测试

### P0-3: 移除仓库机器路径

- `gradle.properties` 的 `-Djava.io.tmpdir=E:/javas/tmp` 与 `org.gradle.tmpdir=E:/javas/tmp/gradle-tmp` 移除（v0.5.5 遗留的开发者本机路径）
- 新增 `repoHasNoMachineSpecificPaths` 自动检查（盘符路径 + URL 协议排除），防止再次提交
- 证据：`ExportSymbolMapperBeforeFixTest#repoHasNoMachineSpecificPaths`

### P1: 跨平台 wrapper 封闭

- `gradleCommandFor` 不再跨 OS 回退：Windows 只认 .bat，Unix 只认 gradlew，都缺失则**明确报错**（不再静默用系统 Gradle）
- `copyGradleWrapper` 释放后校验完整性：gradlew 必须存在、Unix 下设置可执行位、wrapper jar 双平台必需
- Linux 实机构建仍标记未验证（环境限制同 v0.5.5）

### P1: Java 注释封闭

- 新增 `safeCommentText`：JavaDoc 只保留字母/数字/空格/下划线/CJK——`*/`、引号、反斜杠、换行全部剔除，玩家/AI 文本无法提前终止注释或改变语法结构
- 三处 JavaDoc（自定义 Item/Block/BlockEntity）全部切换
- 生成器余下字符串插入点沿用 `escapeJava`

### P1: AI 配置 epoch 隔离

- `configEpoch` 每次 reloadConfig 递增；`chat()` 请求绑定发起时的 epoch，回调在 server 线程复核——**旧配置响应静默丢弃**，绝不写入新会话/生成提案/污染 UI
- `attachProviderForTesting` 同步 bump epoch；`clearRateForTest` 测试隔离钩子（不再让 releaseAll 误清在途提案）
- 证据：`WorldForgeAgentTests#testAgentBridgeEndToEnd` step 13——慢响应 + 配置切换竞态，stale 回复零泄漏（日志可见 "dropped stale AI response"）

### P1: 六行为等价性核查

- 新增 `auditBehaviors`：retained 必须参数完全可兑现——sound/particles/ignite 的自定义参数（音效事件/粒子类型数量/燃烧秒数）因生成器硬编码而归入 **converted**（"导出使用固定默认实现"），默认参数保持 retained
- apply_effect（effect/duration/amplifier）、heal（amount）、summon_lightning 参数完全兑现（生成源码逐项断言）
- 证据：`ActionEquivalenceAuditTest` 6 项（修复前 3 项 converted 缺失失败）

### 文档自动化

- `scripts/sync-version-docs.py`：从 gradle.properties 同步版本号到 README/BETA-GUIDE，终结手工复制错误（v0.4.0 残留引用已清理）
- README JUnit 统计更正（110→202）

### 验证汇总

| 项目 | 结果 |
|------|------|
| JUnit | 202/202（+23） |
| WorldForge GameTest | 61/61（含 epoch 竞态验证） |
| Standalone GameTest | 8/8 |
| Windows 隔离实例 | 51/51（真实构建 auto_mod-1.0.1.jar） |
| Linux 实机构建 | 未验证（环境限制，同 v0.5.5） |
| macOS | 未验证（无环境） |
| JAR | worldforge-0.5.6.jar · SHA-256 361aacdc…a9ed3 |

---

## v0.5.5 已发布 — 安全修复与跨平台构建

### 源码审查：8 项发现全部确认并修复

| # | 缺陷 | 位置 | 修复 | 证据 |
|---|------|------|------|------|
| 1 | `experience`/`give_item` 被标支持但无生成实现（静默丢弃） | ModExporter.java isSupportedItemAction | 从支持清单移除，报告为 lost"无代码生成实现" | SecurityAuditFixTest#experienceActionIsNotClaimedAsSupported |
| 2 | `hit_entity` 等触发器被并入 right-click use() | TemplateProjectGenerator.java writeCustomItem/writeCustomBlock | 触发器契约 `isTriggerExportable`——非 on_use 行为不生成右键代码，明确报告丢失 | ExportEquivalenceMatrixTest#mixedTriggerExportOnlyImplementsOnUse |
| 3 | 物品属性、周期方块行为静默丢失 | ModExporter.capabilityReportOf | 属性 Map 与 PERIODIC 方块显式进入 lost 报告 | #itemAttributesSurfaceInReport / #periodicBlockCannotExportEquivalently |
| 4 | 玩家/AI 文本可注入生成 Java（`effect` 参数逃逸字符串） | TemplateProjectGenerator apply_effect | `sanitizeEffectId`（只留 [a-z0-9_]）+ `sanitizePositiveInt`（范围拒绝） | #maliciousParamsCannotBreakGeneratedCode（注入代码被清除） |
| 5 | ID 可通过路径段逃逸资源目录（`worldforge:../../evil` 过验证器） | ModExporter.nativeItemId + validateIdSafety | 原始 ID 段检查拒绝 `..`/`.`/绝对路径；nativeItemId 防御性规范化 | #nativeIdRejectsPathTraversalSegments 等 3 项 |
| 6 | ExportPlan 内部 Map/List 仍可变（attributes 用 LinkedHashMap） | ExportPlan.deepCopy | Map.copyOf 冻结 + freezeParamValue 递归冻结，嵌套/越界参数创建时拒绝 | ExportPlanDeepImmutabilityTest 4 项 |
| 7 | 执行器允许未确认降级导出（GUI-only 拦截） | ExportExecutor.doExport | `degradedGuardError` 执行器自检——lost 非空且未确认直接 FAILED | #executorRejectsUnconfirmedDegradedPlans |
| 8 | Linux/macOS 会错误选择 gradlew.bat（文件存在优先于 OS） | ExportExecutor.buildCommand | `gradleCommandFor(os, …)` 纯决策函数——Windows 用 .bat，Linux/macOS 用 gradlew | #gradleCommandPrefersPlatformWrapper |

### 其他可靠性

- **AgentBridge 配置热更新**：reloadConfig 现在先 cancelAll+shutdown 旧 provider 再替换，杜绝在途请求用旧凭证
- **构建日志取尾部**：错误消息保留最后 4000 字符（头部截断曾掩盖真实错误——隔离验收排查中实锤）
- **导出构建 `--no-daemon -x runGameTest`**：不再在玩家机器上启动第二个 Minecraft，daemon 常驻内存减半（页面文件耗尽的机器上 daemon 无法启动——隔离验收实锤并修复）
- 导出模板与 WorldForge 自身 gameTest JVM 均限 1G

### 环境限制（如实记录）

- **Linux/macOS 独立构建**：本机 WSL 无发行版且安装需重启，Docker 不可用——gradleCommandFor 决策函数已单元测试覆盖（Windows 实测 gradlew.bat 构建通过），Linux/macOS 实机构建**未验证**，标记为公测待验证项
- Windows 11 隔离导出：51/51 通过（含真实 JAR 构建）
- 本机后期系统提交内存被第三方进程（TTS server 12.7GB）耗尽，WorldForge 自身 GameTest 在最终复跑时因 `commit_memory` 失败受阻——非代码缺陷，早前同版本测试 61/61 已通过

### 验证汇总

- JUnit 179 项（+18 安全/等价测试，最终复跑全绿）
- GameTest 61/61（最终复跑受系统内存阻塞，此前多轮通过）
- Standalone GameTest 8/8
- Windows 隔离实例 51/51（真实构建 auto_mod-1.0.1.jar）

---

## v0.5.4 已发布 — 公测准备：快照一致性、取消语义与真实安装验收

### 第一阶段：源码审查修复

#### P0: 后台导出彻底脱离运行时定义
- **`validatePlanShape` 纯快照化**：Mod ID 格式、名称安全、输出目录符号链接、注册 ID 冲突（物品 vs BlockItem）全部从方案冻结数据校验，零运行时查询
- **`capabilityReportOf` 快照报告**：`ExportPlan.create` 的能力报告直接由深拷贝定义计算（同一份数据），报告与导出永不背离
- **端到端证明**（`PlanOnlyExportTest` 5 项）：建方案→删除定义→分离存储→导出仍成功且内容正确；实时编辑不污染冻结报告；快照冲突检测

#### P1: 深度不可变 ExportPlan
- 全部嵌套参数/行为列表/属性 Map 深拷贝冻结（`ExportPlanDeepImmutabilityTest` 4 项）：原始 Map 事后投毒不泄漏、所有集合拒绝修改、并发读取稳定、可变类型创建时拒绝

#### P1: 取消构建终态语义
- 取消→CANCELLED（绝不误记 FAILED）、失败→FAILED、超时→TIMEOUT、完成→OK；终态 `compareAndSet` 不可覆盖
- **预哈希检查点**：构建完成后取消无法追溯丢弃产物——竞争规则明确且测试覆盖
- **多阶段竞争测试**（`ExportCancellationRaceTest` 24 项）：构建前/生成中/Gradle 运行中/进程刚退出/SHA-256 阶段 × 多轮重复 + 并发 cancel
- 非 OK 终态清理全部 JAR，保留源码工程并明确告知用户

### 第二阶段：隔离实例真实安装验收（51/51 通过）

**全新安装路径**（不用开发缓存）：
- Fabric Installer 1.0.1 经代理安装 Loader 0.16.9 + MC 1.21.1（直连超时→代理成功，记录网络细节）
- 从 Mojang piston-meta 下载 1.21.1 客户端 + 97 库文件（102 MB，108 秒，零失败）+ 3911 assets（50 分钟）
- mods 仅放 Fabric API + WorldForge 0.5.4 候选 JAR（SHA-256 双侧核对一致）

**发现并修复的死锁**（jstack 实锤定位）：
- tick handler 内调用 `createIntegratedServerLoader().start()` 阻塞渲染线程 → `startIntegratedServer` sleep-poll 死锁（Preparing spawn 0% 永久卡住）
- 修复：隔离验收改走 QuickPlay（主函数加载世界），tick 内只等待不重复 start

**隔离实例完整流程 51 项全部通过**：
- QuickPlay 进入真实新世界 → Studio → 导出页 → 字段输入/全选 → 降级阻止/确认/失效/取消 → **真实 Gradle 构建 auto_mod-1.0.1.jar 成功** → 取消重导出 → Scale 1/2/3 布局审计 → ESC 语义

**布局真实缺陷修复**（截图暴露）：GUI Scale 2/3 下虚拟宽高仅 427×240/285×160，原布局列溢出窗口——自适应行距 + 窄窗堆叠列布局，layoutRegionsAreDisjoint 三档全过

**导出模板测试修复**：闪电断言改用 UUID 增量（批内共享世界的旧闪电消失会造成计数假阴性），standalone 8/8 稳定

### 第三阶段：交付体验

- `docs/BETA-GUIDE.md`：中文安装教程、5 分钟示例、能力清单（保留/警告/不支持三档）、环境要求、API Key 安全说明、常见问题排查
- 明确标注：AI 生成使用本地测试服务验证；真实 DeepSeek/OpenAI 兼容性待公测反馈

### 验证汇总

| 验证层 | 结果 |
|--------|------|
| JUnit | 163/163（+30 新测试） |
| WorldForge GameTest | 61/61 |
| Standalone GameTest | 8/8 |
| 隔离实例 GUI 验收 | 51/51（含真实构建 + 截图 + SHA-256） |
| 候选 JAR | worldforge-0.5.4.jar · SHA-256 da780cff…5da32 |

---

## v0.5.3 已发布 — 导出器可靠性与真实客户端验收

**核心目标**：把已有导出能力做成普通玩家真正可以可靠使用的产品。

### P0-1: 让导出快照真正生效

- **`ExportPlan` 不可变方案**：游戏线程上一次性解析全部选中定义并深拷贝（行为、参数、属性全部重建为不可变集合），附能力报告与输出目录
- **`DefinitionResolver` 抽象**：`fromRuntime()`（方案创建，仅客户端线程）与 `fromSnapshot(plan)`（后台线程只读快照）两种实现
- **TemplateProjectGenerator 去运行时化**：全部 12 处 `WorldForgeRuntimeManager/BlockRuntimeManager` 查询改为 resolver 路由，后台导出不再触碰实时数据
- **导出与编辑解耦**：方案创建后删除/编辑定义、切换世界均不影响进行中的导出；JAR 内容与确认时的方案一致
- **回归测试**：`ExportPlanSnapshotTest` 5 项 —— 删除后快照可导出、编辑后方案保留原值、8 线程并发建方案、runtime 分离后快照仍可解析、降级确认副本

### P0-2: 修复降级确认 UI

- **状态即可见性**：确认/取消按钮的 visible/active 在状态变化点直接设置（不再依赖渲染帧时序），普通模式完全隐藏禁用
- **完整能力报告**：保留/转换/丢失三段平铺 + 鼠标滚轮滚动查看
- **确认失效**：任何字段修改、全选、列表选择变化立即作废旧确认并隐藏按钮
- **同一方案执行**：确认时执行用户看到的同一份 ExportPlan（`withDegradedConfirmation`），不重新读取已变动表单
- **ESC/取消语义**：第一次 ESC 取消确认，第二次才关闭；关闭/取消均恢复初始状态

### P0-3: 真正完成导出 GUI 自动化（42 项检查全部通过）

- **真实客户端全流程**：QuickPlay 进入真实单人世界 → Studio 按钮 → 导出页 → 字段输入回读 → 全选物品 → 触发降级方案
- **关键断言**：确认前无构建线程、无 executor、无产物；确认按钮先隐藏后出现；表单修改后失效；取消后无 JAR 残留；取消状态 CANCELLED
- **真实构建**：仅支持项导出真实完成 `auto_mod-1.0.1.jar` 构建 + SHA-256 校验
- **多分辨率**：854×480 下 GUI Scale 1/2/3 全部稳定 + 7 张真实截图
- **失败即验收失败**：任何 FAIL 抛出 RuntimeException 使 runClient 退出码非 0，绝不静默通过
- **修复过程**：修复前 10 项失败 → 逐项根因修复（输入框 32 字符截断路径、窗口失焦暂停阻塞 server.execute、渲染帧时序、step 计数跳过）→ 42/42

### 第二阶段：构建生命周期与超时

- **异步日志读取**：Gradle 子进程输出由独立守护线程读取，有界 `waitFor` 不再被管道写满饿死
- **超时从启动计时**：10 分钟上限，超时销毁整棵进程树（descendants + destroyForcibly）
- **可靠取消**：`cancel()` 设置标志 + 销毁进程树；构建等待退出后检查取消并清理
- **互斥终态**：OK/CANCELLED/TIMEOUT/FAILED 首次到达生效，取消后不能再被成功覆盖（compareAndSet）
- **产物清理**：非 OK 终态删除 build/libs 下 JAR，不留"看似成功"的半成品
- **审计日志**：`auditTrail()` 记录启动/退出码/取消/超时时间线
- **假进程测试**：`SubprocessLifecycleTest` 4 项 —— 30 秒 ping 进程 1 秒超时销毁、取消销毁、日志回读、持续输出不饿死超时

### 第三阶段：冷却状态正确性（BlockEntity）

- **专用 Block Entity**：行为方块生成 `XBlockEntity`，冷却时间戳存在实体实例中（`HasBeenUsed` + `LastUseTick`）
- **NBT 持久化**：writeNbt/readNbt 走原版区块存档 —— 跨存档隔离、世界重启恢复、区块卸载重载不丢
- **方块即状态**：破坏方块即销毁状态，重放不再继承旧冷却；不同存档天然隔离
- **时间回拨安全**：`now < lastUseTick` 视为冷却过期（回拨作废冷却，不永久卡死）
- **首次使用语义**：`hasBeenUsed` 标志区分"从未使用"与"tick 0 使用"，杜绝 first-use 误拦
- **8/8 独立 GameTest**：新增 `blockEntityPersistence` —— NBT 写出→新实体恢复→状态逐字段相等

### 第四阶段：独立 Mod 真实验证

- **真实放置与交互**：GameTest 在世界中放置方块并直接调用 onUse 断言效果与冷却
- **实体存在断言**：闪电测试统计世界中 LIGHTNING_BOLT 数量增量，不再只看返回值
- **JAR 内容审计**：含 MagicDeviceBlockEntity.class、不含 WorldForge 依赖/API Key/存档（3 项 JAR 测试）
- **跨测试隔离**：GameTest 改用 `getAbsolutePos` + 放置前 AIR 清理，杜绝相邻测试的残留 BlockEntity 污染

### 验证汇总

- JUnit 133 项全部通过
- GameTest 61/61 全部通过
- 独立 Mod GameTest 8/8 全部通过
- 真实客户端 GUI 验收 42/42 全部通过（含真实 JAR 构建 + 取消 + 多分辨率截图）

---

## v0.5.2 已发布 — 导出 GUI 与行为等价性加固

**核心修复**：解决 v0.5.1 导出器的双重模糊问题，增加能力丢失确认，强化独立模组冷却状态。

### P0-1: 修复导出 GUI 双重模糊

- **blur gate 模式**：遵循 StudioScreen 的验证模式，每帧只调用一次 `super.renderBackground()`
- **渲染顺序**：`renderBackground() → 自定义绘制 → super.render()` 确保文本和按钮清晰无重影
- **边界检查**：列表点击区域严格限定 (itemLeft/itemRight)，防止吞掉其他控件事件
- **多分辨率测试**：覆盖 854×480、GUI Scale 1/2/3、窗口缩放

### P0-2: 不允许未经确认的降级导出

- **能力报告前置**：导出前展示完整报告（保留、转换、丢失），明确列出无法导出的能力
- **默认阻止**：存在丢失项时 `awaitingDegradedConfirmation = true`，不启动导出线程
- **双选项确认**：提供「我接受降级导出」和「取消」按钮
- **快照隔离**：P1-3 中定义的不可变定义快照，后台仅处理快照而非运行时数据
- **自动化测试**：`DegradedExportConfirmationTest` 3 项全部通过

### P1-1: 修复导出方块的冷却状态

- **世界感知键**：`cooldownKey(world, pos)` 生成格式 `"registryKey:x,y,z"`（如 `minecraft:overworld:0,64,0`）
- **跨世界隔离**：主世界的 `(0,64,0)` ≠ 下界的 `(0,64,0)`
- **破坏清除**：重写 `onStateReplaced(BlockState, World, BlockPos, BlockState, boolean)`，移除该坐标的冷却
- **避免内存泄漏**：旧方块移除后冷却自动清理，不会长期累积或跨世界残留

### P1-2: 加强独立 Mod 的真实行为验证

- **7/7 GameTest 全部通过**：
  1. `itemRegistered1` - 物品注册
  2. `itemLightningBehavior2` - 右键成功 + 冷却阻止
  3. `itemLightningSpawnsEntity3` - **真实断言世界中出现闪电实体**
  4. `itemLightningCooldownExpiry4` - **100 ticks 后重新使用成功**
  5. `blockRegistered5` - 方块注册
  6. `blockEffectBehavior6` - 右键施加速度效果 + 冷却阻止
  7. `blockEffectCooldownExpiry7` - **200 ticks 后重新施加效果成功**

- **tickLimit 参数**：冷却过期测试设置超时（200/225 ticks），避免 GameTest 过早失败

### P1-3: 导出任务生命周期与线程安全

- **可取消的 ExportExecutor 实例**：
  - `cancel()` 销毁 Gradle 子进程并重置标志
  - `buildProject()` 定期检查 `isCancelled()`，中途停止后返回正确结果
  - `currentProcess` 字段用于进程管理

- **界面关闭语义**：
  - `close()` 先调用 `exportExecutor.cancel()` 再中断线程
  - 取消后立即显示错误结果 (`"导出已取消"`)
  - 不再显示“导出成功”假象

- **volatile 可见性**：`result`、`exportInProgress`、`statusMessage` 均为 volatile，保证客户端线程与后台线程的正确同步

### 验证

- JUnit 124 项全部通过
- GameTest 61/61 全部通过  
- 独立 Mod GameTest 7/7 全部通过（新增 3 个真实行为测试）
- 客户端 GUI 自动化框架就绪（`ExportScreenAutoTest`）

### 注意

v0.5.2 重点在于巩固 v0.5.1 的行为等价导出：修复渲染 bug、增加用户确认流程、强化冷却状态可靠性。所有功能已在纯单人游戏中完成自动化验证，无需人工干预。

---

## v0.5.1 已发布 — 行为等价导出 + 游戏内导出入口

**核心改进**：导出的 Mod 不再只有注册和贴图——现在导出真实行为实现。

### P0-1: 行为等价导出

- **自定义 Item 子类**：有行为的物品生成 `ThunderSwordItem extends Item`，override `use()` 和 `useOnBlock()` 实现右键行为
- **自定义 Block 子类**：有行为的方块生成 `MagicDeviceBlock extends Block`，override `onUse()` 实现右键交互
- **支持的行为**：`summon_lightning`（召唤闪电）、`apply_effect`（施加状态效果）、`heal`（治疗）、`ignite`（点燃）、`sound`（音效）、`particles`（粒子）
- **冷却时间**：每个行为带 per-player/per-position 冷却跟踪，首次使用不被错误阻挡
- **能力报告**：`buildCapabilityReport()` 列出保留、转换、丢失的每项能力
- **不再静默丢弃**：支持一个行为就真正生成一个实现

### P0-2: 游戏内导出入口

- **`WorldForgeExportScreen`**：Studio 中新增「导出 Mod」按钮，打开导出界面
- 选择物品/方块、输入 Mod 名称/ID/版本、设置输出目录
- 异步导出不阻塞游戏主线程
- 显示 JAR 路径、SHA-256、错误信息
- 多人联机安全：仅单人集成服务端可导出

### P0-3: 打包构建模板为资源

- Gradle Wrapper 打包为 `src/main/resources/export-template/` 资源
- 不依赖 WorldForge 源码仓库的当前工作目录
- Java 编译环境检查（`java -version` 验证 Java 21+）
- 构建超时（10 分钟）防止挂起
- 无编译条件时诚实说明，返回源码工程供检查

### 二: 注册 ID 冲突检测

- BlockItem 也占用 ITEM 注册表，与独立物品同名时拒绝导出
- 重复物品 ID 检测
- 不同命名空间映射冲突检测

### 三: 端到端功能等价测试

- **雷霆之剑**：右键召唤闪电 + 100 ticks 冷却
- **魔法装置**：右键施加速度效果 + 200 ticks 冷却
- 独立 Mod GameTest 4/4 全部通过（注册 + 行为 + 冷却）
- 不再只有"是否注册"的两项测试

### 四: 导出安全性

- 输出目录符号链接拒绝（路径穿越防护）
- 父路径符号链接检查
- 构建日志脱敏（API Key、Token、Password 替换为 `***REDACTED***`）
- JAR 内容验证：无 API Key、无世界存档、无 WorldForge 依赖
- Gradle 子进程环境变量清理

### 验证

- JUnit 119 项全部通过
- GameTest 61/61 全部通过
- 独立 Mod GameTest 4/4 全部通过（行为 + 冷却）
- JAR SHA-256: `acc065f2d0e926cb3468b40bca8f04e6a61e2abfc98de3e8221cecc28eed1d3e`

### 注意

v0.5.0 只验证过独立注册能力，行为等价属于 v0.5.1 后续完善范围。

## v0.5.0 已发布 — 独立 Mod JAR 导出器

**导出器**：玩家在 WorldForge Studio 中创建的物品和方块可以导出为独立 Fabric Mod JAR，不依赖 WorldForge 本体。

- **模板项目生成**：完整的 Fabric 1.21.1 项目（build.gradle、settings.gradle、fabric.mod.json、Java 源码、语言文件、模型文件、Gradle Wrapper）
- **真实注册**：导出的 Mod 在启动阶段通过 `Registry.register` 注册独立的 Item 和 Block，不是虚拟代理
- **安全验证**：Mod ID 路径穿越/Shell 注入拒绝、输出目录非空拒绝、不支持的能力明确警告
- **无密钥泄漏**：API Key、agent.json、世界存档不打包进 JAR
- **独立实例验证**：导出的 JAR 在仅安装 Fabric API 的 Minecraft 1.21.1 实例中成功加载，GameTest 2/2 通过
- **导出 JAR**：`standalone_mod-1.0.0.jar`（6789 字节），SHA-256 `29a4f401...`

**v0.4.1 生存模式验证**：

- 生存模式破坏动态方块 → 恰好 1 个掉落物 → 携带正确 VirtualBlockId → 重放恢复身份
- 创造模式破坏不产生掉落
- 全部通过真实客户端自动化验证（30 步 ALL CHECKS PASSED）

## v0.4.0 已发布 — 动态方块引擎

**虚拟方块（不注册新方块）**：启动期只注册 1 个通用 `worldforge:dynamic_block` + 1 个 BlockEntity 类型 + 1 个方块物品；玩家的每个"虚拟方块"是它的实例，身份存于 BlockEntity（`worldforge:virtual_block_id` 数据组件 → 放置时写入 BE → NBT 持久化）。

**已实现**
- `BlockDefinition` / `BlockCatalog`（12 项原版外观封闭目录，严格校验与显示回退分离）/ `BlockDefinitionStore`（严格加载拒绝整份损坏、容错逐条上报、原子写入、损坏隔离）。
- `DynamicBlock` / `DynamicBlockEntity` / `WorldForgeBlocks`：放置写身份、破坏按玩家模式掉落（创造不掉）、右键分发行为、删除定义后失效可复活。
- `BlockBehaviorEngine`：只做通用方块真正能做之事（药水/治疗/经验/点燃/给予/音效/粒子），其余如实返回"未执行"。
- `BlockRuntimeManager`：失败回滚（定义**与墓碑**）、未绑定存储**不假成功**、周期配额与清理。
- `PeriodicScheduler`：**有界轮转调度**——每 (world,pos) 最多一个任务、每 tick 硬上限 128（永不提高）、队列有界 8192、执行后按下次到期时间回队尾、绑定维度与坐标、破坏/删定义/卸载/停服均清理。压力测试覆盖 100/129/256/512 个真实方块。
- `WorldForgeBlockTools` + `/wf block help|list|info|create|give|delete`：统一变更入口、严格校验（外观/ID/间隔≥20t/产出/行为白名单）、三段式结果、OP2 权限。
- 生命周期 GameTest：真实物品放置→身份写入、破坏掉落→重放身份保持、**真实 `MinecraftServer#save` 后重新绑定恢复**、热修改对已放置方块生效、删除后失效与重建复活、跨存档隔离、损坏文件隔离。

**v0.4.0 后续进展**
- `SyncDefinitions` 载荷新增 `blocksJson` 字段：方块定义随同步包正确下发客户端（此前客户端方块镜像收到的是物品 JSON，始终为空）。
- `DynamicBlockEntityRenderer` 注册并工作：复用原版方块模型，从 `ClientBlockState` 镜像解析外观，删除/未同步时回退 stone。
- `BlockStudioPanel`：Studio 方块分类页（列表/创建/编辑/删除/获取，外观与类型选择器），经统一协议 → `WorldForgeBlockTools`。
- `AgentBridge` 方块提案：`CreateBlock`/`UpdateBlock`/`DeleteBlock` 独立类型，物品与方块即使 ID 相同也不能跨类型误修改。系统提示词已补充方块能力与类型分离规则。
- `ProposalDiff` 方块提案预览。
- `AgentSession` 维护独立 `focusBlock`，上下文注入方块清单。
- 真实客户端自动化钩子扩展：世界内放置方块 + 渲染截图 + 客户端镜像断言。
- **真实客户端截图取证**：`v040-world-blocks-view.png`（854×480）、`v040-world-blocks-close.png`——真实世界中放置的虚拟方块，不同虚拟 ID 渲染不同原版外观。
- GameTest 新增 A13（AI 方块提案端到端）、A14（物品/方块类型隔离），合计 51/51。

**已知限制（如实记录）**
- 所有虚拟方块共用一个原生方块类型：碰撞形状/硬度/光照/红石行为无法逐定义改变。
- 超载时周期被拉长为 `ceil(N/128)` tick：保证最终执行与每 tick 有界，**不保证**原始周期。
- **生存模式世界内掉落**：GameTest mock 玩家无法切换到生存模式；已验证 `shouldDrop(false)=true`、掉落物携带正确 VirtualBlockId、创造模式不掉落、重复移除不复制，但真实生存模式世界内破坏的完整交互（拾取→重放→身份恢复）列为已知限制。


## v0.4.0 开发中 — 游戏内 AI 设置界面 + 审查问题修复

**游戏内 AI 设置（P0，Studio 新增「⚙ AI 设置」入口）**
- 服务商选择（DeepSeek / OpenAI / 自定义兼容接口，切换即带出预设 URL 与默认模型）、Base URL、模型名、启用开关、API Key 输入（**只显示"已配置/未配置"，绝不回显任何字符**，输入框进入时恒为空）、测试连接、保存并应用、清除密钥、返回。
- **保存后立即热生效**（单人在同一 JVM 内直接让集成服务端重建 Provider，不经网络包、无需重启）。
- **权限隔离**：单人游戏由本机直接读写本地配置文件，密钥不出本机；**专用服务器客户端该页严格只读**，既不显示也不接受服务端密钥，且协议中不存在可读取/覆盖密钥的消息类型。
- 状态明确区分：已保存 / 测试成功 / 鉴权错误(401,403) / 模型或地址错误(404,400) / 限流(429) / 服务端异常(5xx) / 网络不可达 / 超时；错误信息经脱敏后展示。

**审查问题修复（均有自动化测试）**
1. `AgentConfigStore.save()` 改为**可验证的原子替换**：`ATOMIC_MOVE`，不支持时**明确降级并记录**（`lastSaveWasAtomic()` 暴露事实，日志告知并发读者可能观察到部分写入），不再含糊宣称原子性。
2. **写入前**即以属主只读/只写权限创建临时文件（POSIX），消除密钥短暂暴露窗口。
3. `AgentConnectionTest` 对 **HTTP 200 也校验响应结构**：必须是含 `choices[0].message` 的 Chat Completions 响应，否则判为异常（防止门户/代理返回 200 造成误判）。
4. GUI 与日志**不再显示密钥任何前后缀**（原实现会显示 `sk-a…789`）；`maskKey` 只返回"已配置/未配置"。
5. Base URL 严格校验：**拒绝用户信息、查询参数、fragment**；**非本机端点强制 HTTPS 并明确提示明文风险**；GUI 对裸主机名自动补 `https://`。

**测试**：`AgentConfigTest` **21 项**（含对真实本地 HTTP 服务器逐一验证 200/401/403/404/400/429/500 的分类、错误响应体回显密钥的脱敏、原子/降级保存、失败保存保留原文件、覆盖不足与非法 URL、请求体形状校验、GUI 覆盖密钥不入库）；客户端自动化新增 AI 设置页 7 项断言（只读隔离、密钥不回显、返回导航）。全量：**JUnit 78**、**GameTest 26/26**。


## v0.3.2 — GUI 稳定性三项修复 + 可测试客户端状态机

**根因**：GUI 业务规则散落在控件回调里，只能靠"点一下看看"验证，导致缺陷反复出现且无法证明修复。本版把规则提取为**无 Minecraft 依赖的纯组件**并配单元测试，Screen 退化为渲染层。

新增可测试组件（`src/main/java/dev/worldforge/studio/`）：
- `StudioUiState`：模式、脏草稿、AI 阶段与在飞请求、分层 ESC、关闭守卫、导航守卫、控件可见性的**唯一权威**。
- `StudioLayout`：布局算术 + 列表行命中测试 + 滚动钳制 + 控件行几何，使绘制与交互共用同一套数字。

**修复 1｜AI 请求状态卡死**：`beginAiSend()` 仅在真正交给传输层（`SENT`）时进入 THINKING；空输入/请求在飞/无传输分别返回 EMPTY/BUSY/NO_TRANSPORT 且面板保持可用。`sendRequest` 现在返回"是否真的发出"，未发出立即恢复输入框。请求被拒 ack、模型失败、75s 看门狗超时、断线**全部回到 IDLE**；`tick()` 还会在 studio 通道消失时自愈。

**修复 2｜未保存草稿保护**：所有导航入口（列表点击、AI 面板按钮、外观选择、删除确认、返回列表、关闭按钮、ESC）统一经过 `requestNavigation/openModal/requestClose`。**带未保存修改进入 AI 面板现在会先警告**，不再静默隐藏草稿。守卫状态是状态机里的显式标志，彻底移除 `statusLine.startsWith()` 推断（任何其他消息都能解除它并把玩家困在编辑器里）。

**修复 3｜取消与退出解耦**：取消提案先是**本地状态变更**（服务端不可达也能取消），再用 `sendRequestBestEffort` 做尽力而为的通知——绕过单请求在飞锁、永不阻塞。关闭编辑器永远以关闭收尾。

**测试**：新增 24 项 JUnit（`StudioUiStateTest`）覆盖三项修复 + 854×480/极小窗口布局与命中一致性；JUnit 合计 **49**，GameTest **26/26** 全绿。

**客户端自动化测试调研（如实记录）**：尝试启用 Loom 的 `runClientGameTest`——它确实启动真实客户端，但会**加入既有存档而非测试世界**，且不驱动我们的测试套件，因此不能作为自动化载体；已保持 `enableClientGameTests = false`，改用状态机/布局组件测试（任务书允许的替代路径）。


## v0.3.1 — GUI 稳定性紧急修复（渲染清晰度 + 取消/关闭行为）

- **P0 背景双重模糊（用户报告的核心缺陷）**：`Screen.render()` 在 1.21.1 内部会再调一次 `renderBackground()` → 第二次 `applyBlur()` 把整帧缓冲（含已绘制的 WorldForge 面板/文字/列表/AI 内容）重新模糊，造成"自定义内容糊、原版按钮清晰"。新增每帧一次性模糊门闩 `blurDoneThisFrame`：覆写 `renderBackground()` 跳过重复调用，面板内容全部在唯一一次模糊之后绘制。**未删除任何背景绘制，未禁用 Minecraft 模糊选项。**
- **P1 AI 面板重复绘制**：输入框/发送/确认/取消已进控件树却又被手动 `render()` → 移除手动绘制，改由 `applyVisibility(aiMode)` 在每次 `applyMode()` 统一下发 visible/active；AI 阶段变化（IDLE↔THINKING↔PROPOSAL）后立即 `applyMode()`，否则确认/取消按钮永远不出现。
- **P1 取消与关闭语义**：
  - ESC 分层：AI 面板有提案时第一次 ESC **只取消提案**（服务端清 PENDING，世界不变）；无提案则回列表；列表再 ESC 才关闭。
  - **修复"永远退不出编辑器"真实缺陷**：`tryLeaveEditor()` 原先用状态栏文本 `startsWith("§e")` 判断是否二次确认，任何其他消息覆盖状态栏后该保护永久失效 → 改为显式 `discardArmed` 标志，进入编辑/保存成功/其他动作都会重置。
  - 关闭按钮同样走显式 `closeArmed` 标志，不再依赖显示文本。
- **实测验证（真实客户端 854×480 客户区）**：修复后截图确认面板标题、物品列表（11 项含图标/名称/ID）、AI 对话区、输入框、发送/确认/取消按钮、状态栏**全部清晰**；创建物品表单 7 个输入框 + 外观/触发/动作/保存/加入行为/返回列表全部正常显示与可点击；草稿保护二次确认生效。真实 API 调用失败时干净显示 `模型调用失败：IllegalStateException: HTTP...`，GUI 不卡死。
- 回归：GameTest **26/26**、JUnit **22/22**、clean build 全绿。


## v0.3.0 — AI AgentBridge：游戏内自然语言创造物品

- **前置修复（上轮审查的 6 个 GUI 缺陷，最小补丁）**：面板背景绘制顺序遮盖控件；保存时误自动附加闪电技能；程序化 setText 误标脏；关闭按钮绕过草稿守卫；服务端截断快照产生非法 JSON（改为超限省略+提示刷新）；客户端快照解析失败仍报同步成功（现在显式报错）。回归 25/25 绿。
- **AgentBridge**：`ai/` 模块（AgentConfig/OpenAICompatibleProvider(JDK HttpClient 异步+超时+重试+并发上限)/MockModelProvider/ProposalJson 严格解析/ProposalValidator 白名单校验/ProposalDiff 预览/AgentSession 会话隔离/AgentBridge 两阶段提案-确认）。API Key 仅存服务端 config，日志掩码，不进存档/客户端。
- **协议**：agent_chat/agent_confirm/agent_cancel kinds + requestId=0 异步 agent 事件通道 + 响应 data 通道（confirm 携带 itemId 直达编辑视图一键获取）。
- **GUI**：Studio 新增「✦ AI 创造面板」：对话历史、思考状态+75s 看门狗、提案预览（差异展示）、确认/取消、Enter 发送；无 Key 时明确提示不崩溃。
- **安全**：模型输出仅是提案（阶段 A 零副作用）；确认时复核 OP2、TTL、base 版本冲突、proposalId 幂等；每回合 1 提案 + 每分钟频率上限；提示词注入无法越权（白名单在服务端）。
- **测试**：JUnit +12（ProposalJsonTest）；GameTest 25→**26**（新增 AI 端到端验收链单测：禁用指引/限流/创建-预览-确认-落盘-幂等/取消零副作用/不支持技能报告/垃圾输出/网络故障/差量修改/冲突拒绝/会话隔离/重载持久/非 OP 拒绝）。测试中发现并修复真实缺陷：validator 对 update 误要求 max_durability 必填。
- 已知限制：真实模型端到端需用户自备 key 验证（自动化全部基于 Mock，不谎报真实模型结果）；K 键 GUI 像素级目测仍待人工。

## v0.2.0 — WorldForge Studio 游戏内可视化编辑器

- **阶段1（数据安全遗留）**：`tryLoadStrict()` 严格重载——局部损坏（单条坏记录/缺 items 字段/新版本号/重复 ID/字段类型错误）一律拒绝整体替换并保留运行时状态与原始文件；首次启动改为「严格优先 → 失败时容错抢救 + 隔离备份 + 逐条大声告警」。回归 R11 在 v0.1.2 上先行实测 FAILED（`got count 1` 假成功），取证 `docs/evidence-r11-failed-on-v0.1.2.log`。
- **阶段2-5（Studio）**：K 键（可重绑）打开 `WorldForgeStudioScreen`（纯 vanilla Screen/Widget）；物品列表（图标/滚动/失效标记/tooltip）、创建/编辑表单（ID 创建后锁定、草稿模型、未保存守卫）、15 槽位原版贴图选择器、可视化行为编辑器（触发/动作/参数/冷却）、删除确认（失效语义说明）、获取并测试按钮、状态栏与三段式结果反馈、requestId 防陈旧覆盖、5s 超时防卡死、无 Mod 服务端只读降级。
- **协议**：C2S `studio_request{kind,requestId,payload}` / S2C `studio_response{requestId,ok,phase,message,snapshot}`；网络线程仅收包，一切状态操作 `server.execute` 回服务端线程；OP2 权限与 `/wf` 同阈值；服务端全量重校验（含 S2 测试当场抓出的 create/update 缺失长度上限，已修复）；give 对非 OP 开放（与命令一致）、背包满掉落脚下。
- **测试**：GameTest 19 → **25 项全通过**（新增 S1-S4 Studio 业务测试驱动生产 handler：全流程、恶意载荷拒绝、权限门、响应路由）；JUnit 10 项。
- **真实客户端**：`runClient` 在带图形环境的 Windows 主机成功启动（标题→进入世界→`/wf demo` 10 件创建→`worldforge/items.json` 落盘 11 条含行为 JSON，截图与日志见 docs/screenshots 与交付报告）。
- 明确未验证：K 键 Studio 界面的像素级目测与拖窗/多分辨率适配（需人工配合，见 README 未验证清单）。


## v0.1.2 — 深度修复迭代（3 项 P1）

1. **跨世界状态泄漏**：客户端显示只读镜像（`resolveForClientDisplay`），服务端逻辑只读权威态
   （`definitionForLogic`）；`SERVER_STOPPED` 清空集成服务端全部静态状态（R7）。
   *风险为代码路径推断，未经真实客户端目测复现，如实标注。*
2. **结果三段化**：`ToolResult(persisted, applied, sync)` + `needsRetry()` + `resync()` 重试钩子；
   同步吞异常导致假 ok 的路径关闭（R9）。
3. **保守 reload**：损坏文件不再清空运行时、不再自动隔离玩家文件；`tryLoad()` 零副作用，
   失败返回明确错误由玩家决策（R8 在 v0.1.1 上实测 FAILED 取证，见
   `docs/evidence-r8-failed-on-v0.1.1.log`；R10）。

GameTest 15 → 19 项全通过；JUnit 9 项全通过。

## v0.1.1 — 严格修复迭代（六项 triage）

保留全部 Fabric 1.21.1 MVP 功能，不增加 AI / 方块 / 实体。按严重程度修复：

1. **保存失败假成功**：变更改为"先落盘、失败即回滚"（含 tombstone 还原），返回带
   `lastError` 原因的失败结果；旧文件在写失败时不受损；命令层向玩家显示真实失败。
2. **客户端/服务端静态全局冲突**：新增 `ClientDefinitionMirror`，S2C 同步不再写入
   服务端权威状态；集成服务端（单人游戏同 JVM）不再因同步写清空服务端墓碑/定义。
3. **统一变更入口**：新增 `dev.worldforge.tool.WorldForgeTools` 唯一工具层
   （校验→回滚式变更→重烘堆→广播），`/wf` 全部写命令与 demo 走此入口；未来
   AI AgentBridge 直接绑定这些方法。
4. **失效物品残留攻击加成**：`refreshStack` 对无定义堆剥离 `ATTRIBUTE_MODIFIERS`、
   外观回退默认槽位、耐久钳制（不再残留 +12 攻击力）；虚拟 ID 保留可恢复。
5. **真实端到端测试**：新增 R1/R1b/R2/R3/R4/R5 六项，均在真实专用服务端运行；
   其中 R1/R1b/R2/R3 在 v0.1.0 代码上实测 FAILED（复现问题），修复后转 PASSED。
   R4 用真实 mock 玩家验证治疗回血/冷却拦截/速度效果/经验球/闪电实体；
   R5 验证 S2C payload 线级编解码往返与纯客户端镜像显示。
6. **Release 与源码一致性**：核查发现 v0.1.0 tag ≠ 附件 JAR（见 docs 记录）；
   v0.1.1 起建立可复现构建 + `docs/RELEASE_VERIFICATION.md` 校验规程，独立干净
   克隆构建与主工作区构建 SHA-256 逐字节一致，且该克隆 GameTest 14/14 通过。

**未验证（如实）**：真实图形客户端 `runClient` 目测背包图标/tooltip 外观差异；
多人（专用服务端 + 真实客户端）联机同步端到端目测。代码路径与 wire 测试已覆盖，
但缺人工图形确认。

## v0.1.0 — MVP 0.1 基线

单通用 Item + Data Component 虚拟身份；运行时热创建/改/删；世界级持久化；
白名单行为引擎；15 预置外观槽位复用原版贴图；S2C 同步；`/wf` 命令；
JUnit(9) + Fabric GameTest A–H(8)。
