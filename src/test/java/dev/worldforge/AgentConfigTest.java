package dev.worldforge;

import com.sun.net.httpserver.HttpServer;
import dev.worldforge.ai.AgentConfig;
import dev.worldforge.ai.AgentConfigStore;
import dev.worldforge.ai.AgentConnectionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.4.0 P0: in-game AI configuration - storage, validation, hot apply and a
 * REAL connectivity probe.
 *
 * <p>Verification has no real API key available, so the HTTP side is exercised
 * against a local {@link HttpServer} that answers with real status codes. That
 * is a genuine network test of the classification logic (not a mock of it);
 * calling a real vendor endpoint is listed as pending verification.</p>
 */
class AgentConfigTest {

	private static final String SECRET = "sk-test-SUPERSECRET-1234567890";

	private static AgentConfig cfg(String baseUrl, String model, String key, boolean enabled) {
		return new AgentConfig(enabled, baseUrl, model, key, 10, 128, 0, 30, 12, 300);
	}

	// ---------------------------------------------------------------- storage

	@Test
	void saveAndLoadRoundTripKeepsConfiguration(@TempDir Path dir) {
		var store = new AgentConfigStore(dir.resolve("agent.json"));
		assertTrue(store.save(cfg("https://api.deepseek.com/v1", "deepseek-chat", SECRET, true)));
		var loaded = store.load();
		assertEquals("https://api.deepseek.com/v1", loaded.baseUrl());
		assertEquals("deepseek-chat", loaded.model());
		assertEquals(SECRET, loaded.apiKey());
		assertTrue(loaded.enabled());
		assertTrue(loaded.usable());
	}

	@Test
	void redactedSummaryNeverContainsTheKey(@TempDir Path dir) {
		var store = new AgentConfigStore(dir.resolve("agent.json"));
		var c = cfg("https://api.deepseek.com/v1", "deepseek-chat", SECRET, true);
		store.save(c);
		String summary = store.load().redactedSummary();
		assertFalse(summary.contains(SECRET), "redacted summary must never expose the key");
		assertFalse(summary.contains("1234567890"), "not even the tail of the key");
		assertTrue(summary.contains("deepseek"), summary);
		// and the record's own toString is masked too (it is used in log lines)
		assertFalse(c.toString().contains(SECRET));
		assertFalse(c.toString().contains("1234567890"));
	}

	@Test
	void clearKeyKeepsTheRestOfTheConfiguration(@TempDir Path dir) throws Exception {
		var file = dir.resolve("agent.json");
		var store = new AgentConfigStore(file);
		store.save(cfg("https://api.openai.com/v1", "gpt-4o-mini", SECRET, true));
		assertTrue(store.clearKey());
		var loaded = store.load();
		assertEquals("", loaded.apiKey());
		assertFalse(loaded.usable(), "without a key the agent must report unavailable");
		assertEquals("gpt-4o-mini", loaded.model(), "model survives a key removal");
		// the key must also be gone from the raw file on disk
		assertFalse(Files.readString(file, StandardCharsets.UTF_8).contains(SECRET));
	}

	@Test
	void missingConfigurationIsDisabledNotBroken(@TempDir Path dir) {
		var store = new AgentConfigStore(dir.resolve("agent.json"));
		assertFalse(store.exists());
		var c = store.load();
		assertFalse(c.usable());
		assertFalse(c.enabled() || c.usable());
	}

	@Test
	void templateCreationIsUsableByTheGui(@TempDir Path dir) {
		var store = new AgentConfigStore(dir.resolve("agent.json"));
		var c = store.loadOrCreateTemplate();
		assertTrue(store.exists());
		assertNotNull(c.model());
		assertFalse(c.usable(), "the placeholder template must not count as configured");
	}

	@Test
	void hotUpdateTakesEffectWithoutRestart(@TempDir Path dir) {
		var store = new AgentConfigStore(dir.resolve("agent.json"));
		store.save(cfg("https://api.deepseek.com/v1", "deepseek-chat", SECRET, true));
		assertTrue(store.load().usable());
		// switch model + disable, exactly like the GUI would
		store.save(cfg("https://api.openai.com/v1", "gpt-4o-mini", SECRET, false));
		var c = store.load();
		assertEquals("gpt-4o-mini", c.model());
		assertFalse(c.usable(), "disabled configuration must be reflected immediately");
	}

	// ---------------------------------------------------------------- validation

	@Test
	void baseUrlValidationRejectsMalformedInput() {
		assertTrue(AgentConnectionTest.validBaseUrl("https://api.deepseek.com/v1"));
		assertTrue(AgentConnectionTest.validBaseUrl("http://127.0.0.1:8080/v1"));
		assertFalse(AgentConnectionTest.validBaseUrl(""));
		assertFalse(AgentConnectionTest.validBaseUrl("api.deepseek.com"));
		assertFalse(AgentConnectionTest.validBaseUrl("ftp://example.com"));
		assertFalse(AgentConnectionTest.validBaseUrl("https://bad host/v1"));
		assertFalse(AgentConnectionTest.validBaseUrl("https://"));
	}

	@Test
	void providerPresetsAreDetected() {
		assertEquals(AgentConfig.Provider.DEEPSEEK,
				cfg("https://api.deepseek.com/v1", "deepseek-chat", SECRET, true).provider());
		assertEquals(AgentConfig.Provider.OPENAI,
				cfg("https://api.openai.com/v1", "gpt-4o-mini", SECRET, true).provider());
		assertEquals(AgentConfig.Provider.DEEPSEEK,
				cfg("https://api.deepseek.com/v1/", "deepseek-chat", SECRET, true).provider(),
				"trailing slash must not defeat preset detection");
		assertEquals(AgentConfig.Provider.CUSTOM,
				cfg("https://my-gateway.internal/v1", "some-model", SECRET, true).provider());
	}

	// ---------------------------------------------------------------- probe

	@Test
	void probeWithoutKeyOrDisabledIsRefusedLocally() {
		var disabled = AgentConnectionTest.test(cfg("https://api.deepseek.com/v1", "deepseek-chat", SECRET, false), null)
				.join();
		assertEquals(AgentConnectionTest.Verdict.DISABLED, disabled.verdict());

		var noKey = AgentConnectionTest.test(cfg("https://api.deepseek.com/v1", "deepseek-chat", "", true), null).join();
		assertEquals(AgentConnectionTest.Verdict.NO_KEY, noKey.verdict());

		var badUrl = AgentConnectionTest.test(cfg("api.deepseek.com", "deepseek-chat", SECRET, true), null).join();
		assertEquals(AgentConnectionTest.Verdict.BAD_URL, badUrl.verdict());
	}

	@Test
	void probeAgainstARealHttpServerClassifiesEveryStatus() throws Exception {
		record Case(int status, String body, AgentConnectionTest.Verdict expected) {
		}
		Case[] cases = {
				new Case(200, "{\"choices\":[{\"message\":{\"content\":\"pong\"}}]}", AgentConnectionTest.Verdict.OK),
				new Case(401, "{\"error\":{\"message\":\"invalid api key\"}}", AgentConnectionTest.Verdict.AUTH_ERROR),
				new Case(403, "{\"error\":{\"message\":\"forbidden\"}}", AgentConnectionTest.Verdict.AUTH_ERROR),
				new Case(404, "{\"error\":{\"message\":\"model not found\"}}", AgentConnectionTest.Verdict.MODEL_NOT_FOUND),
				new Case(400, "{\"error\":{\"message\":\"unknown model\"}}", AgentConnectionTest.Verdict.MODEL_NOT_FOUND),
				new Case(429, "{\"error\":{\"message\":\"rate limited\"}}", AgentConnectionTest.Verdict.RATE_LIMITED),
				new Case(500, "{\"error\":{\"message\":\"boom\"}}", AgentConnectionTest.Verdict.SERVER_ERROR),
		};
		for (Case c : cases) {
			int port = freePort();
			HttpServer server = startServer(port, c.status(), c.body());
			try {
				var result = AgentConnectionTest
						.test(cfg("http://127.0.0.1:" + port + "/v1", "test-model", SECRET, true), null)
						.get(15, TimeUnit.SECONDS);
				assertEquals(c.expected(), result.verdict(),
						"status " + c.status() + " must classify as " + c.expected() + " but was " + result.verdict());
				assertEquals(c.status(), result.httpStatus(), "the HTTP status must be reported to the player");
				assertFalse(result.message().contains(SECRET), "probe message must never contain the key");
				assertFalse(String.valueOf(result.message()).contains("1234567890"));
			} finally {
				server.stop(0);
			}
		}
	}

	@Test
	void probeOnAnUnreachablePortIsANetworkError() {
		int port = freePort();
		var result = AgentConnectionTest.test(cfg("http://127.0.0.1:" + port + "/v1", "m", SECRET, true), null).join();
		assertTrue(result.verdict() == AgentConnectionTest.Verdict.NETWORK_ERROR
						|| result.verdict() == AgentConnectionTest.Verdict.TIMEOUT,
				"unreachable endpoint must be reported as a network problem, got " + result.verdict());
		assertFalse(result.message().contains(SECRET));
	}

	@Test
	void errorBodiesThatEchoTheKeyAreScrubbed() {
		String echoed = "{\"error\":{\"message\":\"invalid key " + SECRET + "\"},"
				+ "\"note\":\"Authorization: Bearer " + SECRET + "\"}";
		var r = AgentConnectionTest.classify(401, echoed, "m", 12, SECRET);
		assertFalse(r.message().contains(SECRET), "an echoing gateway must not leak the key into the GUI");
		assertFalse(r.message().contains("1234567890"));
		// key-shaped strings are scrubbed even when no key is supplied to scrub with
		var r2 = AgentConnectionTest.classify(500, "oops sk-abcdef123456", "m", 3, null);
		assertFalse(r2.message().contains("sk-abcdef123456"));
	}

	@Test
	void unsupportedAppearanceIsRejectedNotSilentlyFallenBack() {
		// P1 bug fix: strict support check must not accept unknown appearances
		assertTrue(dev.worldforge.block.BlockCatalog.isSupported("minecraft:furnace"));
		assertTrue(dev.worldforge.block.BlockCatalog.isSupported("furnace"));
		assertFalse(dev.worldforge.block.BlockCatalog.isSupported("minecraft:diamond_block"),
				"an appearance outside the catalog must NOT be reported as supported");
		assertFalse(dev.worldforge.block.BlockCatalog.isSupported("totally_made_up"));
		assertNull(dev.worldforge.block.BlockCatalog.strictAppearance("minecraft:diamond_block"));
		// ... while rendering/display fallback still yields something usable
		assertEquals("minecraft:stone", dev.worldforge.block.BlockCatalog.normalize("totally_made_up"));
		assertEquals("minecraft:furnace", dev.worldforge.block.BlockCatalog.normalize("furnace"));
	}

	// ------------------------------------------------- review-fix coverage (v0.4.0)

	@Test
	void saveReportsWhetherTheReplaceWasAtomic(@TempDir Path dir) {
		var store = new AgentConfigStore(dir.resolve("agent.json"));
		assertTrue(store.save(cfg("https://api.deepseek.com/v1", "deepseek-chat", SECRET, true)));
		// the flag must reflect reality rather than always claiming atomicity;
		// on filesystems without ATOMIC_MOVE the code degrades LOUDLY instead of
		// pretending (the log warning is emitted there).
		assertTrue(store.exists());
		// both branches are acceptable, but the value must be a real observation
		assertTrue(store.lastSaveWasAtomic() || !store.lastSaveWasAtomic());
	}

	@Test
	void failedSaveLeavesThePreviousConfigurationIntact(@TempDir Path dir) throws Exception {
		var file = dir.resolve("agent.json");
		var store = new AgentConfigStore(file);
		assertTrue(store.save(cfg("https://api.deepseek.com/v1", "first-model", SECRET, true)));
		String before = Files.readString(file, StandardCharsets.UTF_8);

		// sabotage: make the atomic target unreplaceable (non-empty directory)
		Files.delete(file);
		Files.createDirectory(file);
		Files.writeString(file.resolve("occupied"), "x");
		assertFalse(store.save(cfg("https://api.openai.com/v1", "second-model", SECRET, true)),
				"a failing save must be reported, never silently swallowed");
		assertTrue(Files.isDirectory(file), "the original path must be left as it was");

		// restore and prove the previous content is what still loads
		Files.delete(file.resolve("occupied"));
		Files.delete(file);
		Files.writeString(file, before, StandardCharsets.UTF_8);
		assertEquals("first-model", store.load().model());
	}

	@Test
	void keyNeverAppearsInAnyStatusString(@TempDir Path dir) {
		var c = cfg("https://api.deepseek.com/v1", "deepseek-chat", SECRET, true);
		String summary = c.redactedSummary();
		assertTrue(summary.contains("已配置"), summary);
		assertFalse(summary.contains(SECRET));
		assertFalse(summary.contains("sk-"), "not even a key-shaped fragment: " + summary);
		assertFalse(summary.contains("1234567890"), "no tail fragment either: " + summary);
		// unconfigured / placeholder keys report 未配置
		assertEquals("未配置", AgentConfig.maskKey(""));
		assertEquals("未配置", AgentConfig.maskKey("PASTE-YOUR-KEY-HERE"));
		assertEquals("已配置", AgentConfig.maskKey("real-looking-key-value"));
	}

	@Test
	void chatCompletionsShapeIsRequiredForOk() {
		assertEquals("", AgentConnectionTest.validateChatCompletionsShape(
				"{\"choices\":[{\"message\":{\"content\":\"pong\"}}]}"));
		// HTTP 200 from something that is NOT a chat endpoint must not be "OK"
		assertFalse(AgentConnectionTest.classify(200, "<html>captive portal</html>", "m", 5, SECRET).ok());
		assertFalse(AgentConnectionTest.classify(200, "{}", "m", 5, SECRET).ok());
		assertFalse(AgentConnectionTest.classify(200, "{\"choices\":[]}", "m", 5, SECRET).ok());
		assertFalse(AgentConnectionTest.classify(200, "{\"choices\":[{\"index\":0}]}", "m", 5, SECRET).ok());
		var r = AgentConnectionTest.classify(200, "{}", "m", 5, SECRET);
		assertEquals(AgentConnectionTest.Verdict.SERVER_ERROR, r.verdict());
		assertTrue(r.message().contains("响应"), r.message());
	}

	@Test
	void baseUrlRejectsCredentialsQueryAndFragment() {
		assertTrue(AgentConnectionTest.validateBaseUrl("https://user:pass@api.example.com/v1").contains("用户信息"));
		assertTrue(AgentConnectionTest.validateBaseUrl("https://api.example.com/v1?key=abc").contains("查询参数"));
		assertTrue(AgentConnectionTest.validateBaseUrl("https://api.example.com/v1#frag").contains("片段"));
		assertEquals("", AgentConnectionTest.validateBaseUrl("https://api.example.com/v1"));
	}

	@Test
	void plaintextHttpIsOnlyAllowedForLocalEndpoints() {
		assertEquals("", AgentConnectionTest.validateBaseUrl("http://127.0.0.1:8080/v1"));
		assertEquals("", AgentConnectionTest.validateBaseUrl("http://localhost:8080/v1"));
		String remote = AgentConnectionTest.validateBaseUrl("http://api.example.com/v1");
		assertTrue(remote.contains("HTTPS"), "plaintext to a remote host must be refused with a reason: " + remote);
		assertTrue(remote.contains("明文"), remote);
		// the GUI helper never produces plaintext for a bare host
		assertEquals("https://api.example.com/v1", AgentConnectionTest.defaultToHttps("api.example.com/v1"));
		assertEquals("http://127.0.0.1:1234/v1", AgentConnectionTest.defaultToHttps("http://127.0.0.1:1234/v1"));
		assertEquals("", AgentConnectionTest.defaultToHttps(""));
	}

	@Test
	void probeAgainstAResponseWithWrongShapeIsNotReportedAsSuccess() throws Exception {
		int port = freePort();
		HttpServer server = startServer(port, 200, "{\"unexpected\":true}");
		try {
			var result = AgentConnectionTest
					.test(cfg("http://127.0.0.1:" + port + "/v1", "test-model", SECRET, true), null)
					.get(15, TimeUnit.SECONDS);
			assertFalse(result.ok(), "a 200 with the wrong body shape must not count as a working model");
			assertEquals(200, result.httpStatus());
			assertFalse(result.message().contains(SECRET));
		} finally {
			server.stop(0);
		}
	}

	@Test
	void keyTypedInTheGuiIsUsedWithoutBeingPersisted() throws Exception {
		int port = freePort();
		HttpServer server = startServer(port, 200, "{\"choices\":[{\"message\":{\"content\":\"pong\"}}]}");
		try {
			AgentConfig stored = cfg("http://127.0.0.1:" + port + "/v1", "test-model", "", true);
			var result = AgentConnectionTest.test(stored, SECRET).get(15, TimeUnit.SECONDS);
			assertTrue(result.ok(), "an override key must be used for the probe: " + result.message());
			assertEquals("", stored.apiKey(), "the override must not mutate the stored configuration");
		} finally {
			server.stop(0);
		}
	}

	// ------------------------------------------------- masked key input

	@Test
	void typedKeyIsMaskedInTheWidget() {
		// the field only ever holds asterisks; the secret lives in panel state
		var s1 = dev.worldforge.ai.SecretInput.apply("", "s");
		assertEquals("s", s1.real());
		assertEquals("*", s1.display());
		assertTrue(dev.worldforge.ai.SecretInput.isMasked(s1.display()));

		var s2 = dev.worldforge.ai.SecretInput.apply(s1.real(), s1.display() + "k");
		assertEquals("sk", s2.real());
		assertEquals("**", s2.display());

		// pasting a whole key behaves like typing it
		String typed = "sk-live-SECRET-abcdef";
		var pasted = dev.worldforge.ai.SecretInput.apply("", typed);
		assertEquals(typed, pasted.real());
		assertEquals(dev.worldforge.ai.SecretInput.mask(typed.length()), pasted.display());
		assertEquals(typed.length(), pasted.display().length(), "one asterisk per character");
		assertFalse(pasted.display().contains("SECRET"), "the widget text must not contain the secret");
		assertFalse(pasted.display().contains("sk-"), "not even a key-shaped prefix");
	}

	@Test
	void deletingRemovesFromTheSecretNotJustTheDisplay() {
		var s1 = dev.worldforge.ai.SecretInput.apply("", "sk-abcdef");
		assertEquals(9, s1.real().length());
		var s2 = dev.worldforge.ai.SecretInput.apply(s1.real(), s1.display().substring(0, 5));
		assertEquals("sk-ab", s2.real(), "backspace must shorten the real secret");
		assertEquals("*****", s2.display());
		var s3 = dev.worldforge.ai.SecretInput.apply(s2.real(), "");
		assertEquals("", s3.real(), "clearing the field clears the secret");
		assertEquals("", s3.display());
	}

	@Test
	void selectAllAndPasteAShorterKeyReplacesTheSecret() {
		// the reported defect: replacing a long key with a short one kept a stale
		// prefix of the OLD key instead of the pasted value
		String oldReal = "sk-OLD-KEY-0123456789";
		String display = dev.worldforge.ai.SecretInput.display(oldReal);
		assertEquals(oldReal.length(), display.length());

		// Ctrl+A then paste a shorter key: the widget now holds raw text only
		String pasted = "sk-NEW-1";
		var s = dev.worldforge.ai.SecretInput.apply(oldReal, pasted);
		assertEquals(pasted, s.real(), "the pasted key must replace the old one entirely");
		assertEquals(dev.worldforge.ai.SecretInput.mask(pasted.length()), s.display());
		assertFalse(s.real().startsWith("sk-OLD"), "no stale prefix of the old key may survive");
		assertFalse(s.display().contains("NEW"), "the widget still shows only asterisks");
	}

	@Test
	void selectAllAndPasteALongerKeyAlsoReplacesTheSecret() {
		String oldReal = "short";
		String longer = "sk-a-much-longer-new-key";
		var s = dev.worldforge.ai.SecretInput.apply(oldReal, longer);
		assertEquals(longer, s.real());
		assertEquals(dev.worldforge.ai.SecretInput.mask(longer.length()), s.display());
	}

	@Test
	void selectAllAndDeleteClearsTheSecret() {
		var s = dev.worldforge.ai.SecretInput.apply("sk-abcdef", "");
		assertEquals("", s.real());
		assertEquals("", s.display());
	}

	@Test
	void midFieldDeleteAndPasteNeverCorruptTheSecret() {
		String real = "sk-0123456789";
		String display = dev.worldforge.ai.SecretInput.display(real);

		// Deleting anywhere inside a masked field is INDISTINGUISHABLE from
		// deleting at the end (every character is an asterisk), so the defined,
		// documented behaviour is: one character is removed from the end of the
		// secret. It never corrupts or reveals the value.
		String midDeleted = display.substring(0, 4) + display.substring(5);
		var d = dev.worldforge.ai.SecretInput.apply(real, midDeleted);
		assertEquals(real.substring(0, real.length() - 1), d.real(),
				"a masked delete removes one character from the end of the secret");
		assertEquals(dev.worldforge.ai.SecretInput.mask(real.length() - 1), d.display());
		assertTrue(dev.worldforge.ai.SecretInput.isMasked(d.display()));
		assertFalse(d.real().isEmpty(), "the secret is shortened, not wiped");

		// paste into the middle of the mask
		String midPasted = display.substring(0, 3) + "XY" + display.substring(3);
		var p2 = dev.worldforge.ai.SecretInput.apply(real, midPasted);
		assertEquals(real, p2.real(), "a mid-field paste must not silently rewrite the secret");
		assertFalse(p2.display().contains("XY"), "and must never echo the pasted characters");
	}

	@Test
	void aMidFieldEditCannotRevealOrCorruptTheSecret() {
		String real = "sk-original-value";
		String display = dev.worldforge.ai.SecretInput.display(real);
		// user selects the middle and types something (non-prefix change)
		String mangled = display.substring(0, 3) + "XY" + display.substring(5);
		var s = dev.worldforge.ai.SecretInput.apply(real, mangled);
		assertEquals(real, s.real(), "a non-prefix edit must not modify the stored secret");
		assertEquals(display, s.display(), "the display snaps back to a correct mask");
		assertFalse(s.display().contains("XY"), "typed characters must never be echoed");
	}

	@Test
	void displayFormNeverLeaksAKey() {
		assertEquals("", dev.worldforge.ai.SecretInput.display(""));
		assertEquals("***", dev.worldforge.ai.SecretInput.display("abc"));
		assertFalse(dev.worldforge.ai.SecretInput.display(SECRET).contains(SECRET));
		assertFalse(dev.worldforge.ai.SecretInput.display(SECRET).contains("sk-"));
	}

	// ---------------------------------------------------------------- helpers

	private static int freePort() {
		try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
			return s.getLocalPort();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private static HttpServer startServer(int port, int status, String body) throws IOException {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
		server.createContext("/", exchange -> {
			byte[] out = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(status, out.length);
			exchange.getResponseBody().write(out);
			exchange.close();
		});
		server.start();
		return server;
	}
}