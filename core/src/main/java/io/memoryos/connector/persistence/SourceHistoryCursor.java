package io.memoryos.connector.persistence;

import io.memoryos.connector.SourceException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.jspecify.annotations.Nullable;

final class SourceHistoryCursor {
    private SourceHistoryCursor() {}

    static String encode(String scope, String position) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((scope + position).getBytes(StandardCharsets.UTF_8));
    }

    static @Nullable String decode(@Nullable String token, String scope) {
        if (token == null) return null;
        try {
            if (token.length() > 1024) throw new IllegalArgumentException();
            String decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            if (!decoded.startsWith(scope)) throw new IllegalArgumentException();
            return decoded.substring(scope.length());
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    static SourceException invalid() {
        return SourceException.invalid("The history cursor is invalid. Reload the list.", "invalid or mismatched source history cursor");
    }
}
