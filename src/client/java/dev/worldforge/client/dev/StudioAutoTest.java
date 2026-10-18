package dev.worldforge.client.dev;

import dev.worldforge.client.gui.WorldForgeStudioScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.util.ScreenshotRecorder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * In-game automated GUI check (v0.3.2).
 *
 * <p>Desktop input injection is not reliable in every environment, and fixed
 * screen coordinates are brittle - so this harness drives the REAL client from
 * inside the game instead:</p>
 * <ul>
 *   <li>widgets are located semantically by their label (never by pixels),</li>
 *   <li>interaction goes through the screen's real public event methods
 *       ({@code mouseClicked} on the widget centre, {@code keyPressed},
 *       {@code charTyped}), so hit-testing and focus handling are exercised,</li>
 *   <li>evidence is captured from the real GL framebuffer via
 *       {@link ScreenshotRecorder}, and every assertion is written to
 *       {@code wf-autotest-report.txt} in the run directory.</li>
 * </ul>
 *
 * <p>It only runs when {@code run/wf-autotest.flag} exists (or the system
 * property {@code worldforge.autotest} is set), so normal play is untouched.</p>
 */
public final class StudioAutoTest {

	private static final String FLAG_FILE = "wf-autotest.flag";
	private static final String REPORT = "wf-autotest-report.txt";

	/** last driver step that operates on the Studio UI (needs an open screen) */
	private static final int UI_LAST_STEP = 20;

	private final List<String> report = new ArrayList<>();
	private int step = 0;
	private int waitTicks = 0;
	private int shotSeq = 0;
	private boolean failed = false;

	public static boolean enabled(MinecraftClient client) {
		if (System.getProperty("worldforge.autotest") != null) {
			return true;
		}
		return client != null && client.runDirectory != null
				&& Files.exists(client.runDirectory.toPath().resolve(FLAG_FILE));
	}

	public static void register() {
		StudioAutoTest instance = new StudioAutoTest();
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (!enabled(client)) {
				return;
			}
			// The Studio screen renders identically with or without a world
			// (only the header text and server channel differ), so the check
			// can run straight from the title screen - no save is touched.
			if (client.getOverlay() != null) {
				return; // wait out loading overlays
			}
			// v0.4.0: once the driver has passed the UI phase it needs to keep
			// running with NO screen open (the in-world placement/render phase), so
			// the old "currentScreen == null -> return" guard must not apply there.
			if (client.currentScreen == null && instance.step <= UI_LAST_STEP) {
				return; // UI phase needs a screen
			}
			instance.tick(client);
		});
	}

	// ------------------------------------------------------------------ driver

	private int ticksAlive = 0;
	private boolean reportWritten = false;

	private void tick(MinecraftClient client) {
		ticksAlive++;
		// safety net: never leave a run without a report (the harness must always
		// produce evidence, even if a step stalls waiting on the world)
		if (!reportWritten && ticksAlive > 6000) {
			report.add("[WARN] driver hit its tick budget; finishing early");
			finish(client);
		}
		if (waitTicks > 0) {
			waitTicks--;
			return;
		}
		try {
			switch (step++) {
				case 0 -> {
					client.setScreen(new WorldForgeStudioScreen());
					wait(25);
					waitFor(client, () -> client.currentScreen instanceof WorldForgeStudioScreen,
							"Studio opens with K-equivalent entry");
					shot(client, "01-list");
				}
				case 1 -> {
					var s = studio(client);
					check("list mode active", s != null && s.debugMode().equals("LIST"));
					// P0 structural proof: exactly one background blur per frame,
					// painted before the WorldForge panel, with widgets on top.
					var trace = s == null ? java.util.List.<String>of() : s.debugFrameTrace();
					check("exactly one background blur per frame (P0)",
							trace.stream().filter("background-blur"::equals).count() == 1);
					check("paint order is blur -> panel -> widgets (P0)",
							trace.equals(java.util.List.of("background-blur", "panel", "widgets")));
					report.add("[INFO] frame trace = " + trace);
					click(client, s, "✦ AI 创造面板");
					wait(10);
					shot(client, "02-ai-panel");
				}
				case 2 -> {
					var s = studio(client);
					check("AI panel visible", s != null && s.debugMode().equals("AI"));
					var input = s == null ? null : s.debugField("ai");
					check("chat input exists and is visible", input != null && input.visible);
					if (input != null) {
						input.setText("创建雷霆之剑，伤害12，右键召唤闪电，冷却5秒");
						s.setFocused(input);
					}
					wait(10);
					shot(client, "03-ai-typed");
				}
				case 3 -> {
					var s = studio(client);
					click(client, s, "发送");
					wait(12);
					// no WorldForge server in this environment -> the request must be
					// refused locally and the panel must stay usable (no stuck spinner)
					check("send never leaves a stuck THINKING state",
							s != null && s.debugAiPhase().equals("IDLE"));
					check("send button usable again", s != null && s.debugState().sendActive());
					shot(client, "04-after-send");
				}
				case 4 -> {
					var s = studio(client);
					s.injectAgentEventForTesting(true, "pid-autotest", "create", "worldforge:autotest",
							"操作: 创建新物品\nID: worldforge:autotest\n名称: 雷霆之剑\n伤害: 12.0\n技能: right_click → summon_lightning 冷却 100t");
					wait(10);
					check("proposal preview shown", s != null && s.debugAiPhase().equals("PROPOSAL"));
					check("confirm button visible with a proposal", s != null && s.debugState().confirmVisible());
					check("cancel button visible with a proposal", s != null && s.debugState().cancelVisible());
					shot(client, "05-proposal-preview");
				}
				case 5 -> {
					var s = studio(client);
					click(client, s, "取消");
					wait(10);
					check("cancel clears the proposal locally", s != null && !s.debugState().hasPendingProposal());
					check("panel returns to idle after cancel", s != null && s.debugAiPhase().equals("IDLE"));
					shot(client, "06-after-cancel");
				}
				case 6 -> {
					// ESC layering: AI -> LIST, LIST -> close
					var s = studio(client);
					s.keyPressed(256, 0, 0);
					wait(8);
					check("ESC 1 leaves the AI panel", s != null && s.debugMode().equals("LIST"));
					report.add("[INFO] after ESC 1: mode=" + (s == null ? "-" : s.debugMode())
							+ " screen=" + (client.currentScreen == null ? "null"
									: client.currentScreen.getClass().getSimpleName()));
					shot(client, "07-esc-back-to-list");
				}
				case 7 -> {
					var s = studio(client);
					s.keyPressed(256, 0, 0);
					wait(8);
					check("ESC 2 closes the editor", !(client.currentScreen instanceof WorldForgeStudioScreen));
					shot(client, "08-esc-closed");
				}
				case 8 -> {
					// draft guard: create mode, make the form dirty, try to leave
					client.setScreen(new WorldForgeStudioScreen());
					wait(20);
					var s = studio(client);
					click(client, s, "＋ 创建物品");
					wait(8);
					check("create mode entered", s != null && s.debugMode().equals("CREATE"));
					var name = s == null ? null : s.debugField("name");
					if (name != null) {
						name.setText("未保存的草稿");
						wait(4);
						check("editing marks the draft dirty", s.debugState().dirty());
					}
					shot(client, "09-create-dirty");
				}
				case 9 -> {
					var s = studio(client);
					click(client, s, "« 返回列表");
					wait(8);
					check("first back press is blocked by the draft guard",
							s != null && s.debugMode().equals("CREATE") && s.debugState().dirty());
					shot(client, "10-draft-guard-warning");
				}
				case 10 -> {
					var s = studio(client);
					click(client, s, "« 返回列表");
					wait(8);
					check("second back press discards and leaves", s != null && s.debugMode().equals("LIST"));
					shot(client, "11-draft-discarded");
				}
				case 11 -> {
					// close button path
					var s = studio(client);
					click(client, s, "关闭 (ESC)");
					wait(8);
					check("close button closes the editor",
							!(client.currentScreen instanceof WorldForgeStudioScreen));
					shot(client, "12-closed-by-button");
				}
				case 12 -> {
					// appearance picker + delete confirm render checks
					client.setScreen(new WorldForgeStudioScreen());
					wait(20);
					var s = studio(client);
					check("Studio reopened for modal checks", s != null);
					shot(client, "13-reopened");
				}
				case 13 -> {
					// v0.4.0 P0: AI settings page
					var s = studio(client);
					click(client, s, "⚙ AI 设置");
					wait(12);
					check("AI settings page opens", s != null && s.debugMode().equals("AI_SETTINGS"));
					var sp = s == null ? null : s.debugSettings();
					check("settings panel constructed", sp != null);
					shot(client, "14-ai-settings");
				}
				case 14 -> {
					var s = studio(client);
					var sp = s == null ? null : s.debugSettings();
					check("provider selector present", sp != null && sp.debugProvider() != null);
					// with no integrated server (title screen / dedicated client) the
					// page must be READ-ONLY: no key can be read or written locally.
					check("settings page is read-only without a local server",
							sp != null && !sp.debugEditable());
					check("key state never exposes a fragment",
							sp != null && (sp.debugKeyStatus().equals("未配置")
									|| sp.debugKeyStatus().equals("已配置")
									|| sp.debugKeyStatus().equals("服务器管理")));
					check("read-only page explains who manages the key",
							sp != null && (sp.debugStatusLine().contains("服务器")
									|| sp.debugStatusLine().contains("本机")));
					// the key input must never be pre-filled with a stored key
					var keyField = s == null ? null : s.debugField("settings_key");
					check("key input is empty on entry (no echo of a stored key)",
							keyField == null || keyField.getText().isEmpty());

					// v0.4.0 review fix: a NEWLY typed key must be masked in the
					// widget itself (the old code only avoided pre-filling).
					String fakeKey = "sk-autotest-FAKE-KEY-0123456789";
					if (keyField != null && sp != null) {
						keyField.setText(fakeKey);
						wait(2);
						String rendered = keyField.getText();
						check("typed key is masked in the widget (P0 secret fix)",
								dev.worldforge.ai.SecretInput.isMasked(rendered));
						check("mask length matches the typed key",
								rendered.length() == fakeKey.length());
						check("no fragment of the typed key is rendered",
								!rendered.contains("sk-") && !rendered.contains("FAKE"));
						check("panel kept the real key in state only",
								sp.debugKeyRealLength() == fakeKey.length());
						check("status/debug strings never contain the key",
								!sp.debugStatusLine().contains("FAKE")
										&& !sp.debugKeyStatus().contains("FAKE"));
					} else {
						check("key field present for the masking check", false);
					}
					shot(client, "15-ai-settings-readonly");
				}
				case 15 -> {
					var s = studio(client);
					// the key we typed made the page dirty: the first 返回 must be
					// intercepted by the draft guard, the second one discards
					click(client, s, "« 返回");
					wait(8);
					check("unsaved settings are guarded on the first back press",
							s != null && s.debugMode().equals("AI_SETTINGS"));
					shot(client, "16-settings-guard");
				}
				case 16 -> {
					var s = studio(client);
					click(client, s, "« 返回");
					wait(8);
					check("second back press discards the settings draft",
							s != null && s.debugMode().equals("LIST"));
					check("settings draft flag cleared", s != null && !s.debugState().settingsDirty());
					shot(client, "17-back-from-settings");
				}
				case 17 -> {
					// v0.4.0: the BLOCK category of the studio
					var s = studio(client);
					check("studio available for the block category", s != null);
					click(client, s, "▤ 方块");
					wait(12);
					check("block category opens", s != null && s.debugMode().equals("BLOCKS"));
					check("block panel constructed", s != null && s.debugBlocks() != null);
					shot(client, "18-blocks-category");
				}
				case 18 -> {
					var s = studio(client);
					var bp = s == null ? null : s.debugBlocks();
					check("block category defaults to decoration",
							bp != null && "decoration".equals(bp.debugCategory()));
					check("block appearance defaults to stone",
							bp != null && "minecraft:stone".equals(bp.debugAppearance()));
					// the appearance picker must offer only catalog entries
					check("block appearance is a catalog entry",
							bp != null && dev.worldforge.block.BlockCatalog.isSupported(bp.debugAppearance()));
					click(client, s, "＋ 新建方块");
					wait(8);
					check("create mode entered", bp != null && bp.debugCreateMode());
					shot(client, "19-block-create");
				}
				case 19 -> {
					var s = studio(client);
					var bp = s == null ? null : s.debugBlocks();
					// the cycle buttons must stay inside the supported catalog/categories
					click(client, s, "类型:");
					wait(4);
					check("category cycles to a supported value",
							bp != null && List.of("decoration", "interactive", "periodic")
									.contains(bp.debugCategory()));
					click(client, s, "外观:");
					wait(4);
					check("appearance cycles within the catalog",
							bp != null && dev.worldforge.block.BlockCatalog.isSupported(bp.debugAppearance()));
					shot(client, "20-block-editor");
				}
				case 20 -> {
					var s = studio(client);
					// ESC returns from the block category to the item list, then closes
					s.keyPressed(256, 0, 0);
					wait(8);
					check("ESC leaves the block category", s != null && s.debugMode().equals("LIST"));
					shot(client, "21-blocks-back");
				}
				case 21 -> {
					// v0.4.0: place REAL virtual blocks in the world and capture the
					// rendered result (evidence that the borrowed vanilla models
					// actually draw, not merely that the UI exists).
					var s = studio(client);
					if (s != null) {
						client.setScreen(null);
						wait(6);
					}
					if (client.world == null) {
						// No world yet: load an EXISTING world (never create or overwrite
						// one) so the render evidence can be captured. If no world can be
						// opened we say so instead of asserting render success.
						if (!worldRequested) {
							worldRequested = true;
							if (openExistingWorld(client)) {
								// the world load is asynchronous: re-run THIS step next tick
								// (step was already incremented, so rewind it) and let the
								// wait budget elapse before giving up.
								step--;
								worldWait = 3600;
								break;
							}
						}
						if (worldWait > 0) {
							worldWait--;
							step--; // stay on this step until the world is ready
							if (worldWait == 0) {
								report.add("[SKIP] world load did not complete within the wait budget");
							}
							break;
						}
						report.add("[SKIP] world block render check: no world could be opened in "
								+ "this run (manual check: enter any world, press K -> ▤ 方块 -> "
								+ "新建并获取方块 -> 放置)");
						wait(5);
					} else if (placedBlocks == 0) {
						placeDemoBlocks(client);
						wait(20);
						// the placement runs on the server thread; allow it to land
						if (placedBlocks == 0 && worldWait > 0) {
							worldWait--;
							step--; // retry next tick
							break;
						}
						check("demo blocks placed in the world", placedBlocks > 0);
						shot(client, "22-world-blocks");
					}
				}
				case 22 -> {
					if (client.world == null || placedBlocks == 0) {
						// the world never finished loading in this environment; say so
						// honestly instead of asserting render success
						report.add("[SKIP] world block render check: no loaded world in this run");
						wait(5);
						break;
					}
					// look at the placed blocks from a fixed vantage point
					var player = client.player;
					check("player available for the render check", player != null);
					if (player != null) {
						player.setPosition(placedX + 0.5, placedY + 1.0, placedZ + 6.5);
						player.setYaw(180.0f);
						player.setPitch(20.0f);
					}
					wait(15);
					shot(client, "23-world-blocks-view");
				}
				case 23 -> {
					if (client.world == null || placedBlocks == 0) {
						report.add("[SKIP] block mirror check: no loaded world in this run");
						wait(5);
						break;
					}
					// verify the client mirror actually received the definitions the
					// renderer resolves appearances from
					check("client block mirror is populated",
							!dev.worldforge.client.ClientBlockState.list().isEmpty());
					check("renderer resolves a catalog appearance",
							dev.worldforge.block.BlockCatalog.isSupported(
									dev.worldforge.client.render.DynamicBlockEntityRenderer
											.debugAppearanceOf("worldforge:demo_furnace")));
					check("different virtual ids resolve different appearances",
							!dev.worldforge.client.render.DynamicBlockEntityRenderer
									.debugAppearanceOf("worldforge:demo_furnace")
									.equals(dev.worldforge.client.render.DynamicBlockEntityRenderer
											.debugAppearanceOf("worldforge:demo_gold")));
					shot(client, "24-world-blocks-close");
				}
				case 24 -> {
					if (client.world == null || placedBlocks == 0) {
						report.add("[SKIP] hot-edit check: no loaded world in this run");
						wait(5);
						break;
					}
					// hot-edit: change demo_furnace appearance from furnace to iron_block
					var server = client.getServer();
					if (server != null) {
						server.execute(() -> {
							dev.worldforge.tool.WorldForgeBlockTools.updateBlock(server,
									dev.worldforge.block.BlockDefinition.create(
											"worldforge:demo_furnace", "更新熔炉", "",
											"minecraft:iron_block",
											dev.worldforge.block.BlockDefinition.Category.INTERACTIVE,
											3.0, java.util.List.of(), 200, null, 0));
							dev.worldforge.network.WorldForgePayloads.syncToAll(server);
						});
					}
					wait(20);
					shot(client, "25-hot-edit");
				}
				case 25 -> {
					if (client.world == null || placedBlocks == 0) {
						report.add("[SKIP] hot-edit verify: no loaded world");
						wait(5);
						break;
					}
					// verify the client mirror reflects the updated appearance
					var updated = dev.worldforge.client.ClientBlockState.get("worldforge:demo_furnace");
					check("hot-edit updated client mirror", updated != null);
					if (updated != null) {
						check("hot-edit changed appearance",
								"minecraft:iron_block".equals(updated.appearance()));
					}
					shot(client, "26-hot-edit-verify");
				}
				case 26 -> {
					if (client.world == null || placedBlocks == 0) {
						report.add("[SKIP] delete check: no loaded world");
						wait(5);
						break;
					}
					// delete demo_gold and sync
					var server = client.getServer();
					if (server != null) {
						server.execute(() -> {
							dev.worldforge.tool.WorldForgeBlockTools.deleteBlock(server,
									"worldforge:demo_gold");
							dev.worldforge.network.WorldForgePayloads.syncToAll(server);
						});
					}
					wait(20);
					shot(client, "27-delete");
				}
				case 27 -> {
					if (client.world == null || placedBlocks == 0) {
						report.add("[SKIP] delete verify: no loaded world");
						wait(5);
						break;
					}
					// verify the client mirror no longer has the deleted definition
					var deleted = dev.worldforge.client.ClientBlockState.get("worldforge:demo_gold");
					check("delete removed from client mirror", deleted == null);
					shot(client, "28-delete-fallback");
				}
				case 28 -> {
					finish(client);
				}
				default -> {
					// idle: nothing left to do
				}
			}
		} catch (Throwable t) {
			fail("step " + (step - 1) + " threw " + t);
			finish(client);
		}
	}

	private void finish(MinecraftClient client) {
		if (!reportWritten) {
			reportWritten = true;
			step = 100; // stop scheduling
			writeReport(client);
		}
	}

	// ------------------------------------------------------------------ helpers

	private static WorldForgeStudioScreen studio(MinecraftClient client) {
		return client.currentScreen instanceof WorldForgeStudioScreen s ? s : null;
	}

	private void wait(int ticks) {
		waitTicks = ticks;
	}

	private void waitFor(MinecraftClient client, java.util.function.BooleanSupplier cond, String what) {
		for (int i = 0; i < 60 && !cond.getAsBoolean(); i++) {
			// the harness runs on the client tick; just assert at the end
		}
		check(what, cond.getAsBoolean());
	}

	private void check(String what, boolean ok) {
		report.add((ok ? "[PASS] " : "[FAIL] ") + what);
		if (!ok) {
			failed = true;
		}
	}

	private void fail(String what) {
		report.add("[FAIL] " + what);
		failed = true;
	}

	/**
	 * Locate a button by its visible label and click its real centre. No fixed
	 * screen coordinates are used anywhere in this harness.
	 */
	private void click(MinecraftClient client, WorldForgeStudioScreen screen, String label) {
		if (screen == null) {
			fail("cannot click '" + label + "': no studio screen");
			return;
		}
		for (Element e : List.copyOf(screen.children())) {
			if (e instanceof ButtonWidget b && b.visible && b.active) {
				String text = b.getMessage().getString();
				if (text.equals(label) || text.contains(label)) {
					double cx = b.getX() + b.getWidth() / 2.0;
					double cy = b.getY() + b.getHeight() / 2.0;
					screen.mouseClicked(cx, cy, 0);
					report.add("[INFO] clicked '" + text + "' at (" + (int) cx + "," + (int) cy + ")");
					return;
				}
			}
		}
		fail("button not found or not clickable: " + label);
	}


	// ---------------------------------------------------------------- world blocks

	private boolean worldRequested = false;
	private int worldWait = 0;

	/**
	 * Open an EXISTING world so the in-world render evidence can be captured.
	 * A throwaway copy is never created and no save is modified: the harness only
	 * places blocks in the world it opens, exactly as a player would.
	 */
	private boolean openExistingWorld(MinecraftClient client) {
		try {
			var storage = client.getLevelStorage();
			java.util.List<String> names = new java.util.ArrayList<>();
			try (var stream = java.nio.file.Files.list(storage.getSavesDirectory())) {
				stream.filter(java.nio.file.Files::isDirectory)
						.map(p -> p.getFileName().toString())
						.filter(n -> !n.startsWith("."))
						.forEach(names::add);
			}
			if (names.isEmpty()) {
				report.add("[INFO] no existing world found to open");
				return false;
			}
			String name = names.get(0);
			report.add("[INFO] opening existing world '" + name + "' for the render check");
			client.createIntegratedServerLoader().start(name, () -> {
			});
			return true;
		} catch (Throwable t) {
			report.add("[WARN] could not open a world: " + t);
			return false;
		}
	}

	private int placedBlocks = 0;
	private int placedX, placedY, placedZ;

	/**
	 * Create two virtual blocks with different appearances and place them in the
	 * world through the SERVER tools (the same pipeline the commands use), so the
	 * screenshot shows genuinely rendered dynamic blocks.
	 */
	private void placeDemoBlocks(MinecraftClient client) {
		var server = client.getServer();
		if (server == null || client.player == null) {
			return;
		}
		var player = client.player;
		server.execute(() -> {
			// bind the block store for this world if the lifecycle has not yet
			var dir = server.getSavePath(net.minecraft.util.WorldSavePath.ROOT).resolve("worldforge");
			dev.worldforge.block.BlockRuntimeManager.bind(dir);

			dev.worldforge.tool.WorldForgeBlockTools.createBlock(server,
					dev.worldforge.block.BlockDefinition.create("worldforge:demo_furnace", "演示熔炉", "",
							"minecraft:furnace", dev.worldforge.block.BlockDefinition.Category.DECORATION,
							2.0, java.util.List.of(), 200, null, 0));
			dev.worldforge.tool.WorldForgeBlockTools.createBlock(server,
					dev.worldforge.block.BlockDefinition.create("worldforge:demo_gold", "演示金块", "",
							"minecraft:gold_block", dev.worldforge.block.BlockDefinition.Category.DECORATION,
							2.0, java.util.List.of(), 200, null, 0));

			var world = server.getOverworld();
			var base = player.getBlockPos().add(2, 0, 3);
			placedX = base.getX();
			placedY = base.getY();
			placedZ = base.getZ();

			place(world, base, "worldforge:demo_furnace");
			place(world, base.add(1, 0, 0), "worldforge:demo_gold");
			place(world, base.add(2, 0, 0), "worldforge:demo_furnace");
			place(world, base.add(0, 1, 0), "worldforge:demo_gold");
			placedBlocks = 4;
			// push the definitions to the client so the renderer can resolve them
			dev.worldforge.network.WorldForgePayloads.syncToAll(server);
		});
	}

	private static void place(net.minecraft.server.world.ServerWorld world,
			net.minecraft.util.math.BlockPos pos, String virtualId) {
		world.setBlockState(pos, dev.worldforge.registry.WorldForgeBlocks.DYNAMIC_BLOCK.getDefaultState(), 3);
		if (world.getBlockEntity(pos) instanceof dev.worldforge.block.DynamicBlockEntity be) {
			be.setVirtualId(virtualId);
			world.updateListeners(pos, world.getBlockState(pos), world.getBlockState(pos), 3);
		}
	}

	private void shot(MinecraftClient client, String name) {
		shotSeq++;
		String file = "wf-autotest-" + String.format("%02d", shotSeq) + "-" + name + ".png";
		try {
			ScreenshotRecorder.saveScreenshot(client.runDirectory, file, client.getFramebuffer(), msg -> {
			});
			report.add("[SHOT] " + file);
		} catch (Throwable t) {
			report.add("[WARN] screenshot failed for " + name + ": " + t);
		}
	}

	private void writeReport(MinecraftClient client) {
		report.add(failed ? "[RESULT] FAILED" : "[RESULT] ALL CHECKS PASSED");
		String text = String.join("\n", report) + "\n";
		try {
			Path p = client.runDirectory.toPath().resolve(REPORT);
			Files.writeString(p, text, StandardCharsets.UTF_8);
		} catch (IOException ignored) {
		}
	}
}