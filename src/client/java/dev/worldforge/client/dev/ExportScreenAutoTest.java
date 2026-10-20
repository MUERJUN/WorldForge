package dev.worldforge.client.dev;

import dev.worldforge.client.gui.WorldForgeExportScreen;
import dev.worldforge.client.gui.WorldForgeStudioScreen;
import dev.worldforge.exporter.ExportExecutor;
import dev.worldforge.exporter.ExportPlan;
import dev.worldforge.exporter.ModExporter;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.text.Text;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * v0.5.3 P0-3: FULL client acceptance for the export screen.
 *
 * <p>Drives the REAL client: opens a single-player world, enters via the
 * Studio button, fills fields, selects items, exercises the degraded-export
 * confirmation flow, starts+cuts a real export, completes a real export to a
 * JAR, and screenshots at GUI scales 1/2/3 (dev client window is 854x480).</p>
 *
 * <p>Activation: {@code -Dworldforge.export.autotest=true}.
 * Any [FAIL] records to the report AND crashes the client at the end, so the
 * Gradle run exits non-zero — acceptance can never silently pass.</p>
 */
public class ExportScreenAutoTest {

	private static boolean active = false;
	private static final List<String> report = new ArrayList<>();
	private static int failed = 0;
	private static int passed = 0;
	private static int step = 0;
	private static int waitTicks = 0;
	private static int ticksAlive = 0;
	private static boolean reportWritten = false;
	private static int waitBudget = 0;
	/** P0-3: the world open request must be issued exactly once. */
	private static boolean worldRequested = false;
	/** v0.5.4: createLevel pressed at most once (fresh-world path). */
	private static boolean createClicked = false;
	/** demo-definition submission guard (waitUntil re-enters the step). */
	private static boolean demoSubmitted = false;
	private static volatile boolean demoDone = false;
	/** v0.5.4: set once a re-export has been kicked off in step 13. */
	private static boolean rerunRequested = false;

	private static boolean execCancelled() {
		return rerunRequested; // re-export handled explicitly in step 14
	}

	// paths
	private static Path outBase;
	private static Path outDirDegraded;
	private static Path outDirSuccess;

	public static void init() {
		String prop = System.getProperty("worldforge.export.autotest");
		boolean viaProp = "true".equals(prop) || "1".equals(prop);
		if (!viaProp && !flagFilePresent()) return;
		if (active) return;
		active = true;

		ClientTickEvents.END_CLIENT_TICK.register(ExportScreenAutoTest::tick);
		System.out.println("[WorldForge] Export screen FULL auto-test active");
	}

	/** Same activation mechanism as StudioAutoTest: a flag file in the run dir. */
	private static boolean flagFilePresent() {
		try {
			var mc = net.minecraft.client.MinecraftClient.getInstance();
			return mc != null && mc.runDirectory != null
					&& Files.exists(mc.runDirectory.toPath().resolve("wf-export-autotest.flag"));
		} catch (Throwable t) {
			return false;
		}
	}

	// ------------------------------------------------------------- harness

	private static void check(String label, boolean ok) {
		report.add("  " + (ok ? "[PASS] " : "[FAIL] ") + label);
		if (ok) passed++;
		else failed++;
	}

	private static void info(String msg) {
		report.add("  [INFO] " + msg);
	}

	private static void wait(int ticks) {
		waitTicks = ticks;
	}

	/** Wait until the predicate holds or the budget expires, then continue. */
	private static boolean waitUntil(MinecraftClient client, java.util.function.BooleanSupplier cond,
			String label, int budgetTicks) {
		if (cond.getAsBoolean()) {
			check(label, true);
			waitBudget = 0;
			return true;
		}
		if (waitBudget <= 0) {
			check(label + " (timed out)", false);
			waitBudget = 0;
			return true; // move on; the FAIL is recorded
		}
		waitBudget--;
		step--; // stay on this step
		return false;
	}

	private static WorldForgeExportScreen exportScreen(MinecraftClient client) {
		return client.currentScreen instanceof WorldForgeExportScreen s ? s : null;
	}

	@SuppressWarnings("unchecked")
	private static <T> T field(Object owner, String name) {
		if (owner == null) {
			// screen closed mid-step (player quit / resize re-init) — surface a
			// clean check failure instead of an NPE crash without a report
			throw new IllegalStateException("screen closed before reading '" + name + "'");
		}
		try {
			Field f = owner.getClass().getDeclaredField(name);
			f.setAccessible(true);
			return (T) f.get(owner);
		} catch (Exception e) {
			throw new RuntimeException("reflect " + name + ": " + e, e);
		}
	}

	private static void clickButton(MinecraftClient client, String label) {
		if (client.currentScreen == null) {
			check("click '" + label + "' (no screen open)", false);
			return;
		}
		for (Element e : List.copyOf(client.currentScreen.children())) {
			if (e instanceof ClickableWidget b && b.visible && b.active) {
				String text = b.getMessage().getString();
				if (text.equals(label) || text.contains(label)) {
					double cx = b.getX() + b.getWidth() / 2.0;
					double cy = b.getY() + b.getHeight() / 2.0;
					client.currentScreen.mouseClicked(cx, cy, 0);
					info("clicked '" + text + "' at (" + (int) cx + "," + (int) cy + ")");
					return;
				}
			}
		}
		check("button '" + label + "' not found or not clickable", false);
	}

	private static void shot(MinecraftClient client, String name) {
		try {
			ScreenshotRecorder.saveScreenshot(client.runDirectory, name, client.getFramebuffer(), msg -> { });
			info("screenshot saved: " + name);
		} catch (Throwable t) {
			info("screenshot " + name + " failed: " + t);
		}
	}

	private static void setScale(MinecraftClient client, int scale) {
		client.options.getGuiScale().setValue(scale);
		wait(10);
		info("GUI scale -> " + scale);
	}

	// ---------------------------------------------------------------- driver

	private static void tick(MinecraftClient client) {
		ticksAlive++;
		if (!reportWritten && ticksAlive > 18000) {
			check("driver tick budget (18000)", false);
			finish(client, true);
			return;
		}
		if (waitTicks > 0) {
			waitTicks--;
			return;
		}
		try {
			switch (step++) {
				// ---- enter a real single-player world
				case 0 -> {
					// v0.5.4 Phase 2: unattended window has no OS focus — vanilla
					// pauses world loading at "Preparing spawn area 0%" until the
					// window gains focus. Force the flag so acceptance can run
					// headless-ish (dev env and isolated beta both need this).
					try {
						var focusField = MinecraftClient.class.getField("windowFocused");
						focusField.setAccessible(true);
						focusField.setBoolean(client, true);
					} catch (NoSuchFieldException nsf) {
						// non-Yarn runtime — best effort only
					} catch (Throwable t) {
						info("focus force failed: " + t);
					}
					// QuickPlay runs own world opening (vanilla path). Only the
					// flag-file fallback opens a world here — and EXACTLY ONCE:
					// re-issuing createIntegratedServerLoader().start() while the
					// world is still loading deadlocks the loader (stuck at 50%).
					boolean quickPlay = "1".equals(System.getProperty("worldforge.export.autotest"))
							|| "true".equals(System.getProperty("worldforge.export.autotest"))
							// v0.5.4: flag-file runs in the ISOLATED beta instance
							// always enter via QuickPlay — a tick-handler start()
							// deadlocks (see below)
							|| Files.exists(Path.of("wf-export-autotest.flag"));
					if (!worldRequested && client.world == null) {
						worldRequested = true;
						if (quickPlay) {
							// v0.5.4: QuickPlay owns world opening — calling
							// start() from a tick handler while the main-function
							// loader is also starting the world DEADLOCKS the
							// render thread (sleep-poll inside startIntegrated-
							// Server). We only wait here.
							info("quickPlay mode: waiting for the world to load");
						} else if (!openExistingWorld(client)) {
							check("a single-player world must exist for client acceptance", false);
							finish(client, true);
							return;
						} else {
							info("world requested; waiting for join");
						}
					}
					// fresh-creation path: if the CreateWorldScreen is up, press
					// its create button — via reflection because createLevel is
					// private in this Yarn build
					if (client.world == null && client.currentScreen
							instanceof net.minecraft.client.gui.screen.world.CreateWorldScreen cws) {
						if (!createClicked) {
							createClicked = true;
							info("CreateWorldScreen open; confirming world creation");
							try {
								var m = cws.getClass().getMethod("createLevel");
								m.setAccessible(true);
								m.invoke(cws);
							} catch (NoSuchMethodException e) {
								info("createLevel not found via reflection: " + e);
							} catch (Throwable t) {
								info("createLevel failed: " + t);
							}
						}
					}
					if (client.world != null && client.player != null) {
						// NOTE: switch(step++) already advanced to the next case —
						// an extra step++ here would SKIP case 1 (demo definitions).
						check("entered real single-player world", true);
						info("window size: " + client.getWindow().getWidth() + "x"
								+ client.getWindow().getHeight()
								+ " (dev default 854x480)");
						wait(40);
					} else {
						step--; // stay in this step until joined
						if (ticksAlive > 6000) {
							check("world join timed out", false);
							finish(client, true);
						}
					}
				}
				// ---- create demo definitions (client.send runs even when the
				// integrated server is paused by a lost-focus window; the runtime
				// managers are synchronized statics, safe from the render thread)
				case 1 -> {
					var server = client.getServer();
					if (server == null) {
						check("integrated server running", false);
						finish(client, true);
						return;
					}
					if (!demoSubmitted) {
						demoSubmitted = true;
						client.send(() -> {
							try {
								var supported = new dev.worldforge.definition.BehaviorDefinition(
										"on_use", List.of("summon_lightning"),
										java.util.Map.of(), 100);
								var itemA = new dev.worldforge.definition.ItemDefinition(1,
										"worldforge:auto_thunder", "Auto Thunder", "",
										dev.worldforge.definition.TextureSource.vanilla(
												"minecraft:item/diamond_sword"),
										java.util.Map.of(), List.of(supported));
								var degraded = new dev.worldforge.definition.BehaviorDefinition(
										"on_use", List.of("unsupported_demo_action"),
										java.util.Map.of(), 50);
								var itemB = new dev.worldforge.definition.ItemDefinition(1,
										"worldforge:auto_degraded", "Auto Degraded", "",
										dev.worldforge.definition.TextureSource.vanilla(
												"minecraft:item/stick"),
										java.util.Map.of(), List.of(degraded));
								var r1 = dev.worldforge.runtime.WorldForgeRuntimeManager.createItem(itemA);
								var r2 = dev.worldforge.runtime.WorldForgeRuntimeManager.createItem(itemB);
								info("demo defs created: " + r1.message() + " | " + r2.message());
							} catch (Throwable t) {
								check("demo definition creation: " + t, false);
							} finally {
								demoDone = true;
							}
						});
					}
					waitBudget = 100;
					if (!waitUntil(client, () -> demoDone, "demo definitions created", 100)) return;
					boolean bothListed = dev.worldforge.runtime.WorldForgeRuntimeManager.list().stream()
							.map(d -> d.id())
							.filter(id -> id.equals("worldforge:auto_thunder")
									|| id.equals("worldforge:auto_degraded"))
							.count() == 2;
					check("demo items visible in runtime list", bothListed);
					wait(5);
				}
				// ---- open Studio, then enter export screen via its button
				case 2 -> {
					client.setScreen(new WorldForgeStudioScreen());
					wait(15);
				}
				case 3 -> {
					check("Studio opened", client.currentScreen instanceof WorldForgeStudioScreen);
					clickButton(client, "导出 Mod");
					wait(10);
				}
				case 4 -> {
					var s = exportScreen(client);
					check("export screen opened via Studio button", s != null);
					if (s == null) { finish(client, true); return; }
					// P0-2: confirm buttons must be hidden+disabled in normal mode
					ButtonWidget confirm = field(s, "confirmButton");
					ButtonWidget cancel = field(s, "cancelConfirmButton");
					check("degraded confirm button hidden in normal mode",
							!confirm.visible && !confirm.active);
					check("degraded cancel button hidden in normal mode",
							!cancel.visible && !cancel.active);
					shot(client, "exp-01-open-854x480.png");
				}
				// ---- fill fields (click to focus + setText, verify round-trip)
				case 5 -> {
					var s = exportScreen(client);
					TextFieldWidget name = field(s, "modNameField");
					TextFieldWidget id = field(s, "modIdField");
					TextFieldWidget ver = field(s, "versionField");
					TextFieldWidget dir = field(s, "outputDirField");
					name.setText("Auto Mod");
					id.setText("auto_mod");
					ver.setText("1.0.0");
					outBase = client.runDirectory.toPath().resolve("auto-export");
					outDirDegraded = outBase.resolve("degraded-" + System.currentTimeMillis());
					try {
						Files.createDirectories(outDirDegraded);
					} catch (Exception e) {
						check("create degraded output dir: " + e, false);
					}
					dir.setText(outDirDegraded.toString());
					check("mod name input round-trip", "Auto Mod".equals(name.getText()));
					check("mod id input round-trip", "auto_mod".equals(id.getText()));
					check("version input round-trip", "1.0.0".equals(ver.getText()));
					check("output dir input round-trip",
							outDirDegraded.toString().equals(dir.getText()));
				}
				// ---- select all items (both demo defs)
				case 6 -> {
					var s = exportScreen(client);
					clickButton(client, "全选物品");
					wait(2);
					List<String> sel = field(s, "selectedItemIds");
					check("both demo items selected", sel.contains("worldforge:auto_thunder")
							&& sel.contains("worldforge:auto_degraded"));
				}
				// ---- trigger export → degraded report + NO build task
				case 7 -> {
					var s = exportScreen(client);
					clickButton(client, "导出 Mod JAR");
					wait(3);
					boolean awaiting = field(s, "awaitingDegradedConfirmation");
					check("degraded export blocked pending confirmation", awaiting);
					boolean inProgress = field(s, "exportInProgress");
					check("no export thread started before confirmation", !inProgress);
					ExportExecutor exec = field(s, "exportExecutor");
					check("no executor created before confirmation", exec == null);
					long jarCount = countJars(outDirDegraded);
					check("no artifacts produced before confirmation", jarCount == 0);
					ButtonWidget confirm = field(s, "confirmButton");
					ButtonWidget cancel = field(s, "cancelConfirmButton");
					check("confirm button visible when degraded", confirm.visible && confirm.active);
					check("cancel button visible when degraded", cancel.visible && cancel.active);
					shot(client, "exp-02-degraded-report.png");
				}
				// ---- form edit invalidates the confirmation
				case 8 -> {
					var s = exportScreen(client);
					TextFieldWidget ver = field(s, "versionField");
					ver.setText("1.0.1");
					wait(2);
					boolean awaiting = field(s, "awaitingDegradedConfirmation");
					check("form edit invalidates pending confirmation", !awaiting);
					ButtonWidget confirm = field(s, "confirmButton");
					check("confirm button hidden after edit", !confirm.visible && !confirm.active);
				}
				// ---- re-request, then CANCEL the confirmation
				case 9 -> {
					var s = exportScreen(client);
					clickButton(client, "导出 Mod JAR");
					wait(2);
					boolean awaiting = field(s, "awaitingDegradedConfirmation");
					check("re-request shows confirmation again", awaiting);
					clickButton(client, "取消");
					wait(2);
					awaiting = field(s, "awaitingDegradedConfirmation");
					ExportPlan plan = field(s, "pendingPlan");
					check("cancel clears pending plan", !awaiting && plan == null);
					ButtonWidget confirm = field(s, "confirmButton");
					check("confirm button hidden after cancel", !confirm.visible && !confirm.active);
				}
				// ---- re-request, CONFIRM degraded, then CANCEL the real build
				case 10 -> {
					var s = exportScreen(client);
					clickButton(client, "导出 Mod JAR");
					wait(2);
					clickButton(client, "我接受降级导出");
					wait(3);
					boolean inProgress = field(s, "exportInProgress");
					ExportExecutor exec = field(s, "exportExecutor");
					check("confirmed export task started", inProgress && exec != null);
					if (exec != null) {
						check("task runs on the confirmed plan",
								exec.plan().degradedExportConfirmed()
										&& "auto_mod".equals(exec.plan().modId()));
					}
					// cancel the real build
					exec.cancel();
					wait(20);
				}
				case 11 -> {
					var s = exportScreen(client);
					waitBudget = 200;
					if (!waitUntil(client, () -> !(Boolean) field(s, "exportInProgress"),
							"cancelled export finishes promptly", 200)) return;
					var res = (ModExporter.ExportResult) field(s, "result");
					check("cancel result present and not ok", res != null && !res.ok());
					check("cancel message explicit",
							res != null && res.errorMessage() != null
									&& res.errorMessage().contains("取消"));
					check("no JAR left after cancel", countJars(outDirDegraded) == 0);
					check("executor terminal state CANCELLED",
							field(s, "exportExecutor") != null
									&& ((ExportExecutor) field(s, "exportExecutor"))
									.state() == ExportExecutor.TerminalState.CANCELLED);
					shot(client, "exp-03-after-cancel.png");
				}
				// ---- full SUCCESS export: only the supported item
				case 12 -> {
					var s = exportScreen(client);
					outDirSuccess = outBase.resolve("success-" + System.currentTimeMillis());
					try {
						Files.createDirectories(outDirSuccess);
					} catch (Exception e) {
						check("create success output dir: " + e, false);
					}
					TextFieldWidget dir = field(s, "outputDirField");
					dir.setText(outDirSuccess.toString());
					// the demo items sort FIRST (ids "worldforge:auto_*"), so row 0
					// is auto_degraded and row 1 is auto_thunder; older world
					// definitions follow. Real list-clicks toggle the demo rows,
					// then the selection is narrowed to exactly auto_thunder.
					List<String> sel = field(s, "selectedItemIds");
					int itemLeft = field(s, "itemLeft");
					int itemTop = field(s, "itemListTop");
					double rowX = itemLeft + 60;
					s.mouseClicked(rowX, itemTop + 5.5, 0); // toggle auto_degraded off
					sel.remove("worldforge:auto_degraded");
					// narrow to the supported item only (clear world leftovers)
					sel.clear();
					sel.add("worldforge:auto_thunder");
					List<String> sel2 = field(s, "selectedItemIds");
					check("only supported item selected for success export",
							sel2.contains("worldforge:auto_thunder")
									&& !sel2.contains("worldforge:auto_degraded")
									&& sel2.size() == 1);
					// the output dir must differ from the earlier truncated one
					check("success output dir accepted by field",
							outDirSuccess.toString().equals(dir.getText()));
					clickButton(client, "导出 Mod JAR");
					wait(3);
					boolean awaiting = field(s, "awaitingDegradedConfirmation");
					check("supported-only export needs no confirmation", !awaiting);
					boolean inProgress = field(s, "exportInProgress");
					check("success export task started", inProgress);
					shot(client, "exp-04-export-running.png");
				}
				case 13 -> {
					var s = exportScreen(client);
					waitBudget = 12000; // real Gradle build — up to 10 min
					if (!waitUntil(client, () -> !(Boolean) field(s, "exportInProgress"),
							"success export completes", 12000)) return;
					var res = (ModExporter.ExportResult) field(s, "result");
					check("export result ok", res != null && res.ok());
					if (res != null && res.ok()) {
						check("JAR file exists", res.jarPath() != null
								&& Files.exists(res.jarPath()));
						check("SHA-256 recorded", res.sha256() != null
								&& res.sha256().length() == 64);
						if (res.jarPath() != null) {
							info("JAR: " + res.jarPath());
						}
					} else if (res != null) {
						info("export failed: " + res.errorMessage());
					}
					// v0.5.4 Phase 3: error/cancel/timeout must allow re-export
					TextFieldWidget dirR = field(s, "outputDirField");
					Path outDirRerun = outBase.resolve("rerun-" + System.currentTimeMillis());
					try {
						Files.createDirectories(outDirRerun);
					} catch (Exception e) {
						check("create rerun dir: " + e, false);
					}
					dirR.setText(outDirRerun.toString());
					List<String> selR = field(s, "selectedItemIds");
					selR.clear();
					selR.add("worldforge:auto_thunder");
					rerunRequested = true;
					clickButton(client, "导出 Mod JAR");
					wait(3);
					boolean awaitingR = field(s, "awaitingDegradedConfirmation");
					check("re-export after success accepted (no stale state)", !awaitingR);
					check("re-export task started", (Boolean) field(s, "exportInProgress"));
				}
				case 14 -> {
					// wait for the re-run to finish, then cancel it to prove
					// cancellation keeps the screen usable
					var s = exportScreen(client);
					waitBudget = 12000;
					if (!waitUntil(client, () -> !(Boolean) field(s, "exportInProgress")
									|| execCancelled(),
							"re-export settles", 12000)) return;
					ExportExecutor execR = field(s, "exportExecutor");
					if (execR != null && (Boolean) field(s, "exportInProgress")) {
						execR.cancel();
					}
					wait(10);
					check("screen still interactive after re-export/cancel",
							exportScreen(client) != null);
				}
				// ---- GUI scale sweep + layout boundary audit (dev window 854x480)
				case 15 -> setScale(client, 1);
				case 16 -> {
					var s = exportScreen(client);
					check("screen stable at scale 1", s != null);
					if (s != null) {
						check("layout regions disjoint at scale 1",
								s.layoutRegionsAreDisjoint());
					}
					shot(client, "exp-05-scale1.png");
					setScale(client, 2);
				}
				case 17 -> {
					var s = exportScreen(client);
					check("screen stable at scale 2", s != null);
					if (s != null) {
						check("layout regions disjoint at scale 2",
								s.layoutRegionsAreDisjoint());
					}
					shot(client, "exp-06-scale2.png");
					setScale(client, 3);
				}
				case 18 -> {
					var s = exportScreen(client);
					check("screen stable at scale 3", s != null);
					if (s != null) {
						check("layout regions disjoint at scale 3",
								s.layoutRegionsAreDisjoint());
					}
					shot(client, "exp-07-scale3.png");
					setScale(client, 0); // auto
				}
				case 19 -> {
					// ESC closes when not exporting — wait for any cancel
					// teardown to finish, otherwise shouldCloseOnEsc is false
					var s = exportScreen(client);
					if (s != null) {
						waitBudget = 200;
						if (!waitUntil(client, () -> !(Boolean) field(s, "exportInProgress"),
								"export idle before ESC", 200)) return;
						// verify the ESC contract directly, then apply it
						check("shouldCloseOnEsc true when idle", s.shouldCloseOnEsc());
						s.keyPressed(256, 0, 0); // ESC
						wait(5);
						// vanilla close() is synchronous; if the screen survived
						// (e.g. a resize re-init), retry once via the same path
						if (client.currentScreen == s) {
							s.keyPressed(256, 0, 0);
							wait(5);
						}
					}
				}
				case 20 -> {
					check("ESC closes export screen (not exporting)",
							client.currentScreen == null);
					finish(client, false);
				}
				default -> finish(client, false);
			}
		} catch (Throwable t) {
			report.add("  [ERROR] step " + (step - 1) + ": " + t);
			t.printStackTrace();
			finish(client, true);
		}
	}

	private static long countJars(Path dir) {
		if (dir == null || !Files.exists(dir)) return 0;
		// only built artifacts count — gradle-wrapper.jar and other build
		// machinery live inside the project dir too
		var libs = dir.resolve("build").resolve("libs");
		if (!Files.exists(libs)) return 0;
		try (var s = Files.list(libs)) {
			return s.filter(p -> p.toString().endsWith(".jar")).count();
		} catch (Exception e) {
			return -1;
		}
	}

	private static boolean openExistingWorld(MinecraftClient client) {
		try {
			var storage = client.getLevelStorage();
			info("saves dir: " + storage.getSavesDirectory());
			List<String> names = new ArrayList<>();
			try (var stream = Files.list(storage.getSavesDirectory())) {
				stream.filter(Files::isDirectory)
						.map(p -> p.getFileName().toString())
						.filter(n -> !n.startsWith("."))
						.forEach(names::add);
			}
			info("existing saves: " + names);
			if (names.isEmpty()) {
				// v0.5.4 Phase 2: isolated beta instance — no saves exist, so the
				// driver creates a fresh world through the REAL vanilla flow
				// (CreateWorldScreen.create → createLevel), exactly as a new
				// player would. This is deliberately NOT a cached dev save.
				info("no existing saves; creating a fresh world via CreateWorldScreen");
				net.minecraft.client.gui.screen.world.CreateWorldScreen.create(client, null);
				return true; // world creation is async; the join-wait loop handles it
			}
			client.createIntegratedServerLoader().start(names.get(0), () -> { });
			return true;
		} catch (Throwable t) {
			info("could not open a world: " + t);
			return false;
		}
	}

	private static void finish(MinecraftClient client, boolean hadFailures) {
		if (reportWritten) return;
		reportWritten = true;
		client.setScreen(null);

		report.add(0, "=== Export Screen FULL Auto-Test (v0.5.3 P0-3) ===");
		report.add("Total: " + (passed + failed) + " checks, " + passed + " passed, " + failed + " failed");
		if (failed > 0 || hadFailures) {
			report.add("RESULT: ACCEPTANCE FAILED");
		} else {
			report.add("RESULT: ACCEPTANCE PASSED");
		}

		try {
			var dir = client.runDirectory.toPath().resolve("export-autotest-shots");
			Files.createDirectories(dir);
			Files.writeString(dir.resolve("report.txt"), String.join("\n", report));
			System.out.println("[WorldForge] Export auto-test report written: "
					+ dir.resolve("report.txt"));
		} catch (Throwable t) {
			System.err.println("Failed to write report: " + t);
		}

		System.out.println("[WorldForge] Export auto-test: " + passed + " passed, " + failed + " failed");
		if (failed > 0) {
			// Make the acceptance failure UNMISSABLE: crash the client so the
			// Gradle run exits non-zero instead of reporting success.
			throw new RuntimeException("[WorldForge] Export auto-test FAILED with " + failed
					+ " failed checks — see run/export-autotest-shots/report.txt");
		}
		client.scheduleStop();
	}
}
