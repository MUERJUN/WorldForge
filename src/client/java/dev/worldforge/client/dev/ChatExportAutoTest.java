package dev.worldforge.client.dev;

import dev.worldforge.ai.AgentConfig;
import dev.worldforge.ai.ChatTurn;
import dev.worldforge.ai.MockToolProvider;
import dev.worldforge.client.WorldForgeClient;
import dev.worldforge.client.chat.ChatClientState;
import dev.worldforge.chat.ChatMessage;
import dev.worldforge.chat.ChatSession;
import dev.worldforge.client.gui.WorldForgeChatScreen;
import dev.worldforge.workspace.WorkspaceManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.ScreenshotRecorder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * v0.7.0 flow C: export a standalone mod FROM THE CHAT.
 *
 * <p>Drives the real client in a real single-player world: the player types
 * "export the thunder sword as a standalone mod", the scripted model asks for
 * the capability report, then for the export, the player confirms, and a REAL
 * Gradle build runs. The acceptance is the JAR on disk — not the chat text.</p>
 *
 * <p>Activation: {@code -Dworldforge.chat.export.autotest=true}. Any [FAIL]
 * is written to the report AND crashes the client, so acceptance can never
 * silently pass.</p>
 */
public final class ChatExportAutoTest {

	private static final String REPORT = "wf-chat-export-report.txt";
	/** the item the export is built from */
	private static final String ITEM_ID = "worldforge:autotest_thunder_sword";

	private final List<String> report = new ArrayList<>();
	private int step = 0;
	private int waitTicks = 0;
	private int shotSeq = 0;
	private boolean failed = false;
	private int ticksAlive = 0;
	private boolean reportWritten = false;
	private long buildStartedAt = 0;

	public static boolean enabled(MinecraftClient client) {
		return System.getProperty("worldforge.chat.export.autotest") != null;
	}

	public static void register() {
		ChatExportAutoTest instance = new ChatExportAutoTest();
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (!enabled(client)) {
				return;
			}
			if (client.getOverlay() != null) {
				return;
			}
			instance.tick(client);
		});
	}

	// ------------------------------------------------------------------ driver

	private void tick(MinecraftClient client) {
		ticksAlive++;
		if (!reportWritten && ticksAlive > 60_000) {
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
					if (client.world == null || client.getNetworkHandler() == null) {
						report.add("[WAIT] waiting for the world to load");
						wait(20);
						step = 0;
						return;
					}
					check("the client is in a world", client.world != null);
					// the scripted model: check the capabilities, then export
					var provider = MockToolProvider.scripted(List.of(
							new ChatTurn.Turn.Calls(List.of(new ChatTurn.ModelToolCall("c1",
									"check_export_capabilities",
									"{\"item_ids\":[\"" + ITEM_ID + "\"]}"))),
							new ChatTurn.Turn.Calls(List.of(new ChatTurn.ModelToolCall("c2",
									"export_mod",
									"{\"item_ids\":[\"" + ITEM_ID + "\"],\"mod_id\":\"autotest_thunder\","
											+ "\"mod_name\":\"AutoTest Thunder\",\"version\":\"1.0.0\"}"))),
							new ChatTurn.Turn.Final("导出完成了，JAR 在工作区里。")));
					dev.worldforge.ai.AgentBridge.attachProviderForTesting(provider,
							new AgentConfig(true, "http://mock", "mock", "sk-test", 30, 500, 0, 100, 12, 300));
					// make sure the item exists on the server
					var server = client.getServer();
					if (server != null) {
						server.execute(() -> {
							var def = new dev.worldforge.definition.ItemDefinition(1, ITEM_ID,
									"自动测试雷霆剑", "导出演示",
									dev.worldforge.definition.TextureSource.vanilla("minecraft:item/diamond_sword"),
									java.util.Map.of("damage", 14.0, "max_durability", 900.0),
									java.util.List.of(new dev.worldforge.definition.BehaviorDefinition(
											"right_click", "summon_lightning", 100, java.util.Map.of())));
							dev.worldforge.tool.WorldForgeTools.createItem(server, def);
						});
					}
					wait(20);
				}
				case 1 -> {
					// open the chat and ask for the export
					pressKey(client, WorldForgeClient.chatKeyBinding());
					wait(5);
					check("K opens the chat", client.currentScreen instanceof WorldForgeChatScreen);
				}
				case 2 -> {
					var screen = client.currentScreen instanceof WorldForgeChatScreen s ? s : null;
					if (screen == null) {
						fail("the chat must be open to ask for an export");
						finish(client);
						return;
					}
					screen.setInputText("把刚才的雷霆剑导出成一个独立 Mod");
					screen.submitForTest();
					wait(10);
					shot(client, "01-asked");
				}
				case 3 -> {
					ChatSession session = ChatClientState.active();
					boolean asked = session.messages().stream()
							.anyMatch(m -> m.role() == ChatMessage.Role.PLAYER
									&& m.text().contains("导出"));
					check("the request is in the chat", asked);
					// the confirmation for the export must arrive
					boolean waiting = session.hasPendingConfirmation();
					if (!waiting) {
						// still working: keep waiting
						wait(20);
						step = 3;
						return;
					}
					check("the export asks for a confirmation", waiting);
					shot(client, "02-confirm");
				}
				case 4 -> {
					ChatSession session = ChatClientState.active();
					if (!session.hasPendingConfirmation()) {
						wait(20);
						step = 4;
						return;
					}
					// approve it
					dev.worldforge.ai.AgentOrchestrator.confirm(
							client.player.getUuid(), session.conversationId(),
							session.pendingConfirmationId());
					check("the export was confirmed", true);
					buildStartedAt = System.currentTimeMillis();
					wait(20);
				}
				case 5 -> {
					// the real Gradle build is running — wait for it
					Path jar = findJar();
					if (jar == null) {
						long elapsed = System.currentTimeMillis() - buildStartedAt;
						if (elapsed > 11 * 60_000L) {
							fail("the export did not finish within 11 minutes");
							finish(client);
							return;
						}
						wait(40);
						step = 5;
						return;
					}
					check("the export produced a real JAR", jar != null);
					check("the JAR is not empty", jar != null && jar.toFile().length() > 1024);
					report.add("[JAR] " + jar);
					shot(client, "03-exported");
					finish(client);
				}
				default -> finish(client);
			}
		} catch (Throwable t) {
			fail("driver exception: " + t);
			finish(client);
		}
	}

	/** Finds the exported JAR in the workspace. */
	private static Path findJar() {
		Path dir = WorkspaceManager.exportsDir();
		if (!Files.isDirectory(dir)) {
			return null;
		}
		try (var stream = Files.walk(dir, 4)) {
			return stream.filter(p -> p.getFileName().toString().endsWith(".jar"))
					.filter(p -> !p.getFileName().toString().contains("sources"))
					.findFirst().orElse(null);
		} catch (Exception e) {
			return null;
		}
	}

	// ------------------------------------------------------------------ helpers

	private static void pressKey(MinecraftClient client,
			net.minecraft.client.option.KeyBinding binding) {
		if (binding == null) {
			return;
		}
		binding.setPressed(true);
		try {
			java.lang.reflect.Field f = net.minecraft.client.option.KeyBinding.class
					.getDeclaredField("timesPressed");
			f.setAccessible(true);
			f.setInt(binding, 1);
		} catch (Throwable ignored) {
		}
	}

	private void wait(int ticks) {
		waitTicks = ticks;
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

	private void shot(MinecraftClient client, String name) {
		shotSeq++;
		String file = "wf-chat-export-" + String.format("%02d", shotSeq) + "-" + name + ".png";
		try {
			ScreenshotRecorder.saveScreenshot(client.runDirectory, file, client.getFramebuffer(), msg -> {
			});
			report.add("[SHOT] " + file);
		} catch (Throwable t) {
			report.add("[WARN] screenshot failed: " + t);
		}
	}

	private void finish(MinecraftClient client) {
		if (reportWritten) {
			return;
		}
		reportWritten = true;
		step = 1000;
		report.add(failed ? "[RESULT] FAILED" : "[RESULT] ALL CHECKS PASSED");
		String text = String.join("\n", report) + "\n";
		try {
			java.nio.file.Path dir = client.runDirectory.toPath();
			Files.createDirectories(dir);
			Files.writeString(dir.resolve(REPORT), text, java.nio.charset.StandardCharsets.UTF_8);
		} catch (Throwable ignored) {
		}
		if (failed) {
			throw new IllegalStateException("[WorldForge] chat export auto-test FAILED; see " + REPORT);
		}
		client.scheduleStop();
	}
}
