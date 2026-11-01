package io.memoryos.mcp;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.encrypt.AesGcmBytesEncryptor;
import org.springframework.stereotype.Component;

/**
 * Spring Security AES-GCM for MCP secrets. The encryptor has no associated-data API, so the plaintext starts with
 * its Tenant, record and purpose and decryption verifies that prefix: a ciphertext copied to another row fails
 * closed. The master key stays in deployment secret management.
 */
@Component
public final class McpSecrets {
    private static final String FORMAT = "v1";
    private static final int MAX_VALUE_CHARACTERS = 65536;

    public enum Purpose { SERVER_HEADERS, OAUTH_CLIENT_SECRET, REGISTRATION_ACCESS_TOKEN, CREDENTIAL }

    private final @Nullable AesGcmBytesEncryptor encryptor;

    public McpSecrets(@Value("${memoryos.mcp.credential-encryption-key:}") String masterKey) {
        if (masterKey.isBlank()) {
            encryptor = null;
            return;
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(masterKey);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Invalid MCP credential encryption key");
        }
        try {
            if (key.length != 32) throw new IllegalArgumentException("MCP credential encryption key must encode 32 bytes");
            encryptor = AesGcmBytesEncryptor.withSecretKey(new SecretKeySpec(key, "AES")).build();
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    public boolean configured() {
        return encryptor != null;
    }

    public String seal(UUID tenant, UUID record, Purpose purpose, String value) {
        if (value.isEmpty() || value.length() > MAX_VALUE_CHARACTERS) {
            throw new IllegalArgumentException("MCP secret must contain 1 to 65536 characters");
        }
        byte[] plain = (binding(tenant, record, purpose) + value).getBytes(StandardCharsets.UTF_8);
        try {
            return FORMAT + ":" + HexFormat.of().formatHex(requireEncryptor().encrypt(plain));
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    public String open(UUID tenant, UUID record, Purpose purpose, String sealed) {
        var encryptor = requireEncryptor();
        byte[] plain = null;
        try {
            var parts = sealed.split(":", 2);
            if (parts.length != 2 || !FORMAT.equals(parts[0])) throw McpException.credentialUnreadable();
            plain = encryptor.decrypt(HexFormat.of().parseHex(parts[1]));
            String text = new String(plain, StandardCharsets.UTF_8);
            String prefix = binding(tenant, record, purpose);
            if (!text.startsWith(prefix)) throw McpException.credentialUnreadable();
            return text.substring(prefix.length());
        } catch (McpException unreadable) {
            throw unreadable;
        } catch (RuntimeException invalid) {
            throw McpException.credentialUnreadable();
        } finally {
            if (plain != null) Arrays.fill(plain, (byte) 0);
        }
    }

    private AesGcmBytesEncryptor requireEncryptor() {
        if (encryptor == null) throw McpException.notConfigured();
        return encryptor;
    }

    private static String binding(UUID tenant, UUID record, Purpose purpose) {
        return tenant + "/" + record + "/" + purpose + "/";
    }
}
