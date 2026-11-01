package io.memoryos.objectstorage;

import java.util.Objects;
import java.util.regex.Pattern;

public record ObjectKey(String value) {
    private static final int MAX_LENGTH = 240;
    private static final Pattern SAFE_KEY =
            Pattern.compile("[a-z][a-z0-9-]{0,31}(?:/[A-Za-z0-9][A-Za-z0-9._-]{0,127})+");

    public ObjectKey {
        Objects.requireNonNull(value, "value must not be null");
        if (value.length() > MAX_LENGTH || !SAFE_KEY.matcher(value).matches()) {
            throw new IllegalArgumentException("value must be a safe server-managed object key");
        }
    }
}
