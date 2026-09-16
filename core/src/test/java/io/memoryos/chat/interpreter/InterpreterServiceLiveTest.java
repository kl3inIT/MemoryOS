package io.memoryos.chat.interpreter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Opt-in check of the real memoryos-interpreter multipart, execute and file transport. */
@EnabledIfEnvironmentVariable(named = "MEMORYOS_INTERPRETER_LIVE_URL", matches = "https?://.+")
class InterpreterServiceLiveTest {
    @Test
    void realServiceRunsCodeOverAStreamedUnicodeFileAndReturnsTheGeneratedFile() throws Exception {
        var properties = new InterpreterProperties(System.getenv("MEMORYOS_INTERPRETER_LIVE_URL"),
                System.getenv("MEMORYOS_INTERPRETER_LIVE_API_KEY"));
        try (var client = new InterpreterClient(properties)) {
            assertTrue(client.health().healthy(), client.health().error());
            var input = "thang,doanh_thu\n1,10\n2,32\n".getBytes(StandardCharsets.UTF_8);
            var fileId = client.upload("báo cáo.csv", "text/csv", new ByteArrayInputStream(input));
            String outputId = null;
            try {
                var result = client.execute("""
                        import csv
                        rows = list(csv.DictReader(open('báo cáo.csv', encoding='utf-8')))
                        total = sum(int(row['doanh_thu']) for row in rows)
                        open('tổng.txt', 'w', encoding='utf-8').write(str(total))
                        print(total)
                        """, 30_000, List.of(new InterpreterClient.StagedFile("báo cáo.csv", fileId)));
                assertEquals(0, result.exitCode(), result.stderr());
                assertFalse(result.timedOut());
                assertEquals("42", result.stdout().strip());
                var generated = result.files().stream().filter(file -> "tổng.txt".equals(file.path())).findFirst().orElseThrow();
                outputId = generated.fileId();
                assertEquals("42", new String(client.download(outputId), StandardCharsets.UTF_8));
            } finally {
                client.delete(fileId);
                if (outputId != null) client.delete(outputId);
            }
        }
    }
}
