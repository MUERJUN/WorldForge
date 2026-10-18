# v0.4.0 Release Verification

## Build

```
./gradlew clean build
```

- **JUnit**: 88 passed
- **GameTest**: 61/61 passed
- **JAR**: `build/libs/worldforge-0.4.0.jar` (412523 bytes)
- **SHA-256**: `87dbbce4f093ea8fa22d64c115f9ab1ae5d18ca7fb7aaa8da4b32fbc170b3898`

## GameTest Coverage (61 tests)

### Item tests (existing, v0.1-v0.3)
- A1-A12: AI agent bridge proposals, validation, conflict detection, type separation
- B1-B4: Block placement, NBT roundtrip, deleted definition inert, definitions reload
- L1-L6: Block lifecycle (place, break, save/reload, hot-edit, cross-save isolation, corruption)
- R1-R5: Block regression (tombstone, unbound store, periodic budget, large fields, scheduler cleanup)
- T1-T6: Block tools (validation, create/persist/delete, give, rollback, unbound, catalog)
- S1-S4: Studio business tests
- V012 tests, GameTests (R5 sync payload)

### Block tests (v0.4.0)
- A13: AI block proposal end-to-end
- A14: Item/block type isolation
- CL1: Real chunk unload → disk → reload restores identity
- CL2: Hot-edit survives chunk reload
- CL3: Deleted definition stays inert after reload
- SB1: Survival break drops identity (creative no-drop + survival drop rule + no duplication)
- SB2: Non-player removal safety (shouldDrop(null) = false)
- E2E: Full lifecycle (create → give → place → info → update → delete → inert)
- BEH1: Cooldown prevents rapid re-activation
- BEH2: Failed behavior (invalid effect) returns PASS
- BEH3: Unsupported action returns PASS
- BEH4: Delete stops behavior immediately

## Client Automation (28 steps, ALL CHECKS PASSED)

Real client run with `wf-autotest.flag`:

```
[PASS] block category opens
[PASS] block panel constructed
[PASS] block category defaults to decoration
[PASS] block appearance defaults to stone
[PASS] block appearance is a catalog entry
[PASS] create mode entered
[PASS] category cycles to a supported value
[PASS] appearance cycles within the catalog
[PASS] ESC leaves the block category
[PASS] player available for the render check
[PASS] client block mirror is populated
[PASS] renderer resolves a catalog appearance
[PASS] different virtual ids resolve different appearances
[PASS] hot-edit updated client mirror
[PASS] hot-edit changed appearance
[PASS] delete removed from client mirror
[RESULT] ALL CHECKS PASSED
```

### Screenshots (real frame buffer)
- `v040-world-blocks-view.png` — virtual blocks in real world
- `v040-world-blocks-close.png` — closer view
- `v040-hot-edit.png` — before hot-edit
- `v040-hot-edit-verify.png` — after hot-edit (appearance changed)
- `v040-delete-fallback.png` — after delete (mirror cleared)

## Acceptance Criteria Status

| Criterion | Status | Evidence |
|---|---|---|
| Create definition | ✅ | E2E, T2, B1 |
| Give block item | ✅ | E2E, T3 |
| Place in world | ✅ | E2E, B1, L1, client harness |
| Render correct appearance | ✅ | Client harness screenshots |
| Execute behavior | ✅ | BEH1-BEH4, R4 |
| Hot-edit | ✅ | E2E, L4, CL2, client harness |
| Chunk unload/reload recovery | ✅ | CL1-CL3 |
| Delete → inert | ✅ | E2E, B3, BEH4, CL3, client harness |
| Cooldown enforcement | ✅ | BEH1 |
| Client sync (create/update/delete) | ✅ | Client harness |
| Survival break → drop → identity | ⚠️ | SB1-SB2: rule + identity verified; in-world survival interaction pending |
| Non-player removal safety | ✅ | SB2 |

## Known Limitations

1. All virtual blocks share one native block type: collision shape/hardness/light/redstone cannot vary per definition.
2. Periodic overload stretches to `ceil(N/128)` ticks: guarantees bounded execution, not original period.
3. Survival mode in-world break: mock player cannot switch to survival in GameTest; `shouldDrop` rule and drop identity verified, but full in-world survival interaction (pickup → replace → identity recovery) is listed as a known limitation.
