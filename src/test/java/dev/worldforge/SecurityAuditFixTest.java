package dev.worldforge;

import dev.worldforge.exporter.ModExporter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.5.5 P0 安全：修复后验证：8 项审查发现全部修复。
 *
 * <p>证明 ID 路径逃逸、Java 注入、未实现动作被错误标记为支持等问题
 * 在当前代码中真实存在。修复后本测试全部转为通过。</p>
 */
class SecurityAuditFixTest {

	// ---- #5: ID path escape -------------------------------------------

	@Test
	void nativeIdRejectsPathTraversalSegments() {
		// worldforge:../../evil passes the validator regex today; the exporter
		// then writes resources to assets/../../evil — escaping the dir.
		String id = "worldforge:../../evil";
		// ModExporter must reject this; today nativeItemId happily returns
		// "../../evil"
		var errors = ModExporter.validateIdSafety(java.util.List.of(id), java.util.List.of());
		assertFalse(errors.isEmpty(),
				"FIX NEEDED: path-traversal ID must be rejected, got errors=" + errors);
	}

	@Test
	void nativeIdRejectsDotSegment() {
		var errors = ModExporter.validateIdSafety(java.util.List.of("worldforge:.."), java.util.List.of());
		assertFalse(errors.isEmpty(), "FIX NEEDED: '..' segment must be rejected");
	}

	@Test
	void nativeIdRejectsLeadingSlash() {
		var errors = ModExporter.validateIdSafety(java.util.List.of("worldforge:/abs"), java.util.List.of());
		assertFalse(errors.isEmpty(), "FIX NEEDED: leading '/' must be rejected");
	}

	@Test
	void nativeIdAcceptsSafeIds() {
		var errors = ModExporter.validateIdSafety(
				java.util.List.of("worldforge:thunder_sword", "worldforge:magic-device.v2"),
				java.util.List.of("worldforge:magic_device"));
		assertTrue(errors.isEmpty(), "safe IDs must pass: " + errors);
	}

	// ---- #1: unimplemented actions marked supported -------------------

	@Test
	void experienceActionIsNotClaimedAsSupported() {
		// generator implements only 6 actions; 'experience' and 'give_item'
		// are advertised but silently skipped
		assertFalse(ModExporter.isSupportedItemActionPublic("experience"),
				"FIX NEEDED: 'experience' has no code generation — must be unsupported");
		assertFalse(ModExporter.isSupportedItemActionPublic("give_item"),
				"FIX NEEDED: 'give_item' has no code generation — must be unsupported");
		assertTrue(ModExporter.isSupportedItemActionPublic("summon_lightning"));
		assertTrue(ModExporter.isSupportedItemActionPublic("apply_effect"));
	}

	// ---- #2: hit_entity vs right_click triggers ------------------------

	@Test
	void hitEntityTriggerIsNotExportedAsRightClick() {
		// A behavior with trigger=hit_entity must NOT be exported as a
		// right-click use() implementation — today trigger is ignored entirely.
		assertFalse(ModExporter.isTriggerExportable("hit_entity"),
				"FIX NEEDED: hit_entity has no export implementation");
		assertTrue(ModExporter.isTriggerExportable("on_use"),
				"on_use (right-click) is the exported trigger");
		assertFalse(ModExporter.isTriggerExportable("some_random_trigger"),
				"unknown triggers must be unsupported");
	}

	// ---- #4: Java string injection via parameters ----------------------

	@Test
	void effectNameInjectionIsNeutralized() {
		// effect param flows into Identifier.of("minecraft:" + effectName)
		// raw today: 'speed"); player.kill(); //' would break out of the string
		String malicious = "speed\"); player.kill(); //";
		String safe = ModExporter.sanitizeEffectId(malicious);
		assertTrue(safe.matches("[a-z0-9_]+"),
				"FIX NEEDED: effect id must be sanitized to [a-z0-9_]+, got: " + safe);
	}

	@Test
	void numericParamsAreClamped() {
		// duration/amplifier are inlined raw today; absurd values must be
		// rejected/clamped before codegen
		assertThrows(IllegalArgumentException.class,
				() -> ModExporter.sanitizePositiveInt("duration", 999_999_999, 1, 7_200_000),
				"FIX NEEDED: out-of-range duration must be rejected");
		assertThrows(IllegalArgumentException.class,
				() -> ModExporter.sanitizePositiveInt("amplifier", -5, 0, 255),
				"FIX NEEDED: negative amplifier must be rejected");
		assertEquals(200, ModExporter.sanitizePositiveInt("duration", 200, 1, 7_200_000));
	}

	// ---- #7: executor enforces degraded confirmation --------------------

	@Test
	void executorRejectsUnconfirmedDegradedPlans() {
		// ExportExecutor.doExport() must refuse when report.lost() is non-empty
		// and degradedExportConfirmed is false — GUI-only enforcement is not
		// enough; today the executor only adds a warning and proceeds.
		var plan = new ModExporter.CapabilityReport(
				java.util.List.of(), java.util.List.of(), java.util.List.of("item x: unsupported_z"));
		assertFalse(plan.lost().isEmpty());
		// contract: ExportExecutor.checkDegradedGuard(plan) must return an error
		// string when unconfirmed
		assertNotNull(ModExporter.degradedGuardError(plan, false),
				"FIX NEEDED: unconfirmed degraded export must produce a guard error");
		assertNull(ModExporter.degradedGuardError(plan, true),
				"confirmed degraded export may proceed");
		assertNull(ModExporter.degradedGuardError(
				new ModExporter.CapabilityReport(java.util.List.of(), java.util.List.of(), java.util.List.of()),
				false), "no lost capabilities — no guard needed");
	}

	// ---- #8: cross-platform gradlew selection ---------------------------

	@Test
	void gradleCommandPrefersPlatformWrapper() {
		// On Linux/macOS gradlew.bat must NOT be picked just because it exists.
		// The selection is (os, files) — test the pure decision function.
		boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
		String cmd = ModExporter.gradleCommandFor(windows, true, true);
		assertEquals(windows ? "bat" : "sh", cmd,
				"FIX NEEDED: wrapper choice must follow the OS, not file existence order");
		assertEquals("sh", ModExporter.gradleCommandFor(false, true, true));
		assertEquals("bat", ModExporter.gradleCommandFor(true, true, true));
	}
}
