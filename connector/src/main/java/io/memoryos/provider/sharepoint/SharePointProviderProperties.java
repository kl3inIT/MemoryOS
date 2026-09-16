package io.memoryos.provider.sharepoint;

import io.memoryos.connector.SharePointProviderException;
import java.net.URI;
import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("memoryos.sharepoint")
public record SharePointProviderProperties(
        URI authority, URI graphBaseUrl, Duration connectTimeout, Duration requestTimeout,
        Duration acquisitionTimeout, int maxRequests, int maxResponseBytes, String userAgent) {

    public SharePointProviderProperties {
        authority = authority == null ? URI.create("https://login.microsoftonline.com") : authority;
        graphBaseUrl = graphBaseUrl == null ? URI.create("https://graph.microsoft.com/v1.0") : graphBaseUrl;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(3) : connectTimeout;
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(30) : requestTimeout;
        acquisitionTimeout = acquisitionTimeout == null ? Duration.ofSeconds(15) : acquisitionTimeout;
        maxRequests = maxRequests == 0 ? 64 : maxRequests;
        maxResponseBytes = maxResponseBytes == 0 ? 1_048_576 : maxResponseBytes;
        // Microsoft asks integrators to identify themselves; the format is theirs.
        userAgent = userAgent == null || userAgent.isBlank() ? "ISV|MemoryOS|SharePointConnector/1.0" : userAgent;
    }

    // Validation is operation-local: absent SharePoint settings must not prevent FILE or Drive startup.
    void validate() {
        if (!endpoint(authority) || !endpoint(graphBaseUrl) || !duration(connectTimeout, 30)
                || !duration(requestTimeout, 120) || !duration(acquisitionTimeout, 120)
                || maxRequests < 1 || maxRequests > 256
                || maxResponseBytes < 1024 || maxResponseBytes > 16_777_216
                || userAgent.length() > 200) {
            throw new SharePointProviderException(SharePointProviderException.Failure.UNAVAILABLE);
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

    @Override public String toString() { return "SharePointProviderProperties[redacted]"; }
}
