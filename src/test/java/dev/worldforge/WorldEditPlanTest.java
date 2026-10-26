package dev.worldforge;

import dev.worldforge.ops.worldedit.WorldEditEntry;
import dev.worldforge.ops.worldedit.WorldEditPlan;
import dev.worldforge.ops.worldedit.WorldEditValidator;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v0.8.0: the world edit plan contract — deep immutability, deterministic
 * identity, expiry, and the string-layer validation that runs BEFORE any
 * registry resolution (the plan layer never touches live block states).
 */
class WorldEditPlanTest {

	private static WorldEditEntry stone() {
		return WorldEditEntry.of("minecraft:stone");
	}

	private static WorldEditEntry glass() {
		return WorldEditEntry.of("minecraft:glass");
	}

	private static WorldEditPlan plan(UUID player) {
		BlockPos pos = new BlockPos(1, 2, 3);
		return WorldEditPlan.of("we-test", player, "minecraft:overworld",
				List.of(pos),
				Map.of(pos, stone()),
				Map.of(pos, glass()),
				Instant.now(), Instant.now().plusSeconds(600));
	}

	// ----------------------------------------------------- deep immutability

	@Test
	void thePlanIsDeeplyImmutable() {
		WorldEditPlan p = plan(UUID.randomUUID());
		assertThrows(UnsupportedOperationException.class,
				() -> p.positions().add(new BlockPos(9, 9, 9)));
		assertThrows(UnsupportedOperationException.class,
				() -> p.originalStates().put(new BlockPos(9, 9, 9), WorldEditEntry.of("minecraft:dirt")));
		assertThrows(UnsupportedOperationException.class,
				() -> p.newStates().put(new BlockPos(9, 9, 9), WorldEditEntry.of("minecraft:dirt")));
		assertThrows(UnsupportedOperationException.class,
				() -> p.originalStates().get(p.positions().get(0)).properties().put("x", "y"));
	}

	@Test
	void mutatingTheInputsAfterBuildingDoesNotChangeThePlan() {
		BlockPos pos = new BlockPos(1, 2, 3);
		List<BlockPos> positions = new java.util.ArrayList<>(List.of(pos));
		Map<BlockPos, WorldEditEntry> originals = new LinkedHashMap<>(Map.of(pos, stone()));
		WorldEditPlan p = WorldEditPlan.of("we-x", UUID.randomUUID(), "minecraft:overworld",
				positions, originals, Map.of(pos, glass()),
				Instant.now(), Instant.now().plusSeconds(600));
		positions.add(new BlockPos(4, 5, 6));
		originals.put(new BlockPos(4, 5, 6), WorldEditEntry.of("minecraft:dirt"));
		assertEquals(1, p.positions().size(), "the plan must snapshot its inputs");
	}

	@Test
	void anEntryIsDeeplyImmutable() {
		Map<String, String> props = new LinkedHashMap<>();
		props.put("facing", "north");
		WorldEditEntry e = WorldEditEntry.of("minecraft:oak_stairs", props);
		props.put("half", "top");
		assertTrue(e.properties().isEmpty() || !e.properties().containsKey("half"),
				"the entry must snapshot its property map");
		assertThrows(UnsupportedOperationException.class, () -> e.properties().put("x", "y"));
	}

	// -------------------------------------------------- deterministic identity

	@Test
	void identicalContentSharesADeterministicFingerprint() {
		// same content, different players: the fingerprint binds the CONTENT
		assertTrue(!plan(UUID.randomUUID()).fingerprint().isBlank());
		String a = plan(UUID.randomUUID()).fingerprint();
		String b = plan(UUID.randomUUID()).fingerprint();
		assertEquals(a, b, "identical content must fingerprint identically");
	}

	@Test
	void aChangedTargetChangesTheFingerprint() {
		WorldEditPlan a = plan(UUID.randomUUID());
		BlockPos pos = a.positions().get(0);
		WorldEditPlan c = WorldEditPlan.of(a.planId(), a.playerUuid(), a.worldId(),
				a.positions(), a.originalStates(),
				Map.of(pos, WorldEditEntry.of("minecraft:diamond_block")),
				a.createdAt(), a.expiresAt());
		assertNotEquals(a.fingerprint(), c.fingerprint());
	}

	@Test
	void aChangedOriginalStateAlsoChangesTheFingerprint() {
		WorldEditPlan a = plan(UUID.randomUUID());
		BlockPos pos = a.positions().get(0);
		WorldEditPlan c = WorldEditPlan.of(a.planId(), a.playerUuid(), a.worldId(),
				a.positions(), Map.of(pos, WorldEditEntry.of("minecraft:cobblestone")),
				a.newStates(), a.createdAt(), a.expiresAt());
		assertNotEquals(a.fingerprint(), c.fingerprint());
	}

	// ------------------------------------------------------------------ expiry

	@Test
	void anExpiredPlanIsRecognised() {
		WorldEditPlan p = plan(UUID.randomUUID());
		assertTrue(p.isExpiredAt(p.expiresAt().plusSeconds(1)));
		assertTrue(!p.isExpiredAt(p.expiresAt().minusSeconds(1)));
	}

	// ------------------------------------------------------- string validation

	@Test
	void entrySyntaxRejectsBadBlockIds() {
		assertThrows(IllegalArgumentException.class, () -> WorldEditEntry.of("stone"),
				"a bare id without a namespace must be rejected");
		assertThrows(IllegalArgumentException.class, () -> WorldEditEntry.of("minecraft:Stone"),
				"uppercase must be rejected");
		assertThrows(IllegalArgumentException.class, () -> WorldEditEntry.of("minecraft:with space"));
		assertThrows(IllegalArgumentException.class, () -> WorldEditEntry.of(""));
		assertThrows(IllegalArgumentException.class, () -> WorldEditEntry.of(null));
	}

	@Test
	void entrySyntaxRejectsBadPropertyKeys() {
		assertThrows(IllegalArgumentException.class,
				() -> WorldEditEntry.of("minecraft:oak_stairs", Map.of("FACING", "north")));
		assertThrows(IllegalArgumentException.class,
				() -> WorldEditEntry.of("minecraft:oak_stairs", Map.of("", "north")));
	}

	@Test
	void planShapeRejectsAnEmptyPlan() {
		assertThrows(IllegalArgumentException.class, () -> WorldEditPlan.of(
				"we-x", UUID.randomUUID(), "minecraft:overworld",
				List.of(), Map.of(), Map.of(), Instant.now(), Instant.now().plusSeconds(60)));
	}

	@Test
	void planShapeRejectsMoreThanTheHardLimit() {
		List<BlockPos> positions = new java.util.ArrayList<>();
		Map<BlockPos, WorldEditEntry> originals = new LinkedHashMap<>();
		Map<BlockPos, WorldEditEntry> targets = new LinkedHashMap<>();
		for (int i = 0; i < 257; i++) {
			BlockPos pos = new BlockPos(i, 64, 0);
			positions.add(pos);
			originals.put(pos, stone());
			targets.put(pos, glass());
		}
		assertThrows(IllegalArgumentException.class, () -> WorldEditPlan.of(
				"we-big", UUID.randomUUID(), "minecraft:overworld",
				positions, originals, targets, Instant.now(), Instant.now().plusSeconds(60)));
	}

	@Test
	void planShapeRejectsMismatchedMaps() {
		BlockPos a = new BlockPos(1, 2, 3);
		BlockPos b = new BlockPos(4, 5, 6);
		assertThrows(IllegalArgumentException.class, () -> WorldEditPlan.of(
				"we-x", UUID.randomUUID(), "minecraft:overworld",
				List.of(a), Map.of(a, stone()), Map.of(b, glass()),
				Instant.now(), Instant.now().plusSeconds(60)),
				"every position needs an original AND a target entry");
	}

	@Test
	void planShapeRejectsExpiredAtCreation() {
		BlockPos pos = new BlockPos(1, 2, 3);
		assertThrows(IllegalArgumentException.class, () -> WorldEditPlan.of(
				"we-x", UUID.randomUUID(), "minecraft:overworld",
				List.of(pos), Map.of(pos, stone()), Map.of(pos, glass()),
				Instant.now(), Instant.now().minusSeconds(1)),
				"an already-expired plan must be refused at construction");
	}

	@Test
	void boundsCheckRejectsOutOfBoundsPositions() {
		// pure math: world bottom 0, top 319 (overworld shape), border +/-30M
		assertTrue(WorldEditValidator.inWorldBounds(new BlockPos(0, 0, 0), 0, 319));
		assertTrue(!WorldEditValidator.inWorldBounds(new BlockPos(0, -1, 0), 0, 319));
		assertTrue(!WorldEditValidator.inWorldBounds(new BlockPos(0, 320, 0), 0, 319));
		assertTrue(!WorldEditValidator.inWorldBounds(new BlockPos(30_000_001, 64, 0), 0, 319));
	}
}
