package io.memoryos.connector;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.regex.Pattern;

public record GoogleDriveOAuthClient(String clientId, byte[] clientSecret) implements AutoCloseable {
    private static final Pattern CLIENT_ID = Pattern.compile("[A-Za-z0-9_-]{1,255}\\.apps\\.googleusercontent\\.com");

    public GoogleDriveOAuthClient {
        if (clientId == null || !CLIENT_ID.matcher(clientId).matches()
                || clientSecret == null || clientSecret.length < 1 || clientSecret.length > 4096) {
            throw GoogleDriveException.invalidOAuthClient();
        }
        for (byte value : clientSecret) {
            if (value < 33 || value > 126) throw GoogleDriveException.invalidOAuthClient();
        }
        clientSecret = clientSecret.clone();
    }

    public byte[] encode() {
        byte[] id = clientId.getBytes(StandardCharsets.US_ASCII);
        return ByteBuffer.allocate(4 + id.length + clientSecret.length).putInt(id.length).put(id).put(clientSecret).array();
    }

    public static GoogleDriveOAuthClient decode(byte[] bytes) {
        if (bytes.length < 5 || bytes.length > 4500) throw GoogleDriveException.invalidOAuthClient();
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        int length = buffer.getInt();
        if (length < 1 || length >= buffer.remaining()) throw GoogleDriveException.invalidOAuthClient();
        byte[] id = new byte[length];
        buffer.get(id);
        byte[] secret = new byte[buffer.remaining()];
        buffer.get(secret);
        try { return new GoogleDriveOAuthClient(new String(id, StandardCharsets.US_ASCII), secret); }
        finally { Arrays.fill(secret, (byte) 0); }
    }

    @Override public byte[] clientSecret() { return clientSecret.clone(); }
    @Override public void close() { Arrays.fill(clientSecret, (byte) 0); }
    @Override public String toString() { return "GoogleDriveOAuthClient[redacted]"; }
}
