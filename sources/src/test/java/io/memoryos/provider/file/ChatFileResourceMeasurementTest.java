package io.memoryos.provider.file;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/** Opt-in Windows OS measurements; fixtures and measured parser run in separate JVMs. */
@EnabledOnOs(OS.WINDOWS)
@EnabledIfEnvironmentVariable(named = "MEMORYOS_CHAT_RESOURCE_TEST", matches = "true")
class ChatFileResourceMeasurementTest {
    @TempDir Path temporary;

    @Test
    void measuresLargeFilesAndConcurrentCallersInFreshBoundedProcesses() throws Exception {
        var reports = Path.of("build/reports/mem81-resources").toAbsolutePath();
        Files.createDirectories(reports);
        for (int limit : new int[] {100, 250}) {
            Path fixture = temporary.resolve("large-" + limit + ".xlsx");
            // This allocation is deliberately outside the measured process and time interval.
            LargeChatSpreadsheetExtractionTest.createWorkbook(fixture, limit);
            for (int callers : limit == 100 ? new int[] {1} : new int[] {1, 3}) {
                String name = limit + "m-" + callers + "caller";
                Path scratch = Files.createDirectory(temporary.resolve(name));
                Path result = reports.resolve(name + "-parser.json");
                Path arguments = temporary.resolve(name + ".args");
                Files.writeString(arguments, "-Xmx512m\n" + quote("-Djava.io.tmpdir=" + scratch) + "\n-cp\n"
                        + quote(System.getProperty("java.class.path")) + "\n" + ChatFileResourceProbe.class.getName() + "\n"
                        + quote(fixture.toString()) + "\n" + callers + "\n" + quote(result.toString()) + "\n");
                var process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-File",
                        Path.of("../scripts/measure-chat-file-process.ps1").toAbsolutePath().normalize().toString(),
                        "-JavaExecutable", Path.of(System.getProperty("java.home"), "bin/java.exe").toString(),
                        "-ArgumentsFile", arguments.toString(), "-ScratchDirectory", scratch.toString(),
                        "-ReportPath", reports.resolve(name + "-os.json").toString())
                        .redirectErrorStream(true).redirectOutput(reports.resolve(name + ".log").toFile()).start();
                try {
                    assertTrue(process.waitFor(Duration.ofMinutes(3).toMillis(), TimeUnit.MILLISECONDS), "Resource probe timed out");
                    assertEquals(0, process.exitValue(), "Probe failed; see " + reports.resolve(name + ".log"));
                } finally {
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                    process.destroyForcibly();
                }
                var measured = new ObjectMapper().readTree(result.toFile());
                assertEquals(callers, measured.path("completed").asInt());
                assertEquals(1, measured.path("peakActiveInputCopies").asInt());
                try (var remaining = Files.list(scratch)) { assertEquals(0, remaining.count(), "Parser temp must be reclaimed"); }
                var os = new ObjectMapper().readTree(reports.resolve(name + "-os.json").toFile());
                assertTrue(os.path("samples").asInt() > 0);
                assertTrue(os.path("peakWorkingSetBytes").asLong() > 0);
                assertTrue(os.path("peakTempBytes").asLong() > 0);
                assertTrue(os.path("peakTempFiles").asInt() <= 2);
            }
        }
    }

    private static String quote(String value) { return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
}
