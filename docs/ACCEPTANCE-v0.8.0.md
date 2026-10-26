# AI World Editor 第一阶段验收（v0.8.0）

> 基线：v0.7.4 `b4b48d9`。验收日期：2026-10-26。

## 一、源码审查结论

见 [docs/ARCH-REVIEW-v0.8.0.md](ARCH-REVIEW-v0.8.0.md)。三类操作严格区分：
A 世界方块实例编辑（本轮新建）、B 动态方块定义编辑（复用既有 `BlockMutationTools` +
`WorldForgeBlockTools`，不混淆）、C 独立 Mod 导出（原样保留）。

## 二、新增/修改文件

| 文件 | 动作 | 职责 |
|------|------|------|
| `ops/worldedit/WorldEditPlan.java` | 新增 | 深度不可变计划（≤256、指纹、过期） |
| `ops/worldedit/WorldEditEntry.java` | 新增 | 字符串方块描述（注册表无关层） |
| `ops/worldedit/WorldEditValidator.java` | 新增 | 边界/上限/语法校验 |
| `ops/worldedit/BlockTargetRules.java` | 新增 | 禁止目标 + BlockEntity 拒绝 + 严格状态解析 |
| `ops/worldedit/WorldEditExecutor.java` | 新增 | 服务端线程执行、预检、逐位置写入、取消、回滚、诚实报告 |
| `ops/worldedit/WorldEditHistory.java` | 新增 | 每玩家有界撤销栈（内存，不跨重启） |
| `ops/tools/WorldEditTool.java` | 新增 | `edit_world_blocks`（场景 A/B） |
| `ops/tools/UndoWorldEditTool.java` | 新增 | `undo_world_edit`（场景 C） |
| `ops/ToolCatalog.java` | 修改 | 注册 2 个新工具（14→16） |
| `ops/tools/TargetBlock.java` | 修改 | 增加命中面（区域方向推导用） |
| `ai/SuiteReservedRoutes.java` | 修改 | 精确路由注册（测试确定性应答） |
| `docs/ARCH-REVIEW-v0.8.0.md` | 新增 | 架构审查 |
| `test/WorldForgeWorldEditTests.java` | 新增 | 16 项 GameTest |

## 三、测试实际结果

### JUnit（388 PASSED / 0 FAILED / 6 SKIPPED）

新增 `WorldEditPlanTest`（14 项）+ `WorldEditHistoryTest`（8 项），覆盖：
深度不可变、条目语法拒绝、指纹确定性、变更敏感性、过期、空/超限/错配计划拒绝、
撤销历史上限、玩家/世界绑定、越界数学。**红→绿证据**：骨架类（抛
`UnsupportedOperationException`）下 22 项全部 FAILED，实现后 22 项全部 PASSED。

### WorldForge GameTest（103/103）

| # | 场景 | 结果 |
|---|------|------|
| 1 | 单方块替换成功 | ✅ |
| 2 | 5×3 区域精确变更 15 个目标 | ✅ |
| 3 | 确认之前世界完全未变 | ✅ |
| 4 | 确认期间目标变化 → 拒绝且零写入 | ✅ |
| 5 | 计划含禁止目标 → 整项预检失败 | ✅ |
| 6 | BlockEntity 目标拒绝且数据不变 | ✅ |
| 7 | 未加载区块不被强制加载 | ✅ |
| 8 | 超 256 上限拒绝 | ✅ |
| 9 | 取消中断 → 报告真实修改数与完整回滚 | ✅ |
| 10 | 撤销恢复正确状态 | ✅ |
| 11 | 撤销时目标被他人修改 → 拒绝覆盖 | ✅ |
| 12 | 玩家/世界历史隔离 | ✅ |
| 13 | 世界实例编辑与定义编辑互不混淆 | ✅ |
| 14 | 修改跨 `server.saveAll` 保存存活 | ✅ |
| +2 | 聊天闭环：确认前不变 → 确认 → 真实生效 → 撤销恢复；确认期间目标变化拒绝 | ✅ |

### 真实模型验收（4/4 PASSED，使用本地 gitignored 密钥）

| 测试 | 结果 |
|------|------|
| 真实服务回答普通问题 | PASSED |
| 真实服务产生 tool call 且参数通过严格 Schema | PASSED |
| 未提供的工具名被权限门拒绝 | PASSED |
| 完整 graft → 工具结果回灌 → ungraft 双轮链路 | PASSED |

无密钥时如实 SKIPPED（3 项），不计入真实通过。

### 其余门禁

- 导出 JAR 内容测试：3 SKIPPED（需真实导出产物，与 v0.7.4 一致）
- Linux CI：见 Release

## 四、执行链路关键设计

1. **计划即批准物**：`WorldEditPlan`（不可变）由 preparer 在服务器线程构建，
   快照 = `planId:fingerprint`；编排器执行时重跑 preparer 并比对——任何世界漂移
   都会改变指纹从而拒绝（复用 v0.7.x 确认状态机，无第二套确认）。
2. **执行器线程纪律**：世界写入一律在服务器线程；工具执行器通过
   `server.isOnThread()` 分流 + 闩锁，取消令牌可中止等待（修复了 worker 线程
   直接写世界导致的区块锁死）。
3. **fail-closed 优先**：禁止目标、BlockEntity、未加载区块、越界、状态漂移——
   任一位置不合格即整项拒绝，零写入；部分写入后取消/失败 → 有界回滚并如实报告
   applied/rolledBack/rollbackComplete，不谎报原子性。
4. **字符串不直达世界**：`WorldEditEntry` 是字符串层；`BlockTargetRules.resolve`
   经注册表严格解析（未知 id/属性/值 → 拒绝），只有解析成功的 `BlockState` 才
   进入 `setBlockState`。
5. **撤销**：每玩家 10 条、绑定世界、过期清理；撤销前逐位置核对当前状态 ==
   计划的新状态，否则拒绝覆盖。
6. **A/B 分离**：世界实例编辑遇到 WF 动态方块直接拒绝并指引定义编辑路径；
   `modify_target_block` 等定义工具原样不动。

## 五、已知限制（如实）

- **撤销历史不持久化**：仅内存，服务器重启即失效（文档已声明，未声称持久化）。
- **区域方向**：仅支持命中面为竖直面的墙；地板/天花板等水平命中面返回「方向
  不明确」由玩家澄清，模型不得猜坐标。
- **BlockEntity 目标一律拒绝**（含箱子/熔炉/其它 Mod 方块）——第一阶段保护数据。
- **复杂建筑自动识别不在本轮**：只支持有明确边界、≤256 的有限区域。
- 真实客户端图形聊天验收（截图）需在后续手动执行；本轮自动化 GameTest 覆盖了
  完整确认→执行→撤销闭环与零写入约束。
