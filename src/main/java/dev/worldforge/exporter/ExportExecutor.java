package dev.worldforge.exporter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Orchestrates the full export: validate → generate → build → checksum.
 *
 * <p>Thread-safe: designed to run on a background thread so the Minecraft
 * render loop is never blocked. The caller gets an {@link ModExporter.ExportResult}
 * with the JAR path, SHA-256, or an error message.</p>
 */
public final class ExportExecutor {

	/**
	 * Run a full export synchronously. Call from a background thread.
	 */
	public static ModExporter.ExportResult export(ModExporter.ExportRequest req) {
		// 1) validate
		var errors = ModExporter.validate(req);
		if (!errors.isEmpty()) {
			return new ModExporter.ExportResult(false, null, null, null,
					"校验失败: " + String.join("; ", errors), List.of());
		}

		// 2) check unsupported features
		var warnings = ModExporter.checkUnsupported(req);

		// 3) generate project
		Path projectDir = req.outputDir().resolve(req.modId());
		try {
			Files.createDirectories(projectDir);
			var gen = new TemplateProjectGenerator(req, projectDir);
			gen.generate();
		} catch (Exception e) {
			return new ModExporter.ExportResult(false, projectDir, null, null,
					"项目生成失败: " + e.getMessage(), warnings);
		}

		// 4) build (gradle)
		try {
			var jarPath = buildProject(projectDir, req);
			if (jarPath == null) {
				return new ModExporter.ExportResult(false, projectDir, null, null,
						"Gradle 构建未产生 JAR 文件", warnings);
			}
			var sha = sha256(jarPath);
			return new ModExporter.ExportResult(true, projectDir, jarPath, sha,
					null, warnings);
		} catch (Exception e) {
			return new ModExporter.ExportResult(false, projectDir, null, null,
					"构建失败: " + e.getMessage(), warnings);
		}
	}

	/**
	 * Build the project using the system Gradle. Returns the JAR path or null.
	 */
	private static Path buildProject(Path projectDir, ModExporter.ExportRequest req)
			throws Exception {
		// Use the Gradle wrapper if present, otherwise system gradle
		var gradlew = projectDir.resolve("gradlew").toAbsolutePath();
		var gradleCmd = Files.exists(gradlew)
				? List.of(gradlew.toString(), "build", "--console=plain")
				: List.of("gradle", "build", "--console=plain");

		var pb = new ProcessBuilder(gradleCmd);
		pb.directory(projectDir.toFile());
		pb.redirectErrorStream(true);
		// don't inherit any sensitive environment
		pb.environment().remove("WORLDFORGE_API_KEY");
		pb.environment().remove("DEEPSEEK_API_KEY");

		var proc = pb.start();
		var output = new StringBuilder();
		try (var reader = proc.inputReader()) {
			String line;
			while ((line = reader.readLine()) != null) {
				output.append(line).append('\n');
			}
		}
		int exit = proc.waitFor();
		if (exit != 0) {
			throw new RuntimeException("Gradle exit " + exit + ":\n"
					+ output.substring(0, Math.min(output.length(), 2000)));
		}

		// find the JAR
		var jarDir = projectDir.resolve("build/libs");
		if (!Files.exists(jarDir)) return null;
		try (var stream = Files.list(jarDir)) {
			return stream
					.filter(p -> p.toString().endsWith(".jar")
							&& !p.toString().endsWith("-sources.jar"))
					.findFirst()
					.orElse(null);
		}
	}

	private static String sha256(Path file) throws Exception {
		var md = MessageDigest.getInstance("SHA-256");
		try (var is = Files.newInputStream(file)) {
			byte[] buf = new byte[8192];
			int n;
			while ((n = is.read(buf)) > 0) md.update(buf, 0, n);
		}
		return HexFormat.of().formatHex(md.digest());
	}

	private ExportExecutor() {}
}
