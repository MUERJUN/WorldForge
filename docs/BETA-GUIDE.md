# WorldForge 公测指南（v0.5.7）

> 一个 JAR 装进游戏，在 Minecraft 里创建、测试、导出真正可用的独立 Mod。

## 环境要求

| 项目 | 要求 |
|------|------|
| Minecraft | Java 版 **1.21.1** |
| 加载器 | Fabric Loader **0.16.9+** |
| 依赖 | Fabric API **0.116.5+1.21.1** |
| Java | **21**（运行 MC 与导出构建都必须） |
| 磁盘 | 至少 2 GB（导出构建的 Gradle 缓存约 1 GB） |
| 网络 | 首次导出需联网下载 Gradle + Fabric Loom 依赖（约 100-200 MB，视网络 2-10 分钟） |
| AI 功能（可选） | 一个 DeepSeek 或 OpenAI API Key |

## 安装（3 步）

1. 安装 [Fabric Loader](https://fabricmc.net/use/installer/)，选择 1.21.1。
2. 把两个 JAR 放进 `.minecraft/mods/`：
   - `fabric-api-0.116.5+1.21.1.jar`
   - `worldforge-0.5.7.jar`
3. 启动游戏。进世界后按 **K** 打开 WorldForge Studio。

## 5 分钟上手：造一把会召唤闪电的雷霆之剑

1. **创建**：按 K → 「创建」→ 输入名称"雷霆之剑"→ 添加行为「召唤闪电」，冷却 100 ticks（5 秒）→ 保存。
2. **获取**：创建后物品自动进入你的背包（创造模式）；生存模式用 `/worldforge give 雷霆之剑`。
3. **测试**：手持右键——一道闪电落在你面前的地面，5 秒内再次右键被冷却挡住。
4. **导出**：Studio 里点「导出 Mod」→ 填 Mod ID（如 `my_thunder`）→ 勾选雷霆之剑 → 点「导出 Mod JAR」→ 等待构建完成，界面显示 JAR 路径和 SHA-256。
5. **分享**：把生成的 `my_thunder-1.0.0.jar` 发给朋友——朋友只需 Fabric + Fabric API（不需要 WorldForge）就能使用这把真正的雷霆之剑。

## AI 生成（可选）

Studio → 「AI 设置」→ 填入服务商（DeepSeek / OpenAI 兼容）、URL、模型名、API Key → 用自然语言描述物品（"创建雷霆之剑，伤害12，右键召唤闪电，冷却5秒"）→ AI 提案生成 → 你确认后才会真正创建。API Key 只保存在**本机当前存档**，不上传、不打包进导出 JAR。

## 能力清单

### ✅ 导出后完整保留
- 物品/方块注册（独立 ID，与 WorldForge 无依赖）
- **召唤闪电**（右键真实生成闪电实体）
- **施加状态效果**（速度/力量/发光等，含时长与等级）
- **治疗**、**点燃**、**音效**、**粒子**
- **冷却时间**（per-player / per-block-instance，存档重启后保持，破坏方块即重置）
- 贴图（原版贴图映射）、语言文件、工具提示
- **右键触发**（on_use）的行为完整导出

### ⚠️ 导出时明确警告（需要确认，能力报告中列出）
- 物品属性数值（攻击力/护甲等）→ 报告为"没有等价实现"
- 周期方块（定时产出）→ 报告为"没有等价实现"

### ❌ 当前不支持（导出时明确列出，绝不静默丢弃）
- 动态实体、任意脚本/Java 执行
- 非 right-click 触发器（hit_entity / left_click 等——不会并入右键，明确报告为丢失）
- `experience`、`give_item` 动作（尚无代码生成实现）
- WorldForge 运行时专有 API
- 行为参数仅支持字符串/数字/布尔；嵌套结构在创建方案时拒绝

## 导出构建说明

- 导出生成完整 Gradle 工程（Gradle 8.14.3 + Fabric Loom 1.12.7），**构建工具自动按需下载**，不依赖你机器上的 Gradle。
- 需要 Java 21：导出前会自动检查 `java -version`。
- 构建超时 10 分钟；取消/失败/超时会清理半成品 JAR，绝不留下"看似成功"的文件。
- 完整源码工程保留在输出目录，方便二次开发。

## 常见问题

| 现象 | 原因与解决 |
|------|-----------|
| 按 K 没反应 | 确认已进入单人世界；检查 mods 目录里 WorldForge JAR 完整（比对 SHA-256） |
| 导出报"Java 编译环境不可用" | 安装 [JDK 21](https://adoptium.net/) 并确保 `java -version` 显示 21.x |
| 导出构建超时/失败 | 首次构建需下载依赖（2-10 分钟）；失败后重试即可，已下载部分会复用。查看源码工程 `build.gradle` 排查 |
| 朋友用导出 JAR 报缺依赖 | 朋友也需要安装 Fabric API（这是 Fabric Mod 的标准依赖，不是 WorldForge） |
| AI 设置报 401/超时 | 检查 API Key 与网络；DeepSeek URL 填 `https://api.deepseek.com`，OpenAI 填 `https://api.openai.com` |
| 闪电/效果不触发 | 检查冷却是否未到；单人世界确认不是冒险模式 |
| 输出目录被拒绝 | 目录必须为空且不含符号链接（安全要求），换个新目录即可 |

## 已知限制（公测版）

- 中文 GUI 文案在部分字体环境显示为方块（游戏语言设为简体中文可缓解）
- 高 GUI Scale（2/3）+ 854×480 小窗口下导出页布局为压缩版，建议 Scale 1 或更大窗口
- 导出的 Mod 支持 1.21.1；跨版本兼容未验证

## 兼容性说明

以上 AI 生成示例使用本地测试服务验证通过；真实 DeepSeek/OpenAI 服务的兼容性待公测用户反馈。

### 平台验证矩阵（v0.6.0 门禁）

不同验收等级严格区分，**不得混淆**：

| 平台 | 导出构建 | headless GameTest | 图形客户端 GUI | 结论 |
|------|---------|-------------------|---------------|------|
| **Windows 11** | ✅ 真实 Gradle 构建 3 工程 | ✅ 8/8、11/11、5/5 | ✅ 隔离实例 51/51（真实 JAR 构建） | **完整验证** |
| **Linux** | ✅ GitHub Actions（wrapper-only + 空白 GRADLE_USER_HOME） | ✅ 同 CI 内 3 工程 GameTest | ❌ 未运行图形客户端 | **导出构建已验证；GUI 未验证** |
| **macOS** | ❌ | ❌ | ❌ | **未验证**（无可用环境） |

- **Linux CI 证据**：run [39520762702](https://github.com/MUERJUN/WorldForge/actions/runs/39520762702) · commit `09c94ff` · ubuntu-latest · Temurin 21。构建仅经各工程自身 `./gradlew`，隔离空白 Gradle home，证明不依赖系统 Gradle 或预装缓存。
- Linux 的"已验证"**仅指导出器在无图形界面的 CI 上能生成并构建可用 Mod JAR，且导出的 Mod 能被 Fabric 加载并通过 GameTest**；**不代表** Linux 上的 WorldForge 图形客户端（Studio/AI/导出 GUI）已通过人工或自动化验收。
- macOS 因无可用 Runner 环境，整条链路均未验证，公测用户如遇 macOS 问题请优先反馈。

导出 JAR 的行为等价性由 8 项自动化 GameTest + Windows 真实客户端 51 项验收 + Linux CI 三工程真实构建与加载共同保障。
