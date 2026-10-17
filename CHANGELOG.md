# 更新日志

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
