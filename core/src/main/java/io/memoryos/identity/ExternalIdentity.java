package io.memoryos.identity;

import java.util.Objects;

public record ExternalIdentity(String issuer, String subject) {

    public ExternalIdentity {
        requireText(issuer, "issuer");
        requireText(subject, "subject");
    }

    private static void requireText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
