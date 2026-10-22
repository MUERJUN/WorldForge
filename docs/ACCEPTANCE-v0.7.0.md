# v0.7.x 验收指南（复现命令）

本文件列出 v0.7.0 的**全部验收门禁**与**逐条复现命令**。

> 所有命令都在项目根目录 `worldforge/` 下执行，Windows 用 PowerShell / cmd 均可。
> 建议先设置 `JAVA_HOME` 指向 JDK 21，并把 Gradle 缓存放在**非系统盘**（见下方「磁盘建议」）。

```powershell
$env:JAVA_HOME = "C:\path\to\jdk-21"
$env:GRADLE_USER_HOME = "E:\javas\gradle-cache"   # 避免占满系统盘
```

---

## 0. 磁盘建议（重要）

本项目的构建会产生大量临时文件（Gradle 缓存、导出工程、Minecraft 资源）。
**系统盘空间不足会让客户端直接崩溃**（`0xC0000409`），看起来像代码问题，其实是磁盘满。

建议：

| 内容 | 建议位置 |
|------|----------|
| Gradle 缓存 | `GRADLE_USER_HOME=E:\javas\gradle-cache`（**必须在项目盘**） |
| 临时目录 | `TEMP` / `TMPDIR` 指向同一块盘 |
| 导出产物 | `<GameDir>/worldforge/exports/`（默认在项目盘的 `run/` 下） |

已知的泄漏已修复：测试脚手架过去每次运行都会在系统临时目录留下目录
（实测堆积 **7883 个 / 232 MB**），现在统一挂在
`wf-test-session-<pid>` 下，由 JVM 关闭钩子回收，并在下次启动时按 PID 清理残留。

---

## 1. 编译与单元测试（JUnit）

```powershell
./gradlew clean test
```

覆盖范围：

| 测试类 | 数量 | 覆盖内容 |
|--------|------|----------|
| `WfOpsToolLayerTest` | 27 | 工具注册、Schema 严格校验、未知参数拒绝、权限等级、作用域、目标快照、幂等键、事件序列化、结构化工具调用解析 |
| `ChatLayoutTest` | 8 | 聊天界面在 **10 种屏幕尺寸**下的布局（GUI Scale 1–4、854×480、1920×1080、213×120 窄屏）：不溢出、不重叠、输入可用、居中 |
| `ChatStoreTest` | 17 | 会话顺序与上限、上下文 JSON 合法性、保存/读取/删除、**损坏历史不被静默覆盖**、存储上限、文件名安全、工作区布局 |
| 既有回归测试 | 247 | 持久化、导出、权限、冲突、文档完整性等 |

---

## 2. GameTest（真实服务端 + 真实玩家流程）

```powershell
./gradlew runGameTest
```

**包含三条 v0.7.0 端到端玩家流程**（`src/main/java/dev/worldforge/test/`）：

| GameTest | 流程 | 验证内容 |
|----------|------|----------|
| `WorldForgeChatFlowTests.flowA_heldWeaponGainsBehaviourAndReallyWorks` | A | 手持虚拟武器 → 聊天 → 确认 → **定义真的变了** → 右键**真的**产生效果 |
| `WorldForgeChatFlowTests.flowB_targetedBlockChangesAndReallyWorks` | B | 视线目标动态方块 → 聊天 → 确认（**摘要说明共享定义影响**）→ **定义真的变了** → 右键**真的**有反应 → **存档重载后仍在** |
| `WorldForgeChatExportTests.flowC_chatExportProducesARealJar` | C（第一段） | 聊天请求导出 → 能力报告 → **降级确认（摘要列出损失项）** → **真实启动 Gradle 构建** → 工作区里出现**真实的导出工程**（`build.gradle` / `gradlew` / `src`） |

> **为什么 flow C 分两段。** GameTest 的 tick 预算从**入队**那一刻开始算，一个长等待在
> 测试真正开始时可能已经"过期"（会立刻触发，断言就跑到构建前面去了）。所以 GameTest
> 只验证到**真实构建已被启动**这一步，然后主动取消构建。**JAR 本身**由另外两处证明，
> 它们都没有 tick 预算：
>
> | 证据 | 命令 | 证明什么 |
> |------|------|----------|
> | 导出器集成测试 | `./gradlew test --tests "dev.worldforge.ExportBuildIntegrationTest"` | 同一个 ExportExecutor 真的构建出**可加载的 Fabric JAR** |
> | 客户端聊天导出验收 | `./gradlew runClientChatExportTest` | 从**真实客户端的聊天**里发起导出，磁盘上出现**真实 JAR**（`run/wf-chat-export-report.txt`） |
> | Linux CI | `.github/workflows/linux-export-build.yml` | 三个导出工程在**空白 Gradle home**下真实构建并跑通它们的 Fabric GameTest |

> 流程 C 会真的**启动**一次 Gradle 构建（产物工程在
> `run/worldforge/exports/<slot>/<modId>/`），测试随后取消构建。
> 首次构建时能联网下载 Gradle 发行版与 Fabric 依赖（约 100–200 MB）。
>
> 想看**完整的**聊天导出闭环（真的产出 JAR），跑客户端验收：
> `./gradlew runClientChatExportTest`——客户端没有 tick 预算，可以一直等构建结束。

---

## 3. 真实客户端聊天验收（带截图）

```powershell
./gradlew runClientChatTest
```

- 驱动**真实客户端**：按下**真实的 K 绑定**（不是模拟调用）
- 检查首页**没有**导出 / 终端 / 物品编辑 / 方块编辑按钮
- 多会话：新建、切换、**不串台**
- 中文输入、发送、运行中状态、取消、设置入口、ESC 关闭
- 截图输出：`run/screenshots/wf-chat-autotest-*.png`
- 报告：`run/wf-chat-autotest-report.txt`（全部 `[PASS]` 才算通过）

> 客户端会自己 `scheduleStop()` 退出。若中途失败，客户端会抛异常让 Gradle 以非零码退出。

---

## 4. 真实客户端聊天导出验收（流程 C 的客户端版本）

```powershell
./gradlew runClientChatExportTest
```

在真实单人世界里从**聊天**发起一次真实导出，报告在
`run/wf-chat-export-report.txt`，产物在 `run/worldforge/exports/`。

---

## 5. 独立导出工程验证（Linux CI 同款）

```powershell
./gradlew test --tests "dev.worldforge.ThreeProjectGenerationTest"
bash scripts/build-three-projects.sh
```

生成并真实构建三个独立导出工程（alpha / beta / gamma），产物在
`build/three-projects/`。

---

## 6. Linux CI

推 `main` 或打 `v*` tag 触发 `.github/workflows/linux-export-build.yml`：
空白 `GRADLE_USER_HOME`、只用工程自带 wrapper，三个导出工程全部
**生成 + 构建 + 跑 Fabric GameTest**，无 SKIP 路径。

---

## 7. 手工体验（普通玩家视角）

1. 安装 **Fabric Loader 0.16.9** + **Fabric API 0.116.5+1.21.1** + `worldforge-0.7.0.jar`
2. 启动 **Minecraft 1.21.1**（单人世界）
3. 打开 `config/worldforge/agent.json`，填入 `apiKey`（或用游戏内 **⚙ 设置** 面板填，单人游戏直接生效）
4. 进入世界，按 **K** → 打开 AI 聊天
5. 试试：
   - 「创建一把雷霆之剑，右键召唤闪电，冷却 5 秒」
   - 「让它右键召唤加速」
   - 「把刚才的剑导出成一个独立 Mod」

> 旧 Studio 仍在 **L** 键（或聊天右上角 ⚙ → 高级）里，普通玩家不需要进去。

---

## 8. 已知的环境相关问题

| 现象 | 原因 | 处理 |
|------|------|------|
| 客户端启动即崩（`0xC0000409`） | **系统盘满** | 清理系统盘；把 `GRADLE_USER_HOME` / `TEMP` 移到项目盘 |
| `Gradle build daemon disappeared` | 同上（磁盘/内存） | 同上；`./gradlew --stop` 后重试 |
| GameTest 报 `Didn't succeed or fail within N ticks` | 测试**排队**时间也算进 tick 预算 | 本仓库已把导出测试的 `tickLimit` 提到 2,000,000 |
| 导出长时间「Gradle 构建进行中…」 | 首次构建要下载依赖 | 属正常，等 1–3 分钟 |
| 聊天提示「AI 未启用」 | 服务端没配 `agent.json` | 设置面板填 API Key |

---

## 9. 快速全量门禁（一条命令）

```powershell
./gradlew clean test runGameTest
```

期望：`BUILD SUCCESSFUL`，
`All 70 required tests passed :)`（GameTest，含 A/B/C 三条真实流程 + v0.7.1 的
6 个并发/确认/删除/取消用例）。

## 10. v0.7.1 新增的可靠性验收

| 门禁 | 命令 | 覆盖 |
|------|------|------|
| 确认状态机（单元） | `./gradlew test --tests "dev.worldforge.ChatRuntimeConcurrencyTest"` | 终态互斥、**真实线程竞争**（批准 vs 拒绝、取消 vs 完成）、确认 ID 精确匹配、超时与预算 |
| 客户端状态规则（单元） | `./gradlew test --tests "dev.worldforge.ChatClientStateTest"` | 消息去重、旧任务事件丢弃、已删除会话不复活、只有终态结束忙碌、12 个工具的权限与"无任意执行" |
| 并发/确认/删除/取消（GameTest） | `./gradlew runGameTest` | 同会话 20 条连发、确认期间发第二条、两会话并行、旧任务迟到回答、删除一个会话不影响其他、**真实取消 Gradle 子进程** |
| 真实客户端聊天验收 | `./gradlew runClientChatTest` | K 打开聊天、首页无专业按钮、多会话、运行状态指示器跟随真实状态、ESC |

> **v0.7.1 的两条硬规则**（写进了测试）：
> 1. 一个 `conversationId` 同时只有一个活动任务；第二条消息**明确排队或明确拒绝**。
> 2. 只有**终态**（SUCCEEDED / FAILED / CANCELLED）才结束忙碌状态；
>    "已确认，正在执行"和"已请求取消"都不是终态。
