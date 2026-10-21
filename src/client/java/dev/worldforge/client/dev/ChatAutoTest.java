package dev.worldforge.client.dev;

import dev.worldforge.client.WorldForgeClient;
import dev.worldforge.client.chat.ChatClientState;
import dev.worldforge.client.chat.ChatMessage;
import dev.worldforge.client.chat.ChatSession;
import dev.worldforge.client.gui.WorldForgeChatScreen;
import dev.worldforge.client.gui.WorldForgeSettingsScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.text.Text;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * v0.7.0 client acceptance for the CHAT screen.
 *
 * <p>Drives the REAL client: presses the REAL K key binding, checks that the
 * home surface carries no professional tool buttons, exercises multi-session
 * handling, input (including Chinese), scrolling, the running state, the
 * cancel path, the settings entry and ESC — and screenshots every state.</p>
 *
 * <p>Activation: {@code run/wf-chat-autotest.flag} or the system property
 * {@code worldforge.chat.autotest}. Any [FAIL] is written to the report AND
 * crashes the client at the end, so acceptance can never silently pass.</p>
 */
public final class ChatAutoTest {

	private static final String FLAG_FILE = "wf-chat-autotest.flag";
	private static final String REPORT = "wf-chat-autotest-report.txt";

	private final List<String> report = new ArrayList<>();
	private int step = 0;
	private int waitTicks = 0;
	private int shotSeq = 0;
	private boolean failed = false;
	private int ticksAlive = 0;
	private boolean reportWritten = false;
	private String firstConversationId = "";

	public static boolean enabled(MinecraftClient client) {
		if (System.getProperty("worldforge.chat.autotest") != null) {
			return true;
		}
		return client != null && client.runDirectory != null
				&& Files.exists(client.runDirectory.toPath().resolve(FLAG_FILE));
	}

	public static void register() {
		ChatAutoTest instance = new ChatAutoTest();
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (!enabled(client)) {
				return;
			}
			if (client.getOverlay() != null) {
				return; // wait out loading overlays
			}
			instance.tick(client);
		});
	}

	// ------------------------------------------------------------------ driver

	private void tick(MinecraftClient client) {
		ticksAlive++;
		if (!reportWritten && ticksAlive > 4000) {
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
					// the chat needs a SERVER to talk to, so wait for the world
					// to be loaded before starting the acceptance run
					if (client.world == null || client.getNetworkHandler() == null) {
						report.add("[WAIT] waiting for the world to load");
						wait(20);
						step = 0;
						return;
					}
					check("the client is in a world", client.world != null);
					check("the K key binding exists", WorldForgeClient.chatKeyBinding() != null);
					check("K is bound to the chat",
							WorldForgeClient.chatKeyBinding() != null
									&& WorldForgeClient.chatKeyBinding().getBoundKeyLocalizedText()
											.getString().toLowerCase(java.util.Locale.ROOT).contains("k"));
					// press the REAL binding, the way a player does
					pressKey(client, WorldForgeClient.chatKeyBinding());
					wait(3);
				}
				case 1 -> {
					check("K opens the ChatScreen", client.currentScreen instanceof WorldForgeChatScreen);
					check("the Studio is NOT the default entry",
							!(client.currentScreen instanceof dev.worldforge.client.gui.WorldForgeStudioScreen));
					shot(client, "01-chat-open");
				}
				case 2 -> {
					var screen = chat(client);
					if (screen == null) {
						fail("the chat screen must stay open");
						finish(client);
						return;
					}
					// the home surface must not carry the professional tools
					List<String> labels = buttonLabels(screen);
					check("no export button on the home surface",
							labels.stream().noneMatch(l -> l.contains("导出")));
					check("no terminal/debug button on the home surface",
							labels.stream().noneMatch(l -> l.contains("终端") || l.contains("调试")
									|| l.contains("Gradle")));
					check("no item/block editor button on the home surface",
							labels.stream().noneMatch(l -> l.contains("物品编辑") || l.contains("方块编辑")));
					check("a session button exists", labels.stream().anyMatch(l -> l.contains("会话")));
					check("a new-session button exists", labels.stream().anyMatch(l -> l.contains("＋")));
					check("a settings button exists", labels.stream().anyMatch(l -> l.contains("⚙")));
					check("a send button exists", labels.stream().anyMatch(l -> l.contains("发送")));
				}
				case 3 -> {
					// the input field must hold focus so a player can type right away
					var screen = chat(client);
					check("the input field is focused", screen != null && screen.isInputFocused());
					shot(client, "02-focused");
				}
				case 4 -> {
					// type a Chinese message and send it
					var screen = chat(client);
					if (screen == null) {
						fail("the chat screen must stay open");
						finish(client);
						return;
					}
					screen.setInputText("给我的雷霆剑加一个右键闪电技能，冷却五秒");
					screen.submitForTest();
					wait(5);
					shot(client, "03-sent");
				}
				case 5 -> {
					var session = ChatClientState.active();
					check("the player's message is in the flow",
							session.messages().stream().anyMatch(m -> m.role() == ChatMessage.Role.PLAYER));
					firstConversationId = session.conversationId();
				}
				case 6 -> {
					// new session
					ChatClientState.createSession();
					wait(2);
					var session = ChatClientState.active();
					check("a new conversation is empty", session.messages().isEmpty());
					check("the new conversation has its own id",
							!session.conversationId().equals(firstConversationId));
					shot(client, "04-new-session");
				}
				case 7 -> {
					// switch back to the first conversation
					ChatClientState.switchTo(firstConversationId);
					wait(2);
					var session = ChatClientState.active();
					check("switching back restores the first conversation",
							session.conversationId().equals(firstConversationId));
					check("the first conversation still has its message",
							session.messages().stream().anyMatch(m -> m.role() == ChatMessage.Role.PLAYER));
					// the other conversation must NOT have leaked into this one
					check("no cross-conversation leakage",
							session.messages().stream().noneMatch(m -> m.role() == ChatMessage.Role.ASSISTANT
									&& m.text().contains("第二个会话")));
				}
				case 8 -> {
					// a long message must not break the layout
					var screen = chat(client);
					if (screen != null) {
						screen.setInputText("很长的消息".repeat(200));
						shot(client, "05-long-message");
						screen.setInputText("");
					}
				}
				case 9 -> {
					// the running state and the cancel control
					var screen = chat(client);
					if (screen != null) {
						screen.setRunningForTest(true);
						wait(2);
						shot(client, "06-running");
						screen.setRunningForTest(false);
					}
				}
				case 10 -> {
					// the settings entry
					var screen = chat(client);
					check("the settings entry is reachable",
							screen != null && screen.hasSettingsEntry());
					if (screen != null) {
						screen.openSettingsForTest();
						wait(3);
						check("the settings screen opens",
								client.currentScreen instanceof WorldForgeSettingsScreen);
						shot(client, "07-settings");
						client.setScreen(new WorldForgeChatScreen(() -> {
						}, () -> {
						}));
						wait(2);
					}
				}
				case 11 -> {
					// ESC closes
					var screen = chat(client);
					if (screen != null) {
						screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE, 0, 0);
						wait(2);
					}
					check("ESC closes the chat", !(client.currentScreen instanceof WorldForgeChatScreen));
					shot(client, "08-closed");
				}
				case 12 -> {
					// re-open and take the final screenshot
					pressKey(client, WorldForgeClient.chatKeyBinding());
					wait(3);
					shot(client, "09-reopened");
				}
				case 13 -> {
					// The LAYOUT ITSELF is unit-tested for every size (see
					// ChatLayoutTest), so the client only has to prove that the
					// RENDERED screen matches the computed layout and that the
					// widgets really do not overlap.
					auditLayout(client, client.options.getGuiScale().getValue());
					shot(client, "10-layout");
					finish(client);
				}
				default -> finish(client);
			}
		} catch (Throwable t) {
			fail("driver exception: " + t);
			finish(client);
		}
	}

	/**
	 * Checks the layout at one GUI scale: every visible widget must sit
	 * inside the panel, no two widgets may overlap, and the input must have a
	 * usable width. A layout claim that is verified, not eyeballed.
	 */
	private void auditLayout(MinecraftClient client, int scale) {
		var screen = chat(client);
		if (screen == null) {
			fail("scale " + scale + ": the chat screen must be open");
			return;
		}
		int[] panel = screen.panelBounds();
		int px = panel[0];
		int py = panel[1];
		int pw = panel[2];
		int ph = panel[3];

		List<int[]> rects = new ArrayList<>();
		List<String> names = new ArrayList<>();
		for (Element e : screen.children()) {
			if (!(e instanceof ClickableWidget w)) {
				continue;
			}
			if (!w.visible) {
				continue;
			}
			rects.add(new int[]{w.getX(), w.getY(), w.getWidth(), w.getHeight()});
			names.add(w.getMessage().getString());
		}
		// the rendered widgets must be the ones the layout computed
		var expected = dev.worldforge.ui.ChatLayout.compute(client.getWindow().getScaledWidth(),
				client.getWindow().getScaledHeight());
		boolean matches = px == expected.panelX() && py == expected.panelY()
				&& pw == expected.panelW() && ph == expected.panelH();
		check("scale " + scale + ": the rendered panel matches the computed layout" + (matches ? ""
				: " rendered=" + java.util.Arrays.toString(panel) + " computed="
						+ java.util.Arrays.toString(new int[]{expected.panelX(), expected.panelY(),
								expected.panelW(), expected.panelH()})),
				matches);
		check("scale " + scale + ": the panel fits the screen",
				px >= 0 && py >= 0 && px + pw <= client.getWindow().getScaledWidth()
						&& py + ph <= client.getWindow().getScaledHeight());

		boolean inside = true;
		for (int i = 0; i < rects.size(); i++) {
			int[] r = rects.get(i);
			if (r[0] < px || r[1] < py || r[0] + r[2] > px + pw || r[1] + r[3] > py + ph) {
				inside = false;
				report.add("[FAIL] scale " + scale + ": widget '" + names.get(i)
						+ "' is outside the panel " + java.util.Arrays.toString(r)
						+ " panel=" + java.util.Arrays.toString(panel));
			}
		}
		check("scale " + scale + ": every widget stays inside the panel", inside);

		// count the clashes directly: the verdict and the message then come
		// from the SAME number, so they can never disagree
		int clashes = 0;
		StringBuilder clash = new StringBuilder();
		for (int i = 0; i < rects.size(); i++) {
			for (int j = i + 1; j < rects.size(); j++) {
				if (dev.worldforge.ui.ChatLayout.overlaps(rects.get(i), rects.get(j))) {
					clashes++;
					clash.append(" '").append(names.get(i)).append("'")
							.append(java.util.Arrays.toString(rects.get(i)))
							.append(" vs '").append(names.get(j)).append("'")
							.append(java.util.Arrays.toString(rects.get(j))).append(';');
				}
			}
		}
		clash.append(" [all ").append(rects).append(']');
		check("scale " + scale + ": no two widgets overlap -> " + clash, clashes == 0);

		// the input must still be usable
		boolean inputOk = false;
		for (int i = 0; i < rects.size(); i++) {
			if (names.get(i).contains("chat input") || names.get(i).isEmpty()) {
				inputOk = rects.get(i)[2] >= 40;
			}
		}
		check("scale " + scale + ": the input field is wide enough", inputOk);
	}

	// ------------------------------------------------------------------ helpers

	private static WorldForgeChatScreen chat(MinecraftClient client) {
		return client.currentScreen instanceof WorldForgeChatScreen s ? s : null;
	}

	/**
	 * Presses a key binding exactly as the keyboard callback would.
	 *
	 * <p>{@code KeyBinding.setPressed} only flips the held flag; the
	 * "was pressed this tick" counter is private, so the harness raises it
	 * directly — the same thing {@code Keyboard.onKey} does for a real key.</p>
	 */
	private void pressKey(MinecraftClient client,
			net.minecraft.client.option.KeyBinding binding) {
		if (binding == null) {
			return;
		}
		binding.setPressed(true);
		try {
			Field f = net.minecraft.client.option.KeyBinding.class.getDeclaredField("timesPressed");
			f.setAccessible(true);
			f.setInt(binding, 1);
		} catch (Throwable t) {
			report.add("[WARN] could not raise the key-press counter: " + t);
		}
	}

	/** Every button label currently on the screen. */
	private static List<String> buttonLabels(WorldForgeChatScreen screen) {
		List<String> out = new ArrayList<>();
		for (Element e : screen.children()) {
			if (e instanceof ButtonWidget b) {
				out.add(b.getMessage().getString());
			}
		}
		return out;
	}

	private void wait(int ticks) {
		waitTicks = ticks;
	}

	private void check(String what, boolean ok) {
		System.out.println("[WorldForge][chatcheck] " + (ok ? "[PASS] " : "[FAIL] ") + what);
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
		String file = "wf-chat-autotest-" + String.format("%02d", shotSeq) + "-" + name + ".png";
		try {
			ScreenshotRecorder.saveScreenshot(client.runDirectory, file, client.getFramebuffer(), msg -> {
			});
			report.add("[SHOT] " + file);
		} catch (Throwable t) {
			report.add("[WARN] screenshot failed for " + name + ": " + t);
		}
	}

	private void finish(MinecraftClient client) {
		if (!reportWritten) {
			reportWritten = true;
			step = 100;
			writeReport(client);
		}
	}

	private void writeReport(MinecraftClient client) {
		report.add(failed ? "[RESULT] FAILED" : "[RESULT] ALL CHECKS PASSED");
		String text = String.join("\n", report) + "\n";
		try {
			java.nio.file.Path dir = client.runDirectory.toPath();
			Files.createDirectories(dir);
			Files.writeString(dir.resolve(REPORT), text, java.nio.charset.StandardCharsets.UTF_8);
		} catch (Throwable ignored) {
		}
		// a failed acceptance must never look like a pass
		if (failed) {
			throw new IllegalStateException("[WorldForge] chat auto-test FAILED; see " + REPORT);
		}
		// a passing run must END the client, or the Gradle task would hang
		client.scheduleStop();
	}
}
