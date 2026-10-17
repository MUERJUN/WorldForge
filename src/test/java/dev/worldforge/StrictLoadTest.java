package dev.worldforge;

import dev.worldforge.persistence.DefinitionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class StrictLoadTest {
	@Test
	void partialDamageRejected(@TempDir Path dir) throws Exception {
		Files.writeString(dir.resolve("items.json"),
				"{\"version\":1,\"items\":[{\"version\":1,\"id\":\"worldforge:ok\",\"name\":\"A\"},{\"no_id\":true}]}");
		var res = new DefinitionStore(dir).tryLoadStrict();
		assertFalse(res.ok(), () -> "must reject, got ok with " + res.definitions().size() + " defs");
	}
}
