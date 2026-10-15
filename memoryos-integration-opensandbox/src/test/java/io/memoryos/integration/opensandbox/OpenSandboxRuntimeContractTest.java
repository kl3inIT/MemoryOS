package io.memoryos.integration.opensandbox;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.memoryos.sandbox.SandboxCommand;
import io.memoryos.sandbox.SandboxGoneException;
import io.memoryos.sandbox.SandboxSpec;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * The runtime against a real OpenSandbox server (MEM-270), as agent-sandbox's compatibility kit runs every backend. It
 * needs a server and the computer image, so it runs where they are: {@code MEMORYOS_SANDBOX_TEST_ENDPOINT},
 * {@code MEMORYOS_SANDBOX_TEST_API_KEY} and {@code MEMORYOS_SANDBOX_TEST_IMAGE}.
 */
@EnabledIfEnvironmentVariable(named = "MEMORYOS_SANDBOX_TEST_ENDPOINT", matches = ".+")
class OpenSandboxRuntimeContractTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Test
    void aSandboxRunsCommandsKeepsFilesAndIsGoneOnceKilled() throws Exception {
        var properties = new OpenSandboxProperties(System.getenv("MEMORYOS_SANDBOX_TEST_ENDPOINT"),
                System.getenv("MEMORYOS_SANDBOX_TEST_API_KEY"), Duration.ofSeconds(180), Duration.ofSeconds(60));
        String label = UUID.randomUUID().toString();
        try (var runtime = new OpenSandboxRuntime(properties)) {
            String id = runtime.create(new SandboxSpec(System.getenv("MEMORYOS_SANDBOX_TEST_IMAGE"), Duration.ofMinutes(3),
                    "1", "1Gi", List.of("pypi.org"), Map.of("memoryos.io/managed", "true", "memoryos.io/test", label)));
            try {
                var who = runtime.run(id, new SandboxCommand("id -u; pwd", "/workspace", 1000, TIMEOUT));
                assertEquals(0, who.exitCode());
                assertEquals("1000\n/workspace", who.stdout());

                var failed = runtime.run(id, new SandboxCommand("echo bad >&2; exit 3", "/workspace", 1000, TIMEOUT));
                assertEquals(3, failed.exitCode());
                assertEquals("bad", failed.stderr());
                assertFalse(failed.timedOut());

                var slow = runtime.run(id, new SandboxCommand("sleep 30", "/workspace", 1000, Duration.ofSeconds(3)));
                assertTrue(slow.timedOut(), "a command stopped at its timeout is reported as such");
                assertEquals("no-sleep", runtime.run(id, new SandboxCommand(
                        "pgrep -x sleep >/dev/null && echo still-running || echo no-sleep", "/workspace", 1000, TIMEOUT)).stdout());

                byte[] content = new byte[1 << 20];
                new Random(7).nextBytes(content);
                runtime.write(id, "/workspace/data/a.bin", content);
                assertArrayEquals(content, runtime.read(id, "/workspace/data/a.bin", 2 << 20));
                // Written as the sandbox user, so a command can change what a tool wrote.
                assertEquals(0, runtime.run(id, new SandboxCommand("echo more >> /workspace/data/a.bin", "/workspace", 1000,
                        TIMEOUT)).exitCode());
                assertThrows(IllegalArgumentException.class, () -> runtime.read(id, "/workspace/data/a.bin", 1024));
                assertTrue(runtime.list(id, "/workspace", 2).stream().anyMatch(entry -> entry.path().endsWith("a.bin")));

                assertEquals("000", runtime.run(id, new SandboxCommand(
                        "curl -s -o /dev/null -w '%{http_code}' -m 8 https://example.com || true", "/workspace", 1000,
                        TIMEOUT)).stdout());

                runtime.renew(id, Duration.ofMinutes(5));
                assertTrue(runtime.running(Map.of("memoryos.io/test", label)).containsKey(id));
            } finally {
                runtime.kill(id);
            }
            assertThrows(SandboxGoneException.class, () -> runtime.renew(id, Duration.ofMinutes(1)));
            runtime.kill(id);
        }
    }
}
