package dev.worldforge;

import dev.worldforge.exporter.ExportExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.5.3 Phase 2: subprocess lifecycle — timeout, cancellation, async log drain.
 *
 * <p>Uses fake long-running subprocesses (ping/timeout/certutil) so no real
 * Gradle is involved and tests stay fast and hermetic.</p>
 */
class SubprocessLifecycleTest {

	private static Process startLongProcess() throws Exception {
		// Windows: ping -n is a reliable ~N second process producing steady output
		var pb = new ProcessBuilder("ping", "-n", "30", "127.0.0.1");
		pb.redirectErrorStream(true);
		return pb.start();
	}

	@Test
	@EnabledOnOs(OS.WINDOWS)
	void timeoutDestroysLongRunningProcess() throws Exception {
		var proc = startLongProcess();
		assertTrue(proc.isAlive());
		var output = new StringBuilder();

		long start = System.nanoTime();
		assertThrows(ExportExecutor.BuildTimeoutExceptionAccessor.timeoutClass(),
				() -> ExportExecutor.runForTest(proc, output, 1, TimeUnit.SECONDS),
				"a 30s process must hit a 1s timeout");
		long elapsedMs = (System.nanoTime() - start) / 1_000_000;

		// timeout must fire near the 1s bound, NOT after the process ends (~30s)
		assertTrue(elapsedMs < 10_000, "timeout fired after " + elapsedMs + "ms — too late");

		// give the OS a moment to reap the destroyed process
		Thread.sleep(500);
		assertFalse(proc.isAlive(), "process must be destroyed after timeout");
	}

	@Test
	@EnabledOnOs(OS.WINDOWS)
	void cancelDestroysRunningProcess() throws Exception {
		var proc = startLongProcess();
		var output = new StringBuilder();
		var cancelled = new AtomicBoolean(false);

		// simulate cancel() mid-run from another thread
		Thread canceller = new Thread(() -> {
			try {
				Thread.sleep(300);
				cancelled.set(true);
				proc.descendants().forEach(ProcessHandle::destroyForcibly);
				proc.destroyForcibly();
			} catch (Exception ignored) {
			}
		});
		canceller.start();

		// waitFor returns quickly once destroyed; runForTest returns the exit code
		int exit = assertDoesNotThrow(() -> ExportExecutor.runForTest(proc, output, 60, TimeUnit.SECONDS));
		assertTrue(exit != 0, "destroyed process must yield a non-zero exit code");
		assertFalse(proc.isAlive());
		canceller.join(2000);
	}

	@Test
	@EnabledOnOs(OS.WINDOWS)
	void logReaderCapturesOutputWithoutBlocking() throws Exception {
		var pb = new ProcessBuilder("cmd", "/c", "echo hello-from-export-test");
		pb.redirectErrorStream(true);
		var proc = pb.start();
		var output = new StringBuilder();

		int exit = ExportExecutor.runForTest(proc, output, 30, TimeUnit.SECONDS);
		assertEquals(0, exit);
		// the async reader must have captured the echo
		assertTrue(output.toString().contains("hello-from-export-test"),
				"async log reader must drain output: " + output);
	}

	@Test
	@EnabledOnOs(OS.WINDOWS)
	void continuousOutputCannotStarveTimeout() throws Exception {
		// a process that prints a line every 100ms and never exits on its own
		var pb = new ProcessBuilder("powershell", "-NoProfile", "-Command",
				"while($true){ Write-Output tick; Start-Sleep -Milliseconds 100 }");
		pb.redirectErrorStream(true);
		var proc = pb.start();
		var output = new StringBuilder();

		assertThrows(ExportExecutor.BuildTimeoutExceptionAccessor.timeoutClass(),
				() -> ExportExecutor.runForTest(proc, output, 1, TimeUnit.SECONDS),
				"steady output must not starve the bounded wait");
		Thread.sleep(300);
		assertFalse(proc.isAlive(), "noisy process must still be destroyed on timeout");
		assertTrue(output.toString().contains("tick"),
				"reader should have captured at least one tick before teardown");
	}
}
