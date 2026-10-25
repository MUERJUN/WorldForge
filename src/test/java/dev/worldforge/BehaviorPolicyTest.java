package dev.worldforge;

import dev.worldforge.behavior.BehaviorPolicy;
import dev.worldforge.definition.BehaviorDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.7.3 P1: SELF permission vs actual effect permission.
 *
 * <p>The SELF permission on {@code graft_behavior} only answers "can the
 * player modify their own held stack?" It does NOT answer "can the player
 * cause a lightning strike or an explosion in this world?" — that is the
 * {@link BehaviorPolicy}'s job.</p>
 *
 * <p>These tests verify:</p>
 * <ul>
 *   <li>safe actions are allowed by default</li>
 *   <li>dangerous actions are blocked by default</li>
 *   <li>{@code apply_effect} is safe only when targeting self</li>
 *   <li>server config can override the defaults</li>
 *   <li>unknown actions are blocked by default</li>
 *   <li>the policy is checked at EXECUTION time (a policy change takes
 *       effect on the next fire)</li>
 * </ul>
 */
class BehaviorPolicyTest {

	private static BehaviorDefinition def(String action, Map<String, Object> params) {
		return new BehaviorDefinition("right_click", List.of(action), params, 20);
	}

	@AfterEach
	void reset() {
		BehaviorPolicy.set(null);
	}

	// ------------------------------------------------------------ defaults

	@Test
	void safeActionsAreAllowedByDefault() {
		var policy = BehaviorPolicy.DEFAULT;
		assertTrue(policy.canExecute("heal", def("heal", Map.of())),
				"heal must be allowed by default");
		assertTrue(policy.canExecute("experience", def("experience", Map.of())),
				"experience must be allowed by default");
		assertTrue(policy.canExecute("teleport", def("teleport", Map.of())),
				"teleport must be allowed by default");
	}

	@Test
	void dangerousActionsAreBlockedByDefault() {
		var policy = BehaviorPolicy.DEFAULT;
		assertFalse(policy.canExecute("summon_lightning", def("summon_lightning", Map.of())),
				"summon_lightning must be blocked by default");
		assertFalse(policy.canExecute("explosion", def("explosion", Map.of())),
				"explosion must be blocked by default");
		assertFalse(policy.canExecute("ignite", def("ignite", Map.of())),
				"ignite must be blocked by default");
	}

	@Test
	void unknownActionsAreBlockedByDefault() {
		var policy = BehaviorPolicy.DEFAULT;
		assertFalse(policy.canExecute("launch_missiles", def("launch_missiles", Map.of())),
				"unknown actions must be blocked by default");
	}

	// ------------------------------------------------------- apply_effect

	@Test
	void applyEffectOnSelfIsAllowedByDefault() {
		var policy = BehaviorPolicy.DEFAULT;
		assertTrue(policy.canExecute("apply_effect",
				def("apply_effect", Map.of("target", "self"))),
				"apply_effect on self must be allowed");
		assertTrue(policy.canExecute("apply_effect",
				def("apply_effect", Map.of())),
				"apply_effect with no target defaults to self and must be allowed");
	}

	@Test
	void applyEffectOnOtherIsBlockedByDefault() {
		var policy = BehaviorPolicy.DEFAULT;
		assertFalse(policy.canExecute("apply_effect",
				def("apply_effect", Map.of("target", "target"))),
				"apply_effect on other entities must be blocked by default");
	}

	// -------------------------------------------------------- server config

	@Test
	void serverConfigCanAllowDangerousActions() {
		var policy = BehaviorPolicy.of(
				Set.of("summon_lightning"), Set.of());
		assertTrue(policy.canExecute("summon_lightning",
				def("summon_lightning", Map.of())),
				"server config must be able to allow summon_lightning");
	}

	@Test
	void serverConfigCanRestrictSafeActions() {
		var policy = BehaviorPolicy.of(
				Set.of(), Set.of("teleport"));
		assertFalse(policy.canExecute("teleport", def("teleport", Map.of())),
				"server config must be able to restrict teleport");
	}

	@Test
	void serverRestrictedTakesPriorityOverDefaultAllowed() {
		// teleport is safe by default, but the server restricts it
		var policy = BehaviorPolicy.of(Set.of(), Set.of("teleport"));
		assertFalse(policy.canExecute("teleport", def("teleport", Map.of())),
				"server restriction must override the default allow");
	}

	@Test
	void serverAllowedTakesPriorityOverDefaultRestricted() {
		// summon_lightning is dangerous by default, but the server allows it
		var policy = BehaviorPolicy.of(Set.of("summon_lightning"), Set.of());
		assertTrue(policy.canExecute("summon_lightning",
				def("summon_lightning", Map.of())),
				"server allow must override the default restriction");
	}

	// ------------------------------------------------------- execution time

	@Test
	void policyChangeTakesEffectAtExecutionTime() {
		// 1. start with the default policy: explosion is blocked
		BehaviorPolicy.set(BehaviorPolicy.DEFAULT);
		assertFalse(BehaviorPolicy.get().canExecute("explosion",
				def("explosion", Map.of())),
				"explosion must be blocked under the default policy");

		// 2. the server admin allows explosion
		BehaviorPolicy.set(BehaviorPolicy.of(Set.of("explosion"), Set.of()));
		assertTrue(BehaviorPolicy.get().canExecute("explosion",
				def("explosion", Map.of())),
				"explosion must be allowed after the server policy change");

		// 3. the server admin restricts it again
		BehaviorPolicy.set(BehaviorPolicy.of(Set.of(), Set.of("explosion")));
		assertFalse(BehaviorPolicy.get().canExecute("explosion",
				def("explosion", Map.of())),
				"explosion must be blocked again after the policy change");
	}

	@Test
	void getReturnsCurrentPolicy() {
		BehaviorPolicy.set(BehaviorPolicy.DEFAULT);
		assertSame(BehaviorPolicy.DEFAULT, BehaviorPolicy.get());

		var custom = BehaviorPolicy.of(Set.of("explosion"), Set.of());
		BehaviorPolicy.set(custom);
		assertSame(custom, BehaviorPolicy.get());

		BehaviorPolicy.set(null);
		assertSame(BehaviorPolicy.DEFAULT, BehaviorPolicy.get(),
				"null must reset to the default policy");
	}

	// ------------------------------------------- v0.7.4 conflict & config

	@Test
	void anActionInBothListsIsDenied() {
		// restrictedActions takes priority over allowedActions: a mis-edited
		// config that lists the same action in both arrays must fail CLOSED
		var policy = BehaviorPolicy.of(Set.of("summon_lightning"), Set.of("summon_lightning"));
		assertFalse(policy.canExecute("summon_lightning",
				def("summon_lightning", Map.of())),
				"an action in BOTH lists must be denied (restricted wins)");
	}

	@Test
	void anAdminRestrictionBeatsADefaultSafeAction() {
		// teleport is safe by default, but an explicit restriction wins
		var policy = BehaviorPolicy.of(Set.of("teleport"), Set.of("teleport"));
		assertFalse(policy.canExecute("teleport", def("teleport", Map.of())),
				"an explicitly restricted safe action must be denied");
	}

	@Test
	void anExplicitAdminAllowBeatsTheDefaultRestriction() {
		// summon_lightning is dangerous by default; the ONLY way to enable it
		// is an explicit allow AND it must NOT also be restricted
		var policy = BehaviorPolicy.of(Set.of("summon_lightning"), Set.of());
		assertTrue(policy.canExecute("summon_lightning",
				def("summon_lightning", Map.of())),
				"an explicit admin allow (with no restriction) must permit the action");
	}

	// -------------------------------------------------- file load / reload

	@Test
	void invalidJsonKeepsTheLastValidPolicy(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
			throws Exception {
		// 1. a valid policy is loaded first
		BehaviorPolicy.set(BehaviorPolicy.of(Set.of("explosion"), Set.of("heal")));
		assertTrue(BehaviorPolicy.get().canExecute("explosion", def("explosion", Map.of())),
				"setup: the custom policy must be active");

		// 2. the operator breaks the config file
		var cfg = dir.resolve("config/worldforge/behavior-policy.json");
		java.nio.file.Files.createDirectories(cfg.getParent());
		java.nio.file.Files.writeString(cfg, "{ not valid json ]");

		// 3. loading the broken file must NOT relax the active policy
		BehaviorPolicy.load(dir);
		assertTrue(BehaviorPolicy.get().canExecute("explosion",
				def("explosion", Map.of())),
				"the last valid policy must stay in force after a broken config");
		assertFalse(BehaviorPolicy.get().canExecute("heal", def("heal", Map.of())),
				"the admin restriction from the last valid policy must survive");
	}

	@Test
	void wrongConfigStructureKeepsTheLastValidPolicy(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
			throws Exception {
		BehaviorPolicy.set(BehaviorPolicy.of(Set.of(), Set.of("teleport")));

		var cfg = dir.resolve("config/worldforge/behavior-policy.json");
		java.nio.file.Files.createDirectories(cfg.getParent());
		// allowedActions is a STRING, not an array — structurally invalid
		java.nio.file.Files.writeString(cfg,
				"{\"allowedActions\": \"summon_lightning\", \"restrictedActions\": []}");

		BehaviorPolicy.load(dir);
		assertFalse(BehaviorPolicy.get().canExecute("teleport", def("teleport", Map.of())),
				"a structurally broken config must not swap in a new policy");
		assertFalse(BehaviorPolicy.get().canExecute("summon_lightning",
				def("summon_lightning", Map.of())),
				"the broken allow list must never open a dangerous action");
	}

	@Test
	void firstStartupWithBrokenConfigUsesTheSafeDefault(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
			throws Exception {
		BehaviorPolicy.set(BehaviorPolicy.DEFAULT);

		var cfg = dir.resolve("config/worldforge/behavior-policy.json");
		java.nio.file.Files.createDirectories(cfg.getParent());
		java.nio.file.Files.writeString(cfg, "]]] not json");

		BehaviorPolicy.load(dir);
		assertSame(BehaviorPolicy.DEFAULT, BehaviorPolicy.get(),
				"first startup with a broken config must degrade to the safe default");
		assertFalse(BehaviorPolicy.get().canExecute("explosion", def("explosion", Map.of())),
				"the safe default must deny dangerous actions");
	}

	@Test
	void reloadSwapsInANewPolicyOnlyWhenTheFileIsValid(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
			throws Exception {
		var cfg = dir.resolve("config/worldforge/behavior-policy.json");
		java.nio.file.Files.createDirectories(cfg.getParent());
		java.nio.file.Files.writeString(cfg,
				"{\"allowedActions\": [\"summon_lightning\"], \"restrictedActions\": []}");

		BehaviorPolicy.set(BehaviorPolicy.DEFAULT);
		assertTrue(BehaviorPolicy.reload(dir), "a valid config must reload successfully");
		assertTrue(BehaviorPolicy.get().canExecute("summon_lightning",
				def("summon_lightning", Map.of())),
				"the reloaded policy must be active");

		// now break the file and reload: the valid policy must survive
		java.nio.file.Files.writeString(cfg, "{{{ broken");
		assertFalse(BehaviorPolicy.reload(dir), "a broken config must fail the reload");
		assertTrue(BehaviorPolicy.get().canExecute("summon_lightning",
				def("summon_lightning", Map.of())),
				"the last valid policy must stay active after a failed reload");
	}

	@Test
	void reloadWithMissingFileFallsBackToTheSafeDefault(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
		BehaviorPolicy.set(BehaviorPolicy.of(Set.of("explosion"), Set.of()));
		assertTrue(BehaviorPolicy.reload(dir),
				"a missing config file is an explicit operator choice -> default");
		assertSame(BehaviorPolicy.DEFAULT, BehaviorPolicy.get());
		assertFalse(BehaviorPolicy.get().canExecute("explosion", def("explosion", Map.of())),
				"the fallback must be the safe default, not the previous policy");
	}
}
