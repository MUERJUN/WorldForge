# v0.5.0 Standalone Mod Export Verification

## Export

- **Source**: WorldForge v0.5.0 exporter
- **Mod ID**: `standalone_mod`
- **Mod Name**: Standalone Mod
- **Version**: 1.0.0
- **Contents**: 1 item (`standalone_sword`), 1 block (`standalone_block`)

## Build

```
cd build/standalone-export/standalone_mod
./gradlew clean build
```

- **JAR**: `standalone_mod-1.0.0.jar` (6789 bytes)
- **Sources JAR**: `standalone_mod-1.0.0-sources.jar` (6173 bytes)
- **SHA-256**: `29a4f401d16df422c9892b886ee5bf12c16966796dd7ab98a833485c22c31852`

## JAR Contents

```
assets/standalone_mod/lang/en_us.json
fabric.mod.json
standalone/mod/ExportedModTest.class
standalone/mod/ModBlocks.class
standalone/mod/ModItems.class
standalone/mod/StandaloneModMod.class
```

## Dependencies (from fabric.mod.json)

```json
"depends": {
    "fabricloader": ">=0.16.0",
    "minecraft": "~1.21.1",
    "java": ">=21",
    "fabric-api": "*"
}
```

**No WorldForge dependency** — the mod runs standalone.

## Runtime Verification

```
./gradlew runGameTest
```

Result:
```
[Server thread/INFO] (Minecraft) All 2 required tests passed :)
BUILD SUCCESSFUL in 55s
```

### Tests run

1. `itemRegistered1`: Verifies `standalone_mod:standalone_sword` is registered in `Registries.ITEM`
2. `blockRegistered2`: Verifies `standalone_mod:standalone_block` is registered in `Registries.BLOCK`

Both tests passed in a real Minecraft 1.21.1 server instance with only
Fabric API installed — **no WorldForge**.

## Conclusion

The exported JAR:
- ✅ Builds successfully against only Fabric API
- ✅ Contains valid fabric.mod.json with no WorldForge dependency
- ✅ Loads in a real Minecraft server
- ✅ Registers items and blocks at runtime
- ✅ Passes GameTests in a standalone instance
