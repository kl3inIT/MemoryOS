package io.memoryos.retrieval.settings;

import io.memoryos.retrieval.SearchUnavailableException;
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
 * Embedding provider keys in the Chat catalog's scheme and with its key (docs/specs/chat-models.md): Spring
 * Security AES-GCM, {@code v1:} hex ciphertext bound to the Tenant and provider IDs, master key outside the database.
 * Retrieval may not depend on Chat, so the scheme is repeated here rather than shared. Every key is one an
 * administrator entered: the deployment holds none (MEM-216), as Chat holds none since MEM-211.
 */
@Component
public final class EmbeddingProviderCredentials {
    private final @Nullable AesGcmBytesEncryptor encryptor;

    public EmbeddingProviderCredentials(@Value("${memoryos.search.provider-encryption-key:}") String masterKey) {
        if (masterKey.isBlank()) this.encryptor = null;
        else {
            byte[] key;
            try { key = Base64.getDecoder().decode(masterKey); }
            catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Invalid catalog encryption key"); }
            if (key.length != 32) throw new IllegalArgumentException("Catalog encryption key must encode 32 bytes");
            this.encryptor = AesGcmBytesEncryptor.withSecretKey(new SecretKeySpec(key, "AES")).build();
            Arrays.fill(key, (byte) 0);
        }
    }

    /** Ciphertext for a key an administrator enters; bound to the Tenant and provider it was entered for. */
    public String seal(UUID tenant, UUID provider, String key) {
        if (key == null || key.isBlank() || key.length() > 8192) throw new IllegalArgumentException("invalid provider key");
        if (encryptor == null) throw new IllegalStateException("catalog encryption key is not configured");
        byte[] plain = (tenant + "/" + provider + "/" + key).getBytes(StandardCharsets.UTF_8);
        try { return "v1:" + HexFormat.of().formatHex(encryptor.encrypt(plain)); }
        finally { Arrays.fill(plain, (byte) 0); }
    }

    /** Whether a key is stored for the provider. */
    public boolean present(@Nullable String stored) {
        return stored != null;
    }

    /** The key to send, empty when none is stored; an unreadable credential makes search unavailable. */
    public String resolve(UUID tenant, UUID provider, @Nullable String stored) {
        if (stored == null) return "";
        try {
            var parts = stored.split(":", 2);
            if (parts.length != 2 || !"v1".equals(parts[0]) || encryptor == null) throw new IllegalStateException();
            var plain = new String(encryptor.decrypt(HexFormat.of().parseHex(parts[1])), StandardCharsets.UTF_8);
            String prefix = tenant + "/" + provider + "/";
            if (!plain.startsWith(prefix)) throw new IllegalStateException();
            return plain.substring(prefix.length());
        } catch (RuntimeException unreadable) {
            throw new SearchUnavailableException();
        }
    }
}
