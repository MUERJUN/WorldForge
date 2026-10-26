package dev.worldforge;

import dev.worldforge.ops.worldedit.WorldEditEntry;
import dev.worldforge.ops.worldedit.WorldEditHistory;
import dev.worldforge.ops.worldedit.WorldEditPlan;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v0.8.0: the bounded undo history — per-player isolation, world binding,
 * expiry, and the bounded size.
 */
class WorldEditHistoryTest {

	private static WorldEditPlan plan(String worldId, UUID player, long idSalt, Instant created) {
		BlockPos pos = new BlockPos(1, 2, 3);
		return WorldEditPlan.of("we-" + idSalt, player, worldId,
				List.of(pos),
				Map.of(pos, WorldEditEntry.of("minecraft:stone")),
				Map.of(pos, WorldEditEntry.of("minecraft:glass")),
				created, created.plusSeconds(600));
	}

	@Test
	void theLatestEditIsTheFirstUndoCandidate() {
		WorldEditHistory h = new WorldEditHistory(10);
		UUID player = UUID.randomUUID();
		h.record(plan("minecraft:overworld", player, 1, Instant.now()));
		WorldEditPlan second = plan("minecraft:overworld", player, 2, Instant.now().plusSeconds(1));
		h.record(second);
		assertEquals(second.planId(), h.latestUndoable(player, "minecraft:overworld").planId(),
				"the most recent edit must be the undo candidate");
	}

	@Test
	void undoRemovesTheReturnedEntry() {
		WorldEditHistory h = new WorldEditHistory(10);
		UUID player = UUID.randomUUID();
		h.record(plan("minecraft:overworld", player, 1, Instant.now()));
		WorldEditPlan popped = h.popLatest(player, "minecraft:overworld");
		assertEquals("we-1", popped.planId());
		assertNull(h.latestUndoable(player, "minecraft:overworld"),
				"after popping there is nothing left to undo");
	}

	@Test
	void historyIsIsolatedPerPlayer() {
		WorldEditHistory h = new WorldEditHistory(10);
		UUID alice = UUID.randomUUID();
		h.record(plan("minecraft:overworld", alice, 1, Instant.now()));
		assertNull(h.latestUndoable(UUID.randomUUID(), "minecraft:overworld"),
				"one player's history must never be another player's undo source");
	}

	@Test
	void aPlanCannotBeRecordedUnderADifferentPlayer() {
		WorldEditHistory h = new WorldEditHistory(10);
		WorldEditPlan alicesPlan = plan("minecraft:overworld", UUID.randomUUID(), 1, Instant.now());
		assertThrows(IllegalArgumentException.class,
				() -> h.recordFor(alicesPlan, UUID.randomUUID()),
				"recording a plan under a different player must be refused");
	}

	@Test
	void historyIsIsolatedPerWorld() {
		WorldEditHistory h = new WorldEditHistory(10);
		UUID player = UUID.randomUUID();
		h.record(plan("minecraft:overworld", player, 1, Instant.now()));
		assertNull(h.latestUndoable(player, "minecraft:the_nether"),
				"an edit in one dimension must not be undoable from another world key");
	}

	@Test
	void theHistoryIsBoundedPerPlayer() {
		WorldEditHistory h = new WorldEditHistory(3);
		UUID player = UUID.randomUUID();
		for (int i = 1; i <= 5; i++) {
			h.record(plan("minecraft:overworld", player, i, Instant.now().plusSeconds(i)));
		}
		WorldEditPlan top = h.popLatest(player, "minecraft:overworld");
		WorldEditPlan mid = h.popLatest(player, "minecraft:overworld");
		WorldEditPlan bottom = h.popLatest(player, "minecraft:overworld");
		assertNull(h.popLatest(player, "minecraft:overworld"), "cap 3 -> only the 3 newest survive");
		assertEquals("we-5", top.planId());
		assertEquals("we-4", mid.planId());
		assertEquals("we-3", bottom.planId());
	}

	@Test
	void anExpiredEditIsNotUndoable() {
		WorldEditHistory h = new WorldEditHistory(10);
		UUID player = UUID.randomUUID();
		h.record(plan("minecraft:overworld", player, 1, Instant.now().minusSeconds(700)));
		assertNull(h.latestUndoable(player, "minecraft:overworld"),
				"a plan past its expiry must not be undoable");
	}

	@Test
	void historyRejectsNullArguments() {
		WorldEditHistory h = new WorldEditHistory(10);
		assertThrows(NullPointerException.class, () -> h.record(null));
	}
}
