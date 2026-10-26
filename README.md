# WorldForge — Minecraft 游戏内 AI 创造助手（Chat-First）

[![build](https://img.shields.io/badge/build-passing-brightgreen)]() [![MC](https://img.shields.io/badge/Minecraft-1.21.1-green)]() [![Loader](https://img.shields.io/badge/Fabric-Loader%200.16.9-blue)]()

WorldForge 是 Minecraft 游戏内的 AI Mod 开发环境：安装一个 JAR，在游戏里用自然语言或可视化编辑器创造物品和方块，实时测试并导出独立 Mod。

- 游戏版本：Minecraft Java **1.21.1**
- 加载器：Fabric Loader **0.16.9** + Fabric API **0.116.5+1.21.1**
- 映射：Yarn **1.21.1+build.3**；Java **21**；Gradle Wrapper **8.14.3**
- 当前版本：**v0.8.0 Public Beta**

---

## 快速开始

**普通玩家安装（无需开发环境）：**

1. 安装 [Fabric Loader 0.16.9](https://fabricmc.net/use/installer/)，选择 Minecraft **1.21.1**。
2. 下载 [Fabric API 0.116.5+1.21.1](https://modrinth.com/mod/fabric-api) 与 GitHub Release 的 `worldforge-0.8.0.jar`。
3. 把两个 JAR 放入 `.minecraft/mods/`，启动游戏。
4. 进入单人世界，按 **K** 打开 **AI 聊天**。
5. 第一次使用先点聊天右上角 **⚙ 设置**，填入 API Key（单人游戏直接生效，重启无效）。

然后直接用大白话说：

- 「创建一把雷霆之剑，右键召唤闪电，冷却 5 秒」
- 「给我手里的剑加上加速」 ← **v0.7.2 新增：原版物品也能获得行为**
- 「把这块石头变成玻璃」 ← **v0.8.0 新增：直接修改真实世界（可撤销）**
- 「把刚才的剑导出成一个独立 Mod」

AI 自己判断该做什么、在游戏里执行，并把**真实发生的每一步**显示在同一条对话里。

> 旧的 WorldForge Studio 仍在 **L** 键（或聊天右上角 ⚙）里，普通玩家不需要进去。

完整图文教程、5 分钟上手示例、能力清单与常见问题见 **[docs/BETA-GUIDE.md](docs/BETA-GUIDE.md)**；
全部验收门禁的复现命令见 **[docs/ACCEPTANCE-v0.7.0.md](docs/ACCEPTANCE-v0.7.0.md)**。

**开发者构建：**

```bash
./gradlew build           # 编译 + JUnit 全量测试
./gradlew runGameTest     # 真实服务端 GameTest
./gradlew runClient       # 进游戏：按 K 打开 AI 聊天
```

---

## v0.7.1 — Chat Runtime 可靠性维护

**核心体验不变（按 K 就是聊天）。这一版只修可靠性：玩家在聊天里看到的每一个任务状态，都与后台真实执行状态一致。**

- 同一会话**同时只有一个活动任务**：第二条消息会明确排队（上限 4）或明确拒绝，不会并行执行。
- 每个任务有自己的 `taskId` / 取消令牌 / generation：慢的旧任务无法踩掉新任务的状态，迟到回答也不会写进历史。
- 删除一个会话**只释放该会话**（旧实现会把该玩家的所有会话一起停掉）。
- 确认/拒绝是**互斥终态**：空确认 ID 不再能确认任意待办，确认后拒绝不会误报"已取消"。
- 取消**真的取消**：玩家的取消会终止 Gradle 子进程树，不会留下可用的 JAR，也不会谎报失败。
- 断线现在会清理聊天运行时的任务与待确认操作（此前是死代码）。

详见 [CHANGELOG.md](CHANGELOG.md#v071-public-beta-maintenance--chat-runtime-可靠性)。

---

## v0.7.0 — Chat-First：按 K 就是聊天

**这一版改掉了 WorldForge 的使用方式：它不再是装进 Minecraft 的简化版 IntelliJ，
而是存在于游戏里的 AI 创造与行动助手。**

### 你只需要表达想法

| 你说 | AI 做什么 | 你看到 |
|------|----------|--------|
| 「给我手里的剑加个右键闪电，冷却 5 秒」 | 读主手身份 → 读原定义 → 生成方案 → **等你确认** → 改 → 存 → 同步 | 每一步都是一条真实消息 |
| 「让这个方块右键时治疗我」 | 认目标 → 说明**会同时影响所有同 ID 方块** → **等你确认** → 改 → 存 | 明确的共享定义提示 |
| 「把刚才的雷霆剑导出成一个独立 Mod」 | 能力分析 → **降级确认** → 真跑 Gradle 构建 | 真实构建进度 + JAR 路径 |

### 首页只有这些

聊天记录、输入发送、新建/切换会话、设置入口。
**没有**物品编辑、方块编辑、导出、Gradle、终端、调试面板。

### WF-Ops：AI 只能调用注册过的 Java 工具

服务端权威的结构化工具层，**不是 shell 替代品**：

| 工具 | 作用 |
|------|------|
| `inspect_context` | 玩家所在世界/维度/位置/主手物品/视线目标/服务器能力 |
| `inspect_definition` | 指定物品或方块的定义与可编辑字段 |
| `list_capabilities` | 当前支持的操作、约束，以及**明确不能实现的功能** |
| `check_export_capabilities` | 导出前的保留/转换/丢失报告（不构建） |
| `create_item` / `update_item` | 创建 / 修改虚拟物品 |
| `create_block` / `update_block` | 创建 / 修改动态方块 |
| `inspect_target_block` / `modify_target_block` | 检查 / 修改视线目标方块 |
| `give_virtual_item` | 把虚拟物品交给玩家 |
| `export_mod` | 真实构建独立 Mod JAR |

**没有** `run_shell` / `execute_powershell` / `execute_arbitrary_code` 之类的工具。

### 权限不是靠提示词

修改世界的操作：AI 生成意图和受影响对象 → 聊天里展示影响摘要 → 玩家明确确认 →
**服务端再次校验权限、作用域和目标快照** → 才执行。
目标在确认期间被改动（换了手持物、方块被拆、别人先改了）就拒绝执行。
导出降级必须由**玩家批准的摘要**明确写出每一项损失。

### 不会假装成功

工具的成功状态来自真实的服务端执行结果。持久化成功但同步失败会分开显示，
不会笼统说「全部成功」。AI 也**看不到也不显示**模型的思维链，只有任务计划、
工具动作和真实结果。

### v0.7.0 已知限制

- **AI 未配置时聊天不可用**：需要 `config/worldforge/agent.json` 里的 API Key。
- **真实 DeepSeek/OpenAI 的端到端调用尚无独立验收**：本版验收用的是本地脚本化的
  Mock 模型，**证明的是智能体链路，不是真实服务商的兼容性**。
- **不能给原版物品附加行为**，也不能改造原版方块：AI 会说明限制并给出替代方案，
  不会偷偷替换玩家原有的东西。
- 修改方块定义影响**所有同 ID 实例**（共享定义），聊天里会明确提示。
- 聊天历史有限额（50 个会话 / 每会话 500 条），可删除；损坏的历史不会被静默覆盖。
- Linux 仍只验证了无图形导出构建与 Fabric GameTest，**GUI 未验证**；macOS 未验证。
- 导出构建首次需要联网下载 Gradle 与 Fabric 依赖（约 100–200 MB）。

---

## 核心机制

### 虚拟方块

启动期只注册 **1 个通用原生方块** `worldforge:dynamic_block` + 1 个 BlockEntity 类型 + 1 个 BlockItem。玩家的每个"虚拟方块"是它的实例，身份存于 BlockEntity 的 `VirtualBlockId` 字段，通过 NBT 持久化。

### 已实现

| 模块 | 说明 |
|---|---|
| `BlockDefinition` | 记录类：id/name/description/appearance/category/hardness/behaviors/tickIntervalTicks/outputItemId/outputCount |
| `BlockCatalog` | 12 项原版外观封闭目录（stone/oak_planks/furnace/crafting_table/iron_block/gold_block/emerald_block/amethyst_block/redstone_lamp/bookshelf/barrel/deepslate_tiles） |
| `BlockDefinitionStore` | blocks.json 持久化：严格加载拒绝整份损坏、容错逐条上报、原子写入、损坏隔离 |
| `DynamicBlock` / `DynamicBlockEntity` | 放置写身份、破坏按玩家模式掉落（创造不掉）、右键分发行为、删除定义后失效可复活 |
| `BlockRuntimeManager` | 服务端权威：失败回滚（定义与墓碑同时快照）、未绑定存储不假成功 |
| `PeriodicScheduler` | 有界轮转调度：每 (world,pos) 最多一个任务、每 tick 硬上限 128、队列有界 8192 |
| `BlockBehaviorEngine` | 白名单行为：apply_effect/heal/experience/ignite/give_item/sound/particles |
| `WorldForgeBlockTools` | 统一变更入口，三段式结果（persisted/applied/sync） |
| `/wf block` | help/list/info/create/give/delete，OP2 权限 |
| `AgentBridge` | AI 方块提案：CreateBlock/UpdateBlock/DeleteBlock，类型严格隔离 |
| `BlockStudioPanel` | Studio 方块分类页：列表/创建/编辑/删除/获取，外观与类型选择器 |
| `DynamicBlockEntityRenderer` | 客户端渲染：复用原版方块模型，从客户端镜像解析外观 |
| `ClientBlockState` | 客户端方块定义镜像，由 SyncDefinitions 同步 |

### 验收状态

| 验证项 | 状态 | 证据 |
|---|---|---|
| 方块定义创建与持久化 | ✅ GameTest | B1-B4, L1-L6 |
| 放置→身份写入→破坏→重放 | ✅ GameTest | L1, L2 |
| 热修改对已放置方块生效 | ✅ GameTest | L4 |
| 删除后失效与重建复活 | ✅ GameTest | B3, L4 |
| 跨存档隔离 | ✅ GameTest | L5 |
| 损坏文件隔离 | ✅ GameTest | L6 |
| 周期调度压力测试 | ✅ GameTest | R3-R5 (100/129/256/512) |
| AI 方块提案端到端 | ✅ GameTest | A13, A14 |
| Studio 方块 UI | ✅ 真实客户端 | 自动化钩子 + 截图 |
| 世界内方块渲染 | ✅ 真实客户端 | 截图 v040-world-blocks-view.png |
| 客户端镜像同步 | ✅ 真实客户端 | SyncDefinitions blocksJson 字段 |
| 真实区块卸载/重载 | ✅ GameTest | CL1-CL3 (setChunkForced API) |
| 生存模式破坏掉落 | ⚠️ 部分 | SB1-SB2: shouldDrop rule + drop identity verified; in-world survival break pending |

### 已知限制

- 所有虚拟方块共用一个原生方块类型：碰撞形状/硬度/光照/红石行为无法逐定义改变。
- 超载时周期被拉长为 `ceil(N/128)` tick：保证最终执行与每 tick 有界，不保证原始周期。
- 真实区块卸载/重载循环已验证（CL1-CL3）。
- 生存模式世界内掉落：shouldDrop 规则和掉落物身份已验证；完整生存交互列为已知限制。

---

## v0.3.2 — GUI 稳定性与游戏内自动化测试（已发布）

### 三项 GUI 修复

1. **AI 请求不再卡死**：只有请求真正交给传输层才进入"思考中"；空输入/请求在飞/无服务端分别即时提示；被拒 ack、模型失败、75s 看门狗、断线全部恢复输入。
2. **草稿保护全覆盖**：列表点击、AI 面板、外观选择、删除确认、返回列表、关闭按钮、ESC 全部经过同一守卫。
3. **取消与退出解耦**：取消提案先是本地操作，再尽力而为通知；关闭永远以关闭收尾。

### 游戏内自动化 GUI 测试

```bash
echo x > run/wf-autotest.flag     # 打开测试开关
./gradlew runClient               # 真实客户端自动跑完整 GUI 流程
cat run/wf-autotest-report.txt    # 逐项 PASS/FAIL + 真实帧截图
rm run/wf-autotest.flag           # 关闭
```

---

## v0.3.0 — AI AgentBridge：自然语言创造物品

### AI 配置（游戏内 GUI）

按 K 打开 Studio → 点「⚙ AI 设置」→ 在游戏内配置 API Key、Provider、Base URL、Model。**不再需要手动编辑 agent.json**。

也支持直接编辑 `config/worldforge/agent.json`：

```json
{
  "enabled": true,
  "baseUrl": "https://api.deepseek.com/v1",
  "model": "deepseek-chat",
  "apiKey": "你的 KEY"
}
```

API Key 只存服务端配置文件：不进世界存档、不发给客户端、日志自动掩码。

### 使用流程

1. 按 K 打开 Studio → 点「✦ AI 创造面板」
2. 输入：`创建雷霆之剑，攻击力 12，右键召唤闪电，冷却 5 秒`
3. AI 返回结构化提案预览（此时世界数据未变）
4. 点「确认应用」→ 服务端复核权限与参数 → 创建并落盘
5. 确认后自动跳到编辑视图 → 「获取测试」→ ESC 关闭直接玩

### 方块提案

AI 也能创建方块：`创建一个魔法熔炉，外观像熔炉，周期性生成铁锭`。物品与方块是不同类型，即使 ID 相同也不能跨类型误修改。

---

## v0.2.0 — WorldForge Studio 游戏内可视化编辑器

按 K 打开 Studio。支持物品列表、创建/编辑/删除、外观选择（15 个原版贴图槽位）、行为配置、获取测试、AI 面板、AI 设置、方块分类。

---

## 命令

```
/wf help                          — 帮助
/wf list                          — 列出所有物品定义
/wf info <id>                     — 查看物品详情
/wf create <id> <name>            — 创建物品
/wf give <id>                     — 获取物品
/wf setdamage <id> <value>        — 修改攻击力
/wf delete <id>                   — 删除物品定义
/wf reload                        — 重载定义
/wf policy reload                 — 重载行为执行策略 (OP2)
/wf policy status                 — 查看当前行为策略 (OP2)
/wf demo                          — 创建 10 种演示物品

/wf block help                    — 方块命令帮助
/wf block list                    — 列出所有方块定义
/wf block info <id>               — 查看方块详情
/wf block create <id> <name> [appearance] [category] — 创建方块
/wf block give <id>               — 获取方块物品
/wf block delete <id>             — 删除方块定义
```

写操作需要 OP2 权限。

---

## 行为执行策略（服务端权限）

「给原版剑嫁接行为」用的是 SELF 权限——玩家只能改**自己手里**的物品。
但「这个行为能否在当前世界执行」是另一回事，由服务端策略决定
（v0.7.4 新增，`BehaviorPolicy`）：

| 行为 | 默认 | 理由 |
|------|------|------|
| `heal` / `experience` / `teleport` | 允许 | 仅影响玩家自己 |
| `apply_effect`（target=self） | 允许 | 仅影响玩家自己 |
| `apply_effect`（target=他人） | **拒绝** | 影响其他实体（PvP） |
| `summon_lightning` / `explosion` / `ignite` | **拒绝** | 改变世界、可破坏方块或伤害他人 |

要点：

- **配置文件**：`config/worldforge/behavior-policy.json`（首次启动自动生成模板）。
  `allowedActions` 显式允许、`restrictedActions` 显式拒绝；
  **restrictedActions 优先于 allowedActions**——同一动作出现在两个列表时一律拒绝。
  两个列表都没写的动作按内置默认策略处理（未知动作一律拒绝）。
- **生效时机**：策略在行为**触发时**校验，不是嫁接时。已嫁接的行为在服务器策略
  变化后的下一次触发立即按新策略执行，被禁止时玩家会看到明确提示。
- **重载**：编辑 JSON 后运行 `/wf policy reload`（OP2）。命令在服务端线程执行；
  只有完整解析并校验成功才替换当前策略。
- **失败行为**：配置解析失败（JSON 语法错误、字段类型不对）时**保留上一次有效策略**，
  绝不静默放宽限制；首次启动遇到无效配置则采用安全默认策略。命令会返回失败提示。
- **v0.7.3 行为差异**：v0.7.3 只在服务器启动时读取一次该文件（改文件需重启）；
  v0.7.4 起提供 `/wf policy reload` 热重载。

---

## 架构

```text
src/main/java/dev/worldforge/
├── WorldForge.java               # ModInitializer
├── registry/                     # 原生注册：dynamic_item + dynamic_block
├── component/                    # 数据组件：VirtualId + VirtualBlockId
├── definition/                   # 物品定义模型
├── block/                        # 方块定义模型 + 运行时 + 调度 + 行为
│   ├── BlockDefinition           #   方块定义记录
│   ├── BlockCatalog              #   12 项原版外观封闭目录
│   ├── BlockDefinitionStore      #   blocks.json 持久化
│   ├── DynamicBlock              #   通用代理方块
│   ├── DynamicBlockEntity        #   BlockEntity：身份 + NBT + 周期注册
│   ├── BlockRuntimeManager       #   服务端权威管理器
│   ├── PeriodicScheduler         #   有界轮转调度
│   └── BlockBehaviorEngine       #   白名单行为分发
├── tool/                         # 统一工具层
│   ├── WorldForgeTools           #   物品工具
│   └── WorldForgeBlockTools      #   方块工具
├── ai/                           # AgentBridge + 提案 + 校验
├── network/                      # S2C 同步 + Studio 协议
├── command/                      # /wf 命令树
├── studio/                       # StudioUiState 状态机
└── test/                         # GameTest 验收测试

src/client/java/dev/worldforge/client/
├── WorldForgeClient.java         # 客户端初始化
├── ClientBlockState.java         # 客户端方块定义镜像
├── render/DynamicBlockEntityRenderer.java  # 方块实体渲染器
├── gui/                          # Studio 屏幕 + AI 面板 + 方块面板
└── dev/StudioAutoTest.java       # 游戏内自动化测试
```

---

## 测试

| 测试 | 数量 | 方式 |
|---|---|---|
| JUnit | 202 项 | `./gradlew test` |
| GameTest | 61 项 | `./gradlew runGameTest` |
| 客户端自动化 | 30 步 | `./gradlew runClient`（需 flag） |
| 独立 Mod 导出 | ✅ 已验证 | 导出 JAR 在无 WorldForge 实例中加载 |

GameTest 覆盖：物品创建/修改/删除/持久化/同步/行为/失效/跨存档隔离/损坏恢复 + 方块定义/放置/身份/破坏/热修改/失效/调度压力/AI 提案端到端/类型隔离。

---

## License: MIT
