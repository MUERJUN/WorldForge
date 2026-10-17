package dev.worldforge;

import dev.worldforge.ai.Proposal;
import dev.worldforge.ai.ProposalDiff;
import dev.worldforge.ai.ProposalJson;
import dev.worldforge.ai.ProposalValidator;
import dev.worldforge.definition.BehaviorDefinition;
import dev.worldforge.definition.ItemDefinition;
import dev.worldforge.definition.TextureSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.3.0 AI proposal layer unit tests - pure logic, no world state.
 * Covers spec 十 tests 1,2,3,7 (parse legal/illegal/unsupported, delta merge
 * preserving untouched attributes) plus diff rendering.
 */
class ProposalJsonTest {

	// ---- test 1: model returns a legal create proposal ----
	@Test
	void parsesLegalCreateProposal() {
		String json = """
				{"kind":"create","id":"worldforge:flame_blade","name":"烈焰之刃","description":"燃烧吧",
				 "texture":"minecraft:item/diamond_sword","damage":10,"max_durability":200,
				 "behaviors":[{"trigger":"hit_entity","action":"ignite","cooldown_ticks":40,"parameters":{"seconds":5}}]}""";
		var r = ProposalJson.parse(json);
		assertTrue(r.ok(), () -> "should parse: " + r.error());
		Proposal.Create c = assertInstanceOf(Proposal.Create.class, r.proposal());
		assertEquals("worldforge:flame_blade", c.id());
		assertEquals(10.0, c.damage());
		assertEquals(200, c.maxDurability());
		assertEquals(1, c.behaviors().size());
		assertEquals("ignite", c.behaviors().get(0).action());
		// validator accepts it (format only; existence check passes on empty runtime)
		assertTrue(ProposalValidator.validate(c).ok(), () -> ProposalValidator.validate(c).joined());
		assertTrue(ProposalValidator.capabilityGaps(c).isEmpty());
	}

	// ---- test 2: model returns illegal JSON ----
	@Test
	void rejectsMalformedJson() {
		assertFalse(ProposalJson.parse("{ this is not json").ok());
		assertFalse(ProposalJson.parse("").ok());
		assertFalse(ProposalJson.parse("I cannot help with that.").ok()); // no object at all
		assertFalse(ProposalJson.parse("{\"kind\":\"create\",\"id\":").ok()); // truncated
		assertFalse(ProposalJson.parse("[1,2,3]").ok()); // not object
		assertFalse(ProposalJson.parse("{\"id\":\"worldforge:x\"}").ok()); // missing kind
	}

	// fenced code blocks and prose are tolerated, then parsed
	@Test
	void toleratesFencedJson() {
		String wrapped = "好的，这是提案：\n```json\n{\"kind\":\"delete\",\"id\":\"worldforge:a\"}\n```\n请确认。";
		var r = ProposalJson.parse(wrapped);
		assertTrue(r.ok(), () -> r.error());
		assertEquals("worldforge:a", ((Proposal.Delete) r.proposal()).id());
	}

	// ---- test 3: model proposes an unsupported skill ----
	@Test
	void flagsUnsupportedActionAsCapabilityGap() {
		String json = """
				{"kind":"create","id":"worldforge:x","name":"X","damage":5,"max_durability":10,
				 "behaviors":[{"trigger":"right_click","action":"fly_forever","cooldown_ticks":20}]}""";
		var r = ProposalJson.parse(json);
		assertTrue(r.ok());
		var gaps = ProposalValidator.capabilityGaps(r.proposal());
		assertFalse(gaps.isEmpty(), "fly_forever must be reported as unsupported");
		assertTrue(gaps.get(0).contains("fly_forever"));
		assertFalse(ProposalValidator.validate(r.proposal()).ok(), "validator must reject unknown action");
	}

	@Test
	void rejectsOutOfRangeValues() {
		var p = new Proposal.Create("worldforge:x", "X", "", "minecraft:item/diamond_sword",
				999999.0, 10, null, List.of());
		var errs = ProposalValidator.validate(p);
		assertFalse(errs.ok());
		assertTrue(errs.joined().contains("damage"));
	}

	@Test
	void rejectsIllegalIdAndTexture() {
		var p = new Proposal.Create("Bad ID", "X", "", "minecraft:item/does_not_exist", 5.0, 10, null, List.of());
		var errs = ProposalValidator.validate(p);
		assertFalse(errs.ok());
		assertTrue(errs.joined().contains("id syntax") || errs.joined().contains("invalid id"));
		assertTrue(errs.joined().contains("texture"));
	}

	// ---- test 7: update delta preserves untouched attributes ----
	@Test
	void updateMergeKeepsUntouchedFields() {
		ItemDefinition cur = new ItemDefinition(ItemDefinition.CURRENT_VERSION, "worldforge:t", "旧名", "旧描述",
				TextureSource.vanilla("minecraft:item/diamond_sword"),
				Map.of("damage", 12.0, "max_durability", 500.0, "attack_speed", -2.4),
				List.of(new BehaviorDefinition("right_click", List.of("summon_lightning"),
						Map.of("range", 12.0), 100)));
		// only change damage -> name/description/texture/attack_speed/cooldown all survive
		var u = new Proposal.Update("worldforge:t", null, null, null, 20.0, null, null, null, null);
		var merged = ProposalJson.mergeUpdate(cur, u);
		assertEquals(20.0, merged.damage());
		assertEquals("旧名", merged.name());
		assertEquals("旧描述", merged.description());
		assertEquals("minecraft:item/diamond_sword", merged.texture().resource());
		assertEquals(500.0, merged.attributes().get("max_durability"));
		assertEquals(-2.4, merged.attributes().get("attack_speed"), "attack_speed must NOT be reset");
		assertEquals(1, merged.behaviors().size(), "existing behavior survives");
		assertEquals(100, merged.behaviors().get(0).cooldownTicks());
	}

	@Test
	void updateMergeAddAndRemoveBehaviors() {
		ItemDefinition cur = new ItemDefinition(ItemDefinition.CURRENT_VERSION, "worldforge:t", "T", "",
				TextureSource.default_(), Map.of("damage", 5.0, "max_durability", 100.0),
				List.of(new BehaviorDefinition("right_click", List.of("heal"), Map.of("amount", 4.0), 60)));
		// add a hit_entity ignite, keep the right_click heal
		var add = new Proposal.BehaviorSpec("hit_entity", "ignite", 40, Map.of("seconds", 5.0));
		var u = new Proposal.Update("worldforge:t", null, null, null, null, null, null, List.of(add), null);
		var merged = ProposalJson.mergeUpdate(cur, u);
		assertEquals(2, merged.behaviors().size());
		// remove right_click -> only ignite left
		var u2 = new Proposal.Update("worldforge:t", null, null, null, null, null, null, null,
				List.of("right_click"));
		var merged2 = ProposalJson.mergeUpdate(cur, u2);
		assertEquals(0, merged2.behaviors().size());
	}

	@Test
	void updateMergeSetBehaviorsReplacesAll() {
		ItemDefinition cur = new ItemDefinition(ItemDefinition.CURRENT_VERSION, "worldforge:t", "T", "",
				TextureSource.default_(), Map.of("damage", 5.0, "max_durability", 100.0),
				List.of(new BehaviorDefinition("right_click", List.of("heal"), Map.of(), 60)));
		var set = new Proposal.BehaviorSpec("hit_entity", "explosion", 20, Map.of("power", 2.0, "fire", true));
		var u = new Proposal.Update("worldforge:t", null, null, null, null, null, List.of(set), null, null);
		var merged = ProposalJson.mergeUpdate(cur, u);
		assertEquals(1, merged.behaviors().size());
		assertEquals("explosion", merged.behaviors().get(0).actions().get(0));
	}

	@Test
	void toDefinitionAppliesDefaults() {
		var c = new Proposal.Create("worldforge:n", "N", null, null, null, null, null, null);
		var def = ProposalJson.toDefinition(c);
		assertEquals("worldforge:n", def.id());
		assertEquals("N", def.name());
		assertEquals("", def.description());
		assertEquals(0.0, def.damage());
		assertEquals(100, def.maxDurability());
		assertTrue(def.behaviors().isEmpty());
	}

	@Test
	void diffSummarizesDeltaForPreview() {
		var c = new Proposal.Create("worldforge:thunder", "雷霆之剑", "召唤闪电",
				"minecraft:item/diamond_sword", 12.0, 500, null,
				List.of(new Proposal.BehaviorSpec("right_click", "summon_lightning", 100, Map.of("range", 12.0))));
		String d = ProposalDiff.describe(c);
		assertTrue(d.contains("创建新物品"), d);
		assertTrue(d.contains("雷霆之剑"), d);
		assertTrue(d.contains("12"), d);
		assertTrue(d.contains("summon_lightning"), d);
		assertTrue(d.contains("100t"), d);
	}

	@Test
	void updateParserAcceptsFlatAndPatchedForms() {
		var flat = ProposalJson.parse("{\"kind\":\"update\",\"id\":\"worldforge:a\",\"damage\":20}");
		assertTrue(flat.ok(), () -> flat.error());
		assertEquals(20.0, ((Proposal.Update) flat.proposal()).damage());
		var patched = ProposalJson.parse(
				"{\"kind\":\"update\",\"id\":\"worldforge:a\",\"patch\":{\"name\":\"新名\",\"add_behaviors\":"
						+ "[{\"trigger\":\"right_click\",\"action\":\"heal\",\"parameters\":{\"amount\":4}}]}}");
		assertTrue(patched.ok(), () -> patched.error());
		var u = (Proposal.Update) patched.proposal();
		assertEquals("新名", u.name());
		assertEquals(1, u.addBehaviors().size());
		assertNull(u.damage(), "untouched field must stay null (no change)");
	}
}
