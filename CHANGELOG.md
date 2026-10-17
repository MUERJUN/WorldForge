# 更新日志

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
