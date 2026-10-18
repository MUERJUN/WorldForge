# v0.5.0 Release Verification

## Build

```
./gradlew clean build
```

- **JUnit**: 113 passed
- **GameTest**: 61/61 passed
- **JAR**: `build/libs/worldforge-0.5.0.jar` (433355 bytes)
- **SHA-256**: `9c7273bb9ca700448bdd90ad2dbef7dc73d213302263ff74d3ac8535abc132ca`

## v0.5.0 Features

### 独立 Mod JAR 导出器

玩家在 WorldForge Studio 中创建的物品和方块可以导出为独立 Fabric Mod JAR。

**导出流程**：
1. `ModExporter.validate()` — 验证 mod ID、名称、版本、输出目录、定义存在性
2. `ModExporter.checkUnsupported()` — 检查不支持的能力，返回警告
3. `TemplateProjectGenerator.generate()` — 生成完整 Fabric 1.21.1 项目
4. `TemplateProjectGenerator.copyGradleWrapper()` — 复制 Gradle Wrapper
5. `ExportExecutor.export()` — 编排：验证 → 生成 → 构建 → SHA-256

**生成的项目结构**：
```
standalone_mod/
├── build.gradle          # fabric-loom 1.12.7, MC 1.21.1
├── settings.gradle       # pluginManagement with FabricMC maven
├── gradle.properties     # MC/Yarn/Loader/FAPI versions
├── gradlew / gradlew.bat # Gradle Wrapper
├── LICENSE               # MIT
└── src/main/
    ├── java/standalone/mod/
    │   ├── StandaloneModMod.java    # ModInitializer
    │   ├── ModItems.java            # Registry.register for items
    │   ├── ModBlocks.java           # Registry.register for blocks
    │   └── ExportedModTest.java     # GameTest verification
    └── resources/
        ├── fabric.mod.json          # mod metadata, no WorldForge dep
        └── assets/standalone_mod/
            ├── lang/en_us.json      # item/block names
            ├── models/item/         # item models
            ├── models/block/        # block models
            └── blockstates/         # blockstate files
```

### 安全验证

- Mod ID: `[a-z][a-z0-9_]{1,31}` — 拒绝路径穿越、Shell 注入、空格、点
- Mod 名称: 拒绝 `;|`$()` 等 Shell 字符和控制字符
- 版本号: `X.Y.Z` 格式
- 输出目录: 必须为空（避免覆盖）
- 不支持的能力: 明确警告，不静默丢失
- 无 API Key、agent.json、世界存档打包进 JAR

## 独立实例验证

导出的 `standalone_mod-1.0.0.jar` 在仅安装 Fabric API 的 Minecraft 1.21.1 实例中：

```
./gradlew runGameTest
→ All 2 required tests passed :)
→ BUILD SUCCESSFUL
```

- `itemRegistered1`: `standalone_mod:standalone_sword` 注册成功
- `blockRegistered2`: `standalone_mod:standalone_block` 注册成功

**JAR**: 6789 bytes, SHA-256 `29a4f401d16df422c9892b886ee5bf12c16966796dd7ab98a833485c22c31852`

## v0.4.1 生存模式验证

通过真实客户端自动化验证（30 步 ALL CHECKS PASSED）：
- 生存模式破坏 → 恰好 1 个掉落物 → 携带正确 VirtualBlockId
- 重放后身份恢复
- 创造模式破坏不产生掉落

## Test Coverage

| Category | Count | Description |
|---|---|---|
| JUnit | 113 | 包括 13 个导出器验证 + 2 个模板生成 + 1 个构建集成 + 10 个安全测试 |
| GameTest | 61 | 真实服务端 GameTest |
| 客户端自动化 | 30 步 | 真实客户端，包括生存模式破坏 |
| 独立实例 | 2/2 | 导出 JAR 在无 WorldForge 实例中加载 |

## Known Limitations

1. 导出的方块使用 `Block.Settings.create().strength(2.0f)` — 外观通过模型文件实现，但碰撞/硬度统一
2. 导出器第一版不支持自定义行为代码 — 仅注册基本 Item/Block
3. 所有虚拟方块共用一个原生方块类型（继承自 v0.4.0）
4. 周期超载拉长为 `ceil(N/128)` tick（继承自 v0.4.0）
