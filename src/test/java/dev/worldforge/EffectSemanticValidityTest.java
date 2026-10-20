package dev.worldforge;

import dev.worldforge.exporter.ExportSymbolMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.5.7 P1-1: effect ids must be SEMANTICALLY valid vanilla 1.21.1 effects,
 * not merely well-formed strings. A well-formed but nonexistent id would make
 * the exported code call getEntry -> empty and silently do nothing while
 * still claiming SUCCESS and starting the cooldown.
 */
class EffectSemanticValidityTest {

	@Test
	void realVanillaEffectsAccepted() {
		assertEquals("speed", ExportSymbolMapper.strictEffectId("speed"));
		assertEquals("strength", ExportSymbolMapper.strictEffectId("strength"));
		assertEquals("fire_resistance", ExportSymbolMapper.strictEffectId("fire_resistance"));
		assertEquals("instant_health", ExportSymbolMapper.strictEffectId("instant_health"));
	}

	@Test
	void wellFormedButFakeEffectRejected() {
		var e = assertThrows(IllegalArgumentException.class,
				() -> ExportSymbolMapper.strictEffectId("flying_machine"));
		assertTrue(e.getMessage().contains("原版效果"), "must explain it is not vanilla: " + e.getMessage());
		assertThrows(IllegalArgumentException.class,
				() -> ExportSymbolMapper.strictEffectId("super_speed"));
	}

	@Test
	void emptyAndNamespaceRejected() {
		assertThrows(IllegalArgumentException.class, () -> ExportSymbolMapper.strictEffectId(""));
		assertThrows(IllegalArgumentException.class, () -> ExportSymbolMapper.strictEffectId(null));
		var e = assertThrows(IllegalArgumentException.class,
				() -> ExportSymbolMapper.strictEffectId("mymod:custom"));
		assertTrue(e.getMessage().contains("命名空间"), "namespace explained: " + e.getMessage());
	}

	@Test
	void illegalCharsStillRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> ExportSymbolMapper.strictEffectId("speed\"); player.kill(); //"));
		assertThrows(IllegalArgumentException.class,
				() -> ExportSymbolMapper.strictEffectId("Speed")); // uppercase
	}

	@Test
	void effectSetMatches1211() {
		// spot-check a few 1.21.1-specific effects exist and a removed one does not
		assertTrue(ExportSymbolMapper.VANILLA_EFFECTS_1_21_1.contains("wind_charged"));
		assertTrue(ExportSymbolMapper.VANILLA_EFFECTS_1_21_1.contains("weaving"));
		assertTrue(ExportSymbolMapper.VANILLA_EFFECTS_1_21_1.contains("infested"));
		assertFalse(ExportSymbolMapper.VANILLA_EFFECTS_1_21_1.contains("luck_of_the_sea")); // enchantment, not effect
		// 39 effects in 1.21.1 (verified against StatusEffects of the merged jar)
		assertEquals(39, ExportSymbolMapper.VANILLA_EFFECTS_1_21_1.size());
	}
}
