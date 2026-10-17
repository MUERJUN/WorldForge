# WorldForge — Minecraft 运行时动态物品引擎 (MVP 0.1 / v0.1.1)

[![build](https://img.shields.io/badge/build-passing-brightgreen)]() [![MC](https://img.shields.io/badge/Minecraft-1.21.1-green)]() [![Loader](https://img.shields.io/badge/Fabric-Loader%200.16.9-blue)]()

WorldForge 的目标是成为 Minecraft 游戏内的 AI Mod 开发环境：玩家安装一个 JAR，就能在游戏里用自然语言创造物品、方块、实体和规则，实时测试并导出独立 Mod。

**当前版本 MVP 0.1 只完成一个核心技术验证：仅注册一个通用原生 Item，即可在游戏运行期间创建任意多个（验证了 10 种）具有不同虚拟 ID、名称、外观、属性和行为的物品，并完整支持运行时热修改、世界级持久化与退出重进恢复。**

- 游戏版本：Minecraft Java **1.21.1**
- 加载器：Fabric Loader **0.16.9** + Fabric API **0.116.5+1.21.1**
- 映射：Yarn **1.21.1+build.3**；Java **21**；Gradle Wrapper **8.14.3**（distributionUrl 已指向腾讯镜像，可改回 services.gradle.org）

---

## 一、快速开始

### 构建

```bash
# 需要 JDK 21
./gradlew build
# 产物：build/libs/worldforge-0.1.0.jar
```

### 运行

```bash
# 真实游戏内 GameTest（无需图形界面，自动跑 A~H 全套验收测试）
./gradlew runGameTest

# 普通客户端（配合 K 键？GUI 面板暂未实现，用命令即可）
./gradlew runClient

# 普通专用服务端
./gradlew runServer
```

### 游戏内验收流程（对应最终验收标准）

1. 安装 `worldforge-0.1.0.jar` + Fabric API 到 `.minecraft/mods`，或 `./gradlew runClient`；
2. 进入世界，`/wf demo` —— 一键创建 10 种虚拟物品（**不重启**）；
3. `/wf give thunder_sword`、`/wf give healing_staff` …… 获取物品；
4. 背包里 10 种物品名称、描述、贴图外观、攻击伤害、耐久、附魔闪光各不相同；右键/攻击触发技能并有冷却提示；
5. `/wf setdamage thunder_sword 20` —— **背包里已有的那把剑立即变为 20 攻**（tooltip 与玩家属性同步更新）；
6. 退出世界再进入：`/wf list` 定义仍在；旧物品堆仍显示正确的名称、外观和行为；
7. `/wf delete thunder_sword` 后背包里的旧剑显示红色“已失效”状态，不崩溃、不被删除；
8. 换一个存档进入：定义互相隔离（每个世界的 `worldforge/items.json` 独立）。

## 二、架构

```text
src/main/java/dev/worldforge/
├── WorldForge.java            # ModInitializer：组件→物品→网络→事件→命令，注册顺序固定
├── registry/WorldForgeItems   # 唯一原生 Item 注册：worldforge:dynamic_item
├── component/                 # 数据组件（真实 1.21.1 Data Components 机制）
│   ├── VirtualId              #   worldforge:virtual_id —— 堆的虚拟身份（持久化+网络同步）
│   ├── BehaviorState          #   worldforge:behavior_state —— 堆级冷却状态
│   └── WorldForgeComponents   #   ComponentType 注册（Codec + PacketCodec）
├── definition/                # 纯 JVM 数据层（可脱离 MC 单元测试）
│   ├── ItemDefinition         # 统一虚拟物品模型：版本/id/名称/描述/贴图/属性/行为
│   ├── BehaviorDefinition     # trigger + actions[] + parameters + cooldown_ticks
│   ├── TextureSource          # vanilla/mod/generated/custom（后两者降级到默认槽位）
│   ├── AppearanceSlots        # 15 个预置外观槽位 + 原版贴图智能匹配
│   └── DefinitionValidator    # 校验 + 默认值 + 贴图容错
├── runtime/WorldForgeRuntimeManager # create/get/update/delete/list/reload/save，tombstone 失效标记
├── persistence/DefinitionStore      # 世界目录 worldforge/items.json：原子写、损坏隔离、逐条容错
├── behavior/BehaviorEngine    # 白名单行为分发：right_click/hit_entity × 8 种动作，服务端线程
├── network/WorldForgePayloads # S2C 定义快照同步（登录时 + 每次修改后）
├── command/                   # /wf help|list|info|create|give|setdamage|delete|reload|demo
│   └── DemoDefinitions        # 10 种演示物品（普通运行时定义，非原生注册）
├── item/DynamicItem           # 通用物品：名称/tooltip 每次从定义实时解析；属性/槽位烘焙到堆
├── event/WorldForgeEvents     # 服务器生命周期 + 玩家加入；只注册一次（防重复监听）
├── test/WorldForgeGameTests   # Fabric GameTest：验收测试 A~H，真实服务端运行
└── extension/ExtensionPoints  # 未来扩展点说明（Project/Block/Entity/Asset/Agent/Exporter）

src/client/java/dev/worldforge/client/  # 客户端：接收定义同步（名称/tooltip 渲染）
src/main/resources/
├── fabric.mod.json            # 三个入口点：main / client / fabric-gametest
└── assets/worldforge/
    ├── models/item/dynamic_item.json    # 基础模型 + 15 条 custom_model_data 覆盖
    ├── models/item/wf_slot_1..15.json   # parent 直接指向原版模型（复用原版贴图，零 PNG 拷贝）
    └── lang/zh_cn.json, en_us.json
```

### 核心机制说明

**1. 单物品 + 虚拟身份**
原生注册表里只有一个 `worldforge:dynamic_item`（测试 A 在真实注册表上断言数量 == 1）。每个物品堆通过自定义数据组件 `worldforge:virtual_id`（String，带 Codec 持久化 + PacketCodec 网络同步）携带自己的虚拟 ID。**没有**预注册 10 个 Item，**没有**注册表冻结后的强注册 hack，**不是**只改显示名称——伤害/耐久/攻速通过堆上的 `ATTRIBUTE_MODIFIERS`、`MAX_DAMAGE`、`CUSTOM_MODEL_DATA` 等标准数据组件真实生效。

**2. 热修改模型**
显示名、描述、tooltip 在每次读取时从运行时管理器实时解析——改名/改描述立刻对所有存量物品堆生效。数值属性（伤害、耐久）和外观槽位因为原版从堆组件读取，采用“烘焙 + 重刷”策略：定义更新时对所有玩家背包执行幂等的 `refreshStack`（`/wf setdamage`、加入世界、重载定义时都会触发），存量堆即刻使用新属性。

**3. 外观 = 预置槽位 + 原版贴图复用**
1.21.1 的真实机制：`dynamic_item.json` 模型上声明 15 条 `custom_model_data` 覆盖谓词（已在反编译的 `ModelOverrideList`/`ModelPredicateProviderRegistry` 源码核实）。槽位模型 `parent` 直接引用原版物品模型（`minecraft:item/diamond_sword` 等），因此**复用原版贴图、未复制任何 PNG**。定义里的 `texture.source` 支持 `vanilla/mod/generated/custom`；未实现的来源自动降级：**贴图问题永远不会导致物品创建失败**。多个虚拟物品可以共用槽位，身份完全独立。
> 诚实声明：这是 JAR 内预置外观槽位（15 个），不是无限动态贴图。运行时生成纹理/动态资源包属于后续 AssetManager 里程碑。

**4. 行为引擎 = 白名单解释器**
监听入口只在 `DynamicItem`（一个原生 Item 类）上注册一次；运行时新增的定义经由这些既有入口分发。触发器 `right_click`/`hit_entity` × 动作 `summon_lightning/apply_effect/heal/teleport/explosion/experience/ignite/damage`，参数来自 JSON。不执行任何生成代码。安全限制：爆炸威力钳制 0.1~4.0 且默认无火、闪电限 48 格内、传送限 64 格且搜索安全落点、效果时长/等级钳制。世界修改全部在 `Item#use`/`postHit` 的服务端线程路径上执行。

**5. 持久化 = 世界目录级隔离**
每个存档的 `worldforge/items.json`（`{version, items[]}`）。写入采用临时文件 + 原子 move；文件损坏时移入 `items.json.broken-<ts>` 保留证据并以空集继续（下次保存成功）；单条非法定义逐条跳过。堆的身份靠数据组件随物品 NBT 一起保存，重进世界自动恢复。服务器启动时加载、停服时保存；客户端镜像通过 S2C 同步。

**6. 失效状态**
删除定义后，存量堆保留虚拟 ID 但获得 tombstone：名称显示 `§6[已失效]§r xxx`、tooltip 红字说明、右键返回 FAIL 并提示，不崩溃、不销毁物品（测试 F）。以相同 ID 重建定义后堆自动恢复功能。

## 二·五、v0.1.1 修复迭代（严格 triage）

按严重程度完成六项修复，全部遵循"**先复现 → 新增在修复前失败的回归测试 → 修复 → 全量重跑**"流程：

| # | 问题（v0.1.0 实际源码复现） | 修复 | 回归测试（修复前结果 → 修复后结果） |
|---|---|---|---|
| 1 | **保存失败假成功**：`createItem` 先改内存再 `save()`，`save()` 返回 false 被无视 → 内存/磁盘永久分歧，命令仍报“创建成功” | 变更=先落盘、失败即回滚（含 tombstone 还原）并返回带 `lastError` 原因的失败结果；`SERVER_STOPPING` 最终落盘失败会打 ERROR 日志；命令层向玩家显示保存失败 | `testR1`、`testR1b`（用目录占据 items.json 路径确定性制造写失败）**修复前 FAILED → 修复后 PASSED** |
| 2 | **客户端/服务端共享静态全局**：集成服务端（单人游戏，同一 JVM）里 S2C 同步写回同一个静态 `DEFINITIONS/TOMBSTONES`，可清空服务端墓碑与定义 | 新增 `ClientDefinitionMirror`（仅客户端写入）；显示路径“服务端优先、镜像兜底”；`refreshStack` 只信服务端权威；同步 payload 扩展 tombstone 列表 | `testR2`（镜像写入后断言服务端墓碑仍在、且不反 leak）**修复前 FAILED → 修复后 PASSED**；`testR5` 覆盖 payload 编解码往返 + 纯客户端镜像显示 |
| 3 | **变更入口分散**：命令直接调 manager、各自散落 sync/refresh 调用，未来 AI Agent 无统一安全面 | 新建 `dev.worldforge.tool.WorldForgeTools` 唯一变更入口（校验→回滚式变更→重烘堆→广播同步一体完成）；`/wf` 全部写命令与 demo 改走工具层；AgentBridge 将来直接绑定这些方法 | 由既有 B/D/E/F/G/H + 新增 R 系列共同覆盖工具路径 |
| 4 | **失效物品残留攻击力**：删除定义后 `refreshStack` 对无定义堆直接 return，+12 攻击加成、原外观槽位、耐久全部留在堆上 | 无效堆剥离 `ATTRIBUTE_MODIFIERS`、外观回退默认槽位、耐久钳制；虚拟 ID 保留（可恢复）；广播时连同光标槽一起重烘 | `testR3`（删除后断言属性修改器消失）**修复前 FAILED → 修复后 PASSED** |
| 5 | **缺真实端到端**：技能只测了 ActionResult，无实体/效果断言；无重启进程恢复、无网络层测试 | 新增 `testR4`（真实 mock ServerPlayerEntity：治疗实际回血、冷却实际拦截、速度效果真实挂上、经验球真实生成/吸收、闪电实体真实出现）、`testR5`（PacketCodec 线级往返）；跨进程重启恢复通过持久 run 目录两次连续运行取证（第二次启动日志 `loaded N definitions` 来自第一次落盘文件） | 修复后 14/14 GameTest PASSED（含以上全部） |
| 6 | **Release 与源码不一致**：核查发现 v0.1.0 tag 指向 commit `cb9cadb`，但 Release 附件 JAR 由之后的 HEAD（`604edde`，删除了 `ClientDefinitionCache`）构建 → tag≠产物 | 建立 `verify-release` 规程：对目标 tag 干净检出→独立构建→SHA-256 比对；v0.1.1 tag 起严格执行并在 `docs/RELEASE_VERIFICATION.md` 记录校验值；v0.1.0 差异以本条记录在案，不回改历史 | 见 docs/RELEASE_VERIFICATION.md（v0.1.1 全流程留痕） |

**诚实说明**：#5 的“客户端视觉（背包图标差异）”“多人服务器联机同步”已实现代码路径并有 wire 级测试，但**尚未在真实图形客户端/双开联机环境人工目测验证**，见“未验证清单”。

## 三、测试与验证结果

| 测试 | 内容 | 验证方式 | 结果 |
|---|---|---|---|
| （编译） | 全源集 main/client/test 编译（v0.1.1） | `./gradlew clean build` | ✅ 编译验证通过 |
| （单测） | 持久化层 9 项纯 JVM 测试：往返/缺失文件/损坏隔离/逐条容错/校验器/贴图降级/默认值/不可变更新/S2C 一致性 | `./gradlew test`（JUnit 5） | ✅ 9/9 通过 |
| A | 原生注册表只有 1 个 worldforge Item | GameTest @ 真实服务端 | ✅ 通过 |
| B | 运行中创建 10 种物品，无需重启 | GameTest @ 真实服务端 | ✅ 通过 |
| C | 10 种 ID/名称互异、≥4 种外观槽位、行为差异、堆上身份互异 | GameTest @ 真实服务端 | ✅ 通过 |
| D | 伤害 12→20 后**存量堆**属性即时更新 | GameTest @ 真实服务端 | ✅ 通过 |
| E | 定义文件往返 + 物品堆 NBT 身份往返 | GameTest @ 真实服务端 | ✅ 通过 |
| F | 删除定义 → 存量堆失效态、不崩溃、行为 FAIL | GameTest @ 真实服务端 | ✅ 通过 |
| G | 3× reload 计数稳定为 10、双重注册事件为 no-op | GameTest @ 真实服务端 | ✅ 通过 |
| H | 真实世界目录写入 `world/worldforge/items.json` 并重载 | GameTest @ 真实服务端 | ✅ 通过（日志+落盘文件为证） |
| R1/R1b | 写失败必须失败返回 + 内存回滚，旧文件不受损（v0.1.1） | GameTest @ 真实服务端 | ✅ 通过（修复前 FAILED 留档） |
| R2 | 客户端镜像写入不得破坏服务端墓碑/状态（v0.1.1） | GameTest @ 真实服务端 | ✅ 通过（修复前 FAILED 留档） |
| R3 | 删除定义后存量堆不得残留攻击加成（v0.1.1） | GameTest @ 真实服务端 | ✅ 通过（修复前 FAILED 留档） |
| R4 | 治疗/冷却/速度效果/经验/闪电在真实 mock 玩家身上生效（v0.1.1） | GameTest @ 真实服务端 | ✅ 通过 |
| R5 | S2C payload 线级编解码往返 + 纯客户端镜像显示路径（v0.1.1） | GameTest @ 真实服务端 | ✅ 通过 |

> 汇总：v0.1.1 `runGameTest` 输出 **All 14 required tests passed**（旧 8 项 + 新 6 项回归/端到端）。
> R1/R1b/R2/R3 在 v0.1.0 代码上运行得到 **FAILED**（错误信息与上表逐条对应），修复后转 **PASSED** —— 回归有效性已实证。
> **已在真实 Minecraft 专用服务端（Fabric + Yarn 映射）验证**；下列“未验证清单”如实列出尚未人工确认的内容。

### 未验证清单（不谎报）
- 真实图形客户端 `runClient` 目测：背包内 10 种物品的图标外观差异、tooltip 渲染、附魔闪光 —— 代码路径与模型 JSON 已核实，但未截图人工确认。
- 多人（专用服务端 + 真实客户端加入）下的同步与失效显示 —— wire 级 codec 测试通过，端到端联机目测未做。
- K 键 GUI 面板 —— 明确未实现（非 MVP 目标）。

## 四、已实现 / 未实现清单

**已实现（MVP 0.1 验收范围内全部完成）**
- 单一通用 Item + Data Component 虚拟身份
- ItemDefinition 数据模型（版本字段、校验、默认值、错误处理）
- WorldForgeRuntimeManager 全部 7 项能力
- 运行时热创建/修改/删除，无需重启；存量堆热生效
- 删除后的可识别失效状态（不崩溃、不丢物品）
- 10 种演示物品（剑×3、法杖×5、护符×2）含 8 种白名单动作
- 预置外观槽位 + 原版贴图智能匹配 + 三级回退（AI→原版→默认，当前无 AI 即走原版）
- 世界目录级持久化、版本化、原子写、损坏恢复、存档隔离
- S2C 定义同步（客户端名称/tooltip 正确渲染）
- `/wf` 全套命令 + 权限分级（写操作 OP2）
- JUnit + Fabric GameTest 双自动化测试体系

**未实现（明确不在 MVP 0.1 范围）**
- K 键 GUI 面板（用 `/wf list/info` 覆盖同等功能）
- AI 生图、运行时动态资源包（`generated/custom` 贴图来源仅解析并降级）
- 真实 LLM Agent / DSH 接入 / 导出独立 JAR
- 动态方块、动态实体（扩展点已文档化于 `extension/ExtensionPoints.java`）

## 五、已知技术限制

1. **外观槽位有限（15）**：超出后不同物品可能共享槽位；1.21.1 无 `item_model` 数据组件（1.21.3+ 才有），采用 `custom_model_data` 覆盖，方案已在源码级核实。
2. **数值属性依赖“重刷”**：定义变更后存量堆靠幂等 `refreshStack` 更新（背包/命令路径均触发）；若方块容器、其他实体持有该堆，在其下次被玩家持有/登录刷新时同步。显示名/tooltip 无此限制，实时解析。
3. **max_stack 全局为 1**：基物品是耐久工具形态（maxDamage 组件），堆叠数属性暂未逐定义生效（会破坏耐久语义）。
4. **冷却状态存于堆组件**：堆被拆分/合并时冷却随堆实例走，属可接受的 MVP 语义。
5. **行为执行需玩家持有者**：`right_click` 需要玩家上下文（发射器等非玩家场景 MVP 直接 PASS）。
6. **tombstone 是运行期内存态**：重进世界后失效物品仍显示“未定义”（灰）而非“已失效”（黄）；物品本身与虚拟 ID 均完好。
7. **GameTest 在专用服务端验证**：客户端 GUI 渲染（图标外观差异）需人工开 `runClient` 目测确认。

## 六、下一阶段建议

- **动态方块（BlockDefinition）**：1.21.1 原生方块注册表同样冻结，可行路线是“通用代理方块 + BlockEntity + 状态组件”或研究运行时注册解冻时机；先做数据模型与持久化对齐 Item 侧。
- **动态实体**：属性/行为数据驱动，实体类型仍走原生（或通用 NPC 代理）。
- **AssetManager**：把槽位目录变成运行时分配 + 临时资源包挂载 + `client.reloadResources`；AI 生图通过它写入，失败自动回落到现有 vanilla 链。
- **AgentBridge**：`/wf` 命令树几乎就是工具面——建议定义 JSON-RPC/工具描述（create_item/update_item/list…），LLM 只调工具、不触字节码；写操作默认进“草稿”状态，由玩家确认落盘。
- **ModExporter**：definitions.json + 槽位资源直接可导出为数据包/资源包，独立 JAR 需 Loom 子工程方案。

## 七、开发与复现

```bash
git clone https://github.com/MUERJUN/WorldForge
cd WorldForge
./gradlew build          # 编译 + JUnit
./gradlew runGameTest    # 真实服务端验收测试 A~H
./gradlew runClient      # 进游戏手测：/wf demo → /wf give …
```

版本依赖均与官方 fabric-example-mod 的 1.21.1 分支核对过（loom 1.12.7 / loader 0.16.9 / yarn 1.21.1+build.3 / FAPI 0.116.5，另有网络原因使用镜像说明见 `gradle.properties`）。

License: MIT
