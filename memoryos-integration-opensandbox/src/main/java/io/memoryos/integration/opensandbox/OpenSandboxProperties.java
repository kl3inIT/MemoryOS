package io.memoryos.integration.opensandbox;

import java.net.URI;
import java.time.Duration;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where the OpenSandbox server is. An empty {@code endpoint} leaves the runtime out.
 *
 * @param endpoint the server's base URL, {@code http://memoryos-sandbox:8090} in Compose
 * @param apiKey the server's key; the launcher reads it from {@code MEMORYOS_SANDBOX_API_KEY_FILE}
 * @param requestTimeout one request's deadline: above the longest command (120 s) and a sandbox's start (60 s)
 * @param readyTimeout how long a new sandbox may take to become ready; kept under 90 s so a start always ends
 *     before the Worker's two-minute grace for sandboxes no row records yet
 */
@ConfigurationProperties("memoryos.sandbox.opensandbox")
public record OpenSandboxProperties(@Nullable String endpoint, @Nullable String apiKey,
                                    @DefaultValue("180s") Duration requestTimeout,
                                    @DefaultValue("60s") Duration readyTimeout) {
    /** Under {@code SandboxReconciliation.GRACE} (2 minutes), with room for the request that records the start. */
    static final Duration MAX_READY_TIMEOUT = Duration.ofSeconds(90);

    public OpenSandboxProperties {
        endpoint = endpoint == null ? "" : endpoint.strip().replaceAll("/+$", "");
        apiKey = apiKey == null ? "" : apiKey.strip();
        if (readyTimeout.isNegative() || readyTimeout.isZero() || readyTimeout.compareTo(MAX_READY_TIMEOUT) > 0)
            throw new IllegalArgumentException("The OpenSandbox ready timeout must be positive and at most " + MAX_READY_TIMEOUT);
        if (!endpoint.isEmpty()) {
            URI uri;
            try { uri = URI.create(endpoint); }
            catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Invalid OpenSandbox endpoint"); }
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || !(uri.getPath() == null || uri.getPath().isEmpty()))
                throw new IllegalArgumentException("Invalid OpenSandbox endpoint");
            if (apiKey.isEmpty()) throw new IllegalArgumentException("The OpenSandbox endpoint needs an API key");
        }
    }

    @Override
    public String endpoint() {
        return endpoint == null ? "" : endpoint;
    }

    @Override
    public String apiKey() {
        return apiKey == null ? "" : apiKey;
    }

    @Override
    public @NonNull String toString() {
        return "OpenSandboxProperties[endpoint=" + endpoint + ", apiKey=redacted, requestTimeout=" + requestTimeout
                + ", readyTimeout=" + readyTimeout + "]";
    }
}
