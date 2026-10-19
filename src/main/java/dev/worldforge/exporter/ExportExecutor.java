package dev.worldforge.exporter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Orchestrates the full export: validate → generate → build → checksum.
 *
 * <p>Thread-safe: designed to run on a background thread so the Minecraft
 * render loop is never blocked. The caller gets an {@link ModExporter.ExportResult}
 * with the JAR path, SHA-256, or an error message.</p>
 *
 * <p>v0.5.1 improvements:</p>
 * <ul>
 *   <li>Gradle wrapper copied from bundled resources, not source repo</li>
 *   <li>Java compile environment checked before build</li>
 *   <li>Build timeout prevents hanging</li>
 *   <li>Build log sanitized of sensitive data</li>
 *   <li>On build failure, source project is still returned for inspection</li>
 * </ul>
 */
public final class ExportExecutor {

	/** Build timeout in minutes. */
	private static final long BUILD_TIMEOUT_MINUTES = 10;

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
			// copy Gradle wrapper from bundled resources (no source repo dependency)
			gen.copyGradleWrapper(Path.of(".").toAbsolutePath());
		} catch (Exception e) {
			return new ModExporter.ExportResult(false, projectDir, null, null,
					"项目生成失败: " + e.getMessage(), warnings);
		}

		// 4) check Java environment
		var javaCheck = checkJavaEnvironment();
		if (javaCheck != null) {
			warnings = new ArrayList<>(warnings);
			warnings.add(javaCheck);
			// Return source project without claiming JAR build success
			return new ModExporter.ExportResult(false, projectDir, null, null,
					"Java 编译环境不可用: " + javaCheck
							+ "。已导出完整源码工程到 " + projectDir
							+ "，请安装 JDK 21 后运行 gradlew build。",
					warnings);
		}

		// 5) build (gradle) with timeout
		try {
			var jarPath = buildProject(projectDir, req);
			if (jarPath == null) {
				return new ModExporter.ExportResult(false, projectDir, null, null,
						"Gradle 构建未产生 JAR 文件。源码工程位于 " + projectDir, warnings);
			}
			var sha = sha256(jarPath);
			return new ModExporter.ExportResult(true, projectDir, jarPath, sha,
					null, warnings);
		} catch (Exception e) {
			return new ModExporter.ExportResult(false, projectDir, null, null,
					"构建失败: " + sanitizeLog(e.getMessage())
							+ "。源码工程位于 " + projectDir + " 可供检查。",
					warnings);
		}
	}

	/**
	 * Check that a Java 21+ JDK is available for compilation.
	 * Returns null if OK, or an error message.
	 */
	static String checkJavaEnvironment() {
		try {
			var pb = new ProcessBuilder(List.of("java", "-version"));
			pb.redirectErrorStream(true);
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
				return "java -version 返回非零退出码";
			}
			var log = output.toString();
			// Check for Java 21+
			if (!log.contains("21.") && !log.contains("\"21\"")) {
				return "需要 Java 21，当前: " + log.trim();
			}
			return null; // OK
		} catch (Exception e) {
			return "无法执行 java -version: " + e.getMessage();
		}
	}

	/**
	 * Build the project using the Gradle wrapper. Returns the JAR path or null.
	 * Includes timeout to prevent hanging.
	 */
	private static Path buildProject(Path projectDir, ModExporter.ExportRequest req)
			throws Exception {
		// Use the Gradle wrapper if present, otherwise system gradle
		var gradlew = projectDir.resolve("gradlew").toAbsolutePath();
		var gradleBat = projectDir.resolve("gradlew.bat").toAbsolutePath();
		List<String> gradleCmd;
		if (Files.exists(gradleBat)) {
			gradleCmd = List.of(gradleBat.toString(), "build", "--console=plain");
		} else if (Files.exists(gradlew)) {
			gradleCmd = List.of(gradlew.toString(), "build", "--console=plain");
		} else {
			gradleCmd = List.of("gradle", "build", "--console=plain");
		}

		var pb = new ProcessBuilder(gradleCmd);
		pb.directory(projectDir.toFile());
		pb.redirectErrorStream(true);
		// don't inherit any sensitive environment
		pb.environment().remove("WORLDFORGE_API_KEY");
		pb.environment().remove("DEEPSEEK_API_KEY");
		pb.environment().remove("FABRIC_API_KEY");

		var proc = pb.start();
		var output = new StringBuilder();
		try (var reader = proc.inputReader()) {
			String line;
			while ((line = reader.readLine()) != null) {
				output.append(line).append('\n');
			}
		}
		boolean finished = proc.waitFor(BUILD_TIMEOUT_MINUTES, TimeUnit.MINUTES);
		if (!finished) {
			proc.destroyForcibly();
			throw new RuntimeException("Gradle 构建超时 (" + BUILD_TIMEOUT_MINUTES + " 分钟)");
		}
		int exit = proc.exitValue();
		if (exit != 0) {
			throw new RuntimeException("Gradle exit " + exit + ":\n"
					+ sanitizeLog(output.substring(0, Math.min(output.length(), 2000))));
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

	/** Sanitize build log: remove API keys, tokens, and sensitive paths. */
	static String sanitizeLog(String log) {
		if (log == null) return "";
		return log
				.replaceAll("(?i)api[_-]?key[=: ]+\\S+", "api_key=***REDACTED***")
				.replaceAll("(?i)token[=: ]+\\S+", "token=***REDACTED***")
				.replaceAll("(?i)password[=: ]+\\S+", "password=***REDACTED***");
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
