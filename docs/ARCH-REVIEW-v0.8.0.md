# AI World Editor 第一阶段 — 架构审查记录（v0.8.0）

> 基线：`b4b48d9`（v0.7.4）。审查日期：2026-10-26。
> 基线测试：JUnit **366 PASSED** / 0 FAILED / 6 SKIPPED（无密钥真实模型×3、导出 JAR 内容×3），GameTest **87 PASSED**。

## 一、三类操作的架构区分

| 类型 | 现状 | v0.8.0 处置 |
|------|------|------------|
| **A. 世界方块实例编辑**（把眼前的石头换成玻璃） | **缺失**。现有 `setBlockState` 仅出现在导出模板生成器的字符串里，主代码没有任何世界方块状态写入路径 | 本轮新建 World Edit 执行核心 + 2 个新工具 |
| **B. WorldForge 动态方块定义编辑** | **已存在**：`create_block` / `update_block` / `modify_target_block`，全部经 `WorldForgeBlockTools` 单一入口，预检里 `PlacedBlockCounter` 已明确提示共享定义影响所有同 ID 实例 | **复用，不改动**。本轮明确拒绝把 A 混入 B：世界实例编辑工具遇到 WF 动态方块时直接说明应走定义编辑路径 |
| **C. 独立 Mod 导出** | **已存在**：`export_mod` + ExportExecutor + CI | 不属于本轮普通世界编辑路径，保持原样 |

## 二、已存在且直接复用的能力

1. **视线目标读取** — `TargetBlock.read(ctx)`：服务端权威 raycast（tickDelta 1.0），返回坐标、原版 id、WF virtualId。单方块场景 A 直接复用。
2. **确认状态机** — `PendingConfirmation`（PENDING→APPROVED/DENIED/TIMED_OUT/CANCELLED 单一原子终态）、`ToolPreparation.snapshot` + `AgentOrchestrator` 执行时重跑 preparer 比对快照（v0.7.4 已强化为实例身份+NBT 指纹）。
3. **权限门** — `PermissionGate.checkAuthority`：WRITE/ADMIN 工具需要与 `/wf` 相同的 OP2 权限；`checkScope` 按 item/block id 限定范围。
4. **任务生命周期** — `TaskToken` 取消令牌、worker 上限、会话串行化、预算（`AgentTimeouts`）。
5. **工具管线** — `ToolDescriptor/ToolRequest/ToolResult/ToolEvent`、`JsonSchemaValidator` 严格 Schema、`ToolCatalog` 单一注册点。
6. **聊天反馈** — 工具事件流 + 确认控件，全部在同一对话内。
7. **WF 动态方块识别** — `DynamicBlockEntity.virtualId()`。

## 三、缺失的能力（本轮实现）

1. **世界方块状态读取/写入核心**：按坐标读取 `BlockState`、校验、写入、结果核实。
2. **不可变修改计划**：计划 ID、玩家、世界/维度、坐标列表、原状态、新状态、数量、快照指纹、创建时间、过期。
3. **有限区域解析**：以准星命中面为锚定的有界区域（5×3 类），方向由玩家视线/命中面推导，边界不明确时向玩家追问而不是让模型猜坐标。
4. **撤销历史**：有界（每玩家上限）、绑定玩家+世界+维度、撤销前验证当前状态与预期修改后状态一致。**跨重启持久化：本轮不实现，历史仅内存保存，重启即失效——文档与回复如实声明。**
5. **新工具**：`edit_world_blocks`（单方块或有限区域，一个工具统一承载计划生成）+ `undo_world_edit`（撤销最近一笔）。避免工具数量膨胀；模型不感知内部类名。

## 四、拟修改/新增的文件

| 文件 | 动作 | 职责 |
|------|------|------|
| `ops/worldedit/WorldEditPlan.java` | 新增 | 深度不可变计划 record |
| `ops/worldedit/WorldEditValidator.java` | 新增 | 目标/边界/权限/状态校验、禁止目标清单、BlockEntity 拒绝、256 上限、区块已加载检查 |
| `ops/worldedit/WorldEditExecutor.java` | 新增 | 服务端线程执行、结果核实、失败报告真实修改数与回滚 |
| `ops/worldedit/WorldEditHistory.java` | 新增 | 每玩家有界撤销栈（含世界/维度绑定、过期） |
| `ops/worldedit/BlockTargetRules.java` | 新增 | 可编辑目标规则（禁止命令方块/结构方块等；拒绝 BlockEntity 目标） |
| `ops/tools/WorldEditTool.java` | 新增 | `edit_world_blocks` descriptor/preparer/executor（场景 A+B） |
| `ops/tools/UndoWorldEditTool.java` | 新增 | `undo_world_edit` |
| `ops/ToolCatalog.java` | 修改 | 注册两个新工具 |
| `docs/ACCEPTANCE-v0.8.0.md` | 新增 | 验收命令与证据 |

GameTest 新增 `WorldForgeWorldEditTests`（14 项）+ JUnit `WorldEditPlanTest` / `WorldEditValidatorTest` / `WorldEditHistoryTest`。

## 五、硬限制（可测试）

- 单计划 ≤ **256** 个方块；区域遍历体积 ≤ 4096（16³）且逐位置计数。
- 只在**已加载**区块操作（`world.isChunkLoaded`）；不强制加载。
- 世界边界与维度高度（`world.getBottomY()/getTopYInclusive()`）越界拒绝。
- 方块 id 必须经 `Registries.BLOCK.get(Identifier)` 解析成功；仅接受默认状态或显式列出的属性键值对（严格白名单校验）；解析失败即拒绝。
- 禁止目标：命令方块（所有变体）、结构方块、结构空位、屏障、床（多格）、带 BlockEntity 的任何目标（箱子/熔炉/签名/其它 Mod BE 一律拒绝）。
- 计划过期：默认 10 分钟（与确认 TTL 对齐），过期拒绝执行。

## 六、潜在兼容风险与对策

1. **与 B 类共享定义编辑混淆** → 世界实例编辑遇到 WF 动态方块（有 virtualId/BE）时拒绝并提示走定义编辑路径；定义编辑工具不变。
2. **确认快照复用** → 计划指纹进入 `ToolPreparation.snapshot`，沿用编排器既有比对；执行前 `WorldEditValidator` 再次独立核对每个位置的当前状态。
3. **多人** → 计划绑定发起玩家 UUID，撤销只对该玩家自己的最近记录；权限沿用 OP2 契约；模型自然语言不能替代任何检查。
4. **客户端同步** → `world.setBlockState(pos, state, Flag 3)` 走原版同步；GameTest 场景 14 验证保存/重载后修改仍在。
5. **部分失败** → 逐位置写入，失败即停止，报告真实已写数量并尽力回滚已写位置（回滚本身可能失败，必须如实报告，不谎报原子性）。
6. **性能** → 256 上限 + 单 tick 内完成（有界）；取消令牌在逐位置循环中检查，取消后停止并报告部分执行状态。

## 七、结论

不需要重写动态方块运行时；B/C 两类完全复用。本轮新增的是一条**独立的、与确认/权限/事件流完全集成**的世界实例编辑垂直切片，全部能力可被 14 项 GameTest 与配套 JUnit 验证。
