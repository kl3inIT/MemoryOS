package io.memoryos.provider.google;

import java.net.URI;
import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("memoryos.google-drive")
public record GoogleDriveProviderProperties(
        URI tokenUri, URI driveApiBaseUrl,
        URI sheetsApiBaseUrl, URI docsApiBaseUrl, Duration connectTimeout,
        Duration requestTimeout, Duration acquisitionTimeout, int pageSize,
        int maxRequests, int maxTabs, int maxCells, int maxBinaryBytes, int maxSnapshotBytes) {
    public GoogleDriveProviderProperties {
        tokenUri = tokenUri == null ? URI.create("https://oauth2.googleapis.com/token") : tokenUri;
        driveApiBaseUrl = driveApiBaseUrl == null ? URI.create("https://www.googleapis.com/drive/v3") : driveApiBaseUrl;
        sheetsApiBaseUrl = sheetsApiBaseUrl == null ? URI.create("https://sheets.googleapis.com/v4") : sheetsApiBaseUrl;
        docsApiBaseUrl = docsApiBaseUrl == null ? URI.create("https://docs.googleapis.com/v1") : docsApiBaseUrl;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(3) : connectTimeout;
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(30) : requestTimeout;
        acquisitionTimeout = acquisitionTimeout == null ? Duration.ofSeconds(120) : acquisitionTimeout;
        pageSize = pageSize == 0 ? 100 : pageSize;
        maxRequests = maxRequests == 0 ? 256 : maxRequests;
        maxTabs = maxTabs == 0 ? 100 : maxTabs;
        maxCells = maxCells == 0 ? 200_000 : maxCells;
        maxBinaryBytes = maxBinaryBytes == 0 ? 10_485_760 : maxBinaryBytes;
        maxSnapshotBytes = maxSnapshotBytes == 0 ? 33_554_432 : maxSnapshotBytes;
    }

    // Validation is operation-local: absent Google settings must not prevent FILE startup.
    void validate() {
        if (!endpoint(tokenUri) || !endpoint(driveApiBaseUrl) || !endpoint(sheetsApiBaseUrl)
                || !endpoint(docsApiBaseUrl) || !duration(connectTimeout, 30)
                || !duration(requestTimeout, 120) || !duration(acquisitionTimeout, 120)
                || pageSize < 1 || pageSize > 1_000 || maxRequests < 1 || maxRequests > 256
                || maxTabs < 1 || maxTabs > 100 || maxCells < 1 || maxCells > 200_000
                || maxBinaryBytes < 1 || maxBinaryBytes > 10_485_760
                || maxSnapshotBytes < 1 || maxSnapshotBytes > 33_554_432) {
            throw new io.memoryos.connector.GoogleDriveProviderException(
                    io.memoryos.connector.GoogleDriveProviderException.Failure.UNAVAILABLE);
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

    @Override public String toString() { return "GoogleDriveProviderProperties[redacted]"; }
}
