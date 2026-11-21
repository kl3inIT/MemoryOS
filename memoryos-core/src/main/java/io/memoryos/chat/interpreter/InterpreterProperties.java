package io.memoryos.chat.interpreter;

import java.net.URI;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Deployment connection to the memoryos-interpreter service; an empty base URL means Code Interpreter is absent. */
@ConfigurationProperties("memoryos.chat.interpreter")
public record InterpreterProperties(@Nullable String baseUrl, @Nullable String apiKey) {
    public InterpreterProperties {
        baseUrl = baseUrl == null || baseUrl.isBlank() ? "" : baseUrl.strip().replaceAll("/+$", "");
        apiKey = apiKey == null ? "" : apiKey.strip();
        if (!baseUrl.isEmpty()) {
            URI uri;
            try { uri = URI.create(baseUrl); }
            catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Invalid interpreter base URL"); }
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
                throw new IllegalArgumentException("Invalid interpreter base URL");
        }
        if (apiKey.length() > 4096 || apiKey.chars().anyMatch(c -> c < 0x21 || c > 0x7e))
            throw new IllegalArgumentException("Invalid interpreter API key");
    }

    public boolean configured() { return !baseUrl().isEmpty(); }

    @Override public String baseUrl() { return baseUrl == null ? "" : baseUrl; }

    @Override public String apiKey() { return apiKey == null ? "" : apiKey; }

    @Override public @NonNull String toString() { return "InterpreterProperties[baseUrl=" + baseUrl + ", apiKey=redacted]"; }
}
