package io.memoryos.connector.adapter.lark;

import io.memoryos.connector.LarkProviderException;
import java.net.URI;
import java.time.Duration;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param baseUrl          replaces the address of the credential's region; only a test sets it
 * @param maxResponseBytes the bound of one JSON answer
 * @param maxSnapshotBytes the bound of a Docx or Sheet snapshot, which is stored as one item version
 * @param maxBlocks        the most blocks read of one Docx document
 * @param maxCells         the most cells read of one sheet
 * @param maxSheets        the most sheets read of one spreadsheet
 */
@ConfigurationProperties("memoryos.lark")
public record LarkProviderProperties(
        @Nullable URI baseUrl, Duration connectTimeout, Duration requestTimeout, Duration contentTimeout,
        int maxResponseBytes, int maxContentBytes, int maxSnapshotBytes, int maxBlocks, int maxCells, int maxSheets,
        String userAgent) {

    public LarkProviderProperties {
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(3) : connectTimeout;
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(30) : requestTimeout;
        contentTimeout = contentTimeout == null ? Duration.ofSeconds(120) : contentTimeout;
        maxResponseBytes = maxResponseBytes == 0 ? 4_194_304 : maxResponseBytes;
        maxContentBytes = maxContentBytes == 0 ? 104_857_600 : maxContentBytes;
        maxSnapshotBytes = maxSnapshotBytes == 0 ? 33_554_432 : maxSnapshotBytes;
        maxBlocks = maxBlocks == 0 ? 20_000 : maxBlocks;
        maxCells = maxCells == 0 ? 200_000 : maxCells;
        maxSheets = maxSheets == 0 ? 100 : maxSheets;
        userAgent = userAgent == null || userAgent.isBlank() ? "MemoryOS-LarkConnector/1.0" : userAgent;
    }

    // Validation is operation-local: absent or wrong Lark settings must not prevent another provider's startup.
    void validate() {
        if ((baseUrl != null && !endpoint(baseUrl)) || !duration(connectTimeout, 30) || !duration(requestTimeout, 120)
                || !duration(contentTimeout, 600)
                || maxResponseBytes < 1024 || maxResponseBytes > 16_777_216
                || maxContentBytes < 1024 || maxContentBytes > 104_857_600
                || maxSnapshotBytes < 1024 || maxSnapshotBytes > 33_554_432
                || maxBlocks < 1 || maxCells < 1 || maxSheets < 1 || userAgent.length() > 200) {
            throw new LarkProviderException(LarkProviderException.Failure.UNAVAILABLE);
        }
    }

    private static boolean duration(Duration value, int seconds) {
        return !value.isNegative() && !value.isZero() && value.compareTo(Duration.ofSeconds(seconds)) <= 0;
    }

    private static boolean endpoint(URI value) {
        return ("http".equals(value.getScheme()) || "https".equals(value.getScheme())) && value.getHost() != null
                && value.getUserInfo() == null && value.getQuery() == null && value.getFragment() == null
                && ("https".equals(value.getScheme()) || Set.of("localhost", "127.0.0.1", "[::1]").contains(value.getHost()));
    }

    @Override public @NonNull String toString() { return "LarkProviderProperties[redacted]"; }
}
