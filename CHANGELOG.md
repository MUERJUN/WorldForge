# 更新日志

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
