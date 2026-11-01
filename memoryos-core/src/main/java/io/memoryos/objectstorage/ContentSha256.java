package io.memoryos.objectstorage;

import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

public record ContentSha256(String value) {
    private static final Pattern LOWERCASE_SHA256 = Pattern.compile("[0-9a-f]{64}");

    public ContentSha256 {
        Objects.requireNonNull(value, "value must not be null");
        if (!LOWERCASE_SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException("value must be a lowercase SHA-256 digest");
        }
    }

    public static ContentSha256 fromBase64(String value) {
        Objects.requireNonNull(value, "value must not be null");
        byte[] bytes = Base64.getDecoder().decode(value);
        if (bytes.length != 32) {
            throw new IllegalArgumentException("value must encode a SHA-256 digest");
        }
        return new ContentSha256(HexFormat.of().formatHex(bytes));
    }

    public String base64() {
        return Base64.getEncoder().encodeToString(HexFormat.of().parseHex(value));
    }
}
