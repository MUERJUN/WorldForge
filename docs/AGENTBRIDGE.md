# WorldForge AgentBridge 架构与权限设计 (v0.3.0)

## 1. 目标与产品约束

让玩家在 Minecraft 内用自然语言创建/修改虚拟物品，**单个 JAR 安装即用**：

- Java 21 原生实现，零额外运行时（无 Node/Python/Docker/独立后端）。
- 任何 OpenAI 兼容 Chat Completions 接口可用（OpenAI、DeepSeek 等）。
- **不假设模型支持原生 Tool Calling / JSON Schema**：契约是"系统提示词约定 + 服务端严格 JSON 解析"，模型只输出一个提案 JSON。
- 预留 `ModelProvider` 接口，未来可接入 DSH Sidecar 而不改上层。

## 2. 模块与数据流

```text
玩家输入 (客户端 AiStudioPanel)
   │ C2S studio_request{kind:"agent_chat", requestId, payload=用户文本}
   ▼
WorldForgeStudioProtocol (服务端, 网络线程 → server.execute)
   ▼
AgentBridge.chat()                        ← 阶段 A：只生成提案，绝不改世界
   │ 1. available()?  无配置 → 返回启用指引（不崩溃）
   │ 2. 频率限制（每玩家/每分钟）+ 每回合步骤上限
   │ 3. AgentSession：服务端权威物品上下文 + focus_item（"它"的指代）
   │ 4. ModelProvider.complete() 异步（JDK HttpClient，独立线程池）
   │ 5. 回包 → ProposalJson.parse（严格：围栏容忍、不闭合=截断=拒绝）
   │ 6. ProposalValidator（白名单/数值范围/ID 语法/贴图目录/能力缺口）
   │ 7. 暂存 PENDING + 记录 base 快照
   ▼ S2C agent 事件（requestId=0，含 diff 预览）
玩家看到结构化预览 → 点「确认应用」或「取消」
   │ C2S studio_request{kind:"agent_confirm", payload=proposalId}
   ▼
AgentBridge.confirmProposal()             ← 阶段 B：唯一执行点
   │ 1. OP2 权限复核（与 /wf 写命令同阈值；GUI/Agent 永远拿不到更高权限）
   │ 2. proposalId 幂等：已执行的直接返回"重复确认已忽略"（超时重发安全）
   │ 3. TTL 过期 → 作废
   │ 4. 冲突检测：base 快照 ≠ 当前定义 → 拒绝盲目覆盖，要求重新预览
   │ 5. WorldForgeTools.createItem/updateItem/deleteItem —— 与命令、
   │    Studio 手工编辑同一条管线（校验→落盘(失败回滚)→存量堆热重烘→广播）
   ▼
S2C 权威快照（定义+墓碑）→ 客户端镜像收敛 → 玩家一键获取测试
```

## 3. 信任边界（安全设计核心）

| 攻击面 | 防线 |
|---|---|
| 模型谎称"已创建成功" | 模型文本只是提案；执行只发生在玩家点确认后、以服务端 `ToolResult` 为准 |
| 提示词注入（物品描述/玩家输入操纵模型） | 工具返回与历史只作为**数据**拼进 user 消息；系统提示词静态构建，不含任何用户内容；且模型输出还要过服务端白名单——即使被注入操纵，也只能产出白名单内的提案 |
| 非法/超大/畸形 JSON | 32KB 提案上限、不闭合=拒绝、逐字段类型检查、256 字符字段截断 |
| 越权修改 | confirm 时重新检查 OP2；非 OP 可预览（只读）不可执行 |
| 绕过权限直接改世界 | 提案执行只经 WorldForgeTools，与命令层同一入口，无第二套管线 |
| 未经确认的删除 | 删除提案同样必须玩家点确认；tombstone 语义不变 |
| 无限工具调用循环 | 每回合 1 个提案（MAX_STEPS_PER_TURN=1）+ 每玩家每分钟频率上限 |
| 陈旧提案覆盖新编辑 | base 快照冲突检测 → 拒绝并提示重新预览 |
| 超时重发重复创建 | proposalId 幂等表（EXECUTED），重放返回缓存结果 |
| 跨世界上下文串扰 | 会话按 (玩家,服务器实例) 绑定；DISCONNECT/SERVER_STOPPED 全部释放 |
| API Key 泄漏 | 只存服务端 config；toString 掩码；不进存档/不同步客户端/不入日志；GUI 永不接触密钥 |
| 网络挂死拖垮游戏 | 请求在独立线程池；硬超时+有限重试；渲染/主线程零阻塞；客户端 75s 看门狗 |

## 4. 配置

`config/worldforge/agent.json`（服务端；首次启动自动生成模板）：

```json
{
  "enabled": true,
  "baseUrl": "https://api.deepseek.com/v1",
  "model": "deepseek-chat",
  "apiKey": "PASTE-YOUR-KEY-HERE",
  "timeoutSeconds": 60,
  "maxTokens": 1200,
  "retries": 1,
  "rateLimitPerMinute": 10,
  "historyLimit": 12,
  "proposalTtlSeconds": 300
}
```

- `apiKey` 为空或 `enabled:false` → AI 面板显示启用指引，可视化编辑器不受影响。
- 多人服务器：密钥与调用全部在服务端；客户端只发文本。

## 5. 能力白名单（提案允许表达的全部动作）

触发器 `right_click|hit_entity`；动作 `summon_lightning/apply_effect/heal/experience/teleport/explosion/ignite`（各有数值范围）；外观 = 15 个原版贴图槽位目录；属性 `damage/max_durability/attack_speed`。超出白名单 → 提案拒绝 + 向玩家如实报告能力缺口（如"飞行暂不支持"）。

## 6. 测试策略

- **MockModelProvider**：确定性脚本回复（合法提案/垃圾文本/抛异常），全部自动化测试不依赖真实 API。
- **端到端链**（`WorldForgeAgentTests`，单测试串行跑完验收链 12 步）：禁用指引 → 限流 → 创建预览/确认/落盘/幂等 → 取消零副作用 → 不支持技能报告 → 垃圾输出安全 → 网络故障恢复 → 连续对话差量修改 → 冲突拒绝 → 会话隔离 → 重载持久 → 非 OP 拒绝执行。
- **JUnit**（`ProposalJsonTest`）：解析/差量合并语义/校验边界/diff 渲染。
- 真实模型集成：见交付报告"实际验证状态"（需用户提供 key 后按 README 步骤验证，未做不谎报）。

## 7. 未来扩展（本阶段不做）

- AI 生图（保留 `TextureSource` 抽象，槽位目录可扩）
- DSH Sidecar `ModelProvider` 实现（接口已就位）
- 多提案批量确认、动态方块/实体能力
