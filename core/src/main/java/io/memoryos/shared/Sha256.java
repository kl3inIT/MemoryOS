package io.memoryos.shared;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256, which every JVM provides; its absence is a broken runtime, not a condition a caller handles. */
public final class Sha256 {
    private Sha256() {}

    public static byte[] digest(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    /** The lowercase hex digest of the bytes. */
    public static String hex(byte[] value) {
        return HexFormat.of().formatHex(digest(value));
    }

    /** The lowercase hex digest of the text's UTF-8 bytes. */
    public static String hex(String value) {
        return hex(value.getBytes(StandardCharsets.UTF_8));
    }
}
