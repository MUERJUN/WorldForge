# WorldForge — Minecraft 运行时动态物品与方块引擎 + 游戏内 Studio + AI AgentBridge

[![build](https://img.shields.io/badge/build-passing-brightgreen)]() [![MC](https://img.shields.io/badge/Minecraft-1.21.1-green)]() [![Loader](https://img.shields.io/badge/Fabric-Loader%200.16.9-blue)]()

WorldForge 是 Minecraft 游戏内的 AI Mod 开发环境：安装一个 JAR，在游戏里用自然语言或可视化编辑器创造物品和方块，实时测试并导出独立 Mod。

- 游戏版本：Minecraft Java **1.21.1**
- 加载器：Fabric Loader **0.16.9** + Fabric API **0.116.5+1.21.1**
- 映射：Yarn **1.21.1+build.3**；Java **21**；Gradle Wrapper **8.14.3**
- 当前版本：**v0.5.2 已发布**（导出 GUI 与行为等价性加固）

---

## 快速开始

```bash
./gradlew build           # 编译 + JUnit 124 项
./gradlew runGameTest     # 真实服务端 GameTest 61 项
./gradlew runClient       # 进游戏：按 K 打开 Studio
```

安装：把 `build/libs/worldforge-0.4.0.jar` + Fabric API 放入 `.minecraft/mods`。

---

## v0.4.0 — 动态方块引擎（已发布）

### 核心机制：虚拟方块

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

### 方块提案（v0.4.0）

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
| JUnit | 110 项 | `./gradlew test` |
| GameTest | 61 项 | `./gradlew runGameTest` |
| 客户端自动化 | 30 步 | `./gradlew runClient`（需 flag） |
| 独立 Mod 导出 | ✅ 已验证 | 导出 JAR 在无 WorldForge 实例中加载 |

GameTest 覆盖：物品创建/修改/删除/持久化/同步/行为/失效/跨存档隔离/损坏恢复 + 方块定义/放置/身份/破坏/热修改/失效/调度压力/AI 提案端到端/类型隔离。

---

## License: MIT
