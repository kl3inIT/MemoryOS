package io.memoryos.chat.catalog;

import io.memoryos.chat.ChatException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.encrypt.AesGcmBytesEncryptor;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** Spring Security AES-GCM; master key stays in deployment secret management, not the database. */
@Component
public final class ProviderCredentials {
    public static final String DEPLOYMENT = "deployment";
    private final @Nullable AesGcmBytesEncryptor encryptor;
    private final String deploymentKey;
    public ProviderCredentials(@Value("${memoryos.chat.catalog.encryption-key:}") String masterKey,
                               @Value("${memoryos.chat.provider.api-key:}") String deploymentKey) {
        if (masterKey.isBlank()) this.encryptor = null;
        else {
            byte[] key;
            try { key = Base64.getDecoder().decode(masterKey); }
            catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Invalid catalog encryption key"); }
            if (key.length != 32) throw new IllegalArgumentException("Catalog encryption key must encode 32 bytes");
            this.encryptor = AesGcmBytesEncryptor.withSecretKey(new SecretKeySpec(key, "AES")).build();
            java.util.Arrays.fill(key, (byte) 0);
        }
        this.deploymentKey = deploymentKey;
    }

    public enum Action { KEEP, REPLACE, REMOVE }
    public record Change(Action action, @Nullable String value) {
        @Override public @NonNull String toString() { return "CredentialChange[redacted]"; }
    }

    public @Nullable String update(UUID tenant, UUID provider, @Nullable String previous, Change change) {
        if (change == null || change.action() == null) throw ChatException.invalid("Credential action is required.");
        if (change.action() != Action.REPLACE && change.value() != null)
            throw ChatException.invalid("Only credential replacement accepts a value.");
        return switch (change.action()) {
            case KEEP -> previous;
            case REMOVE -> null;
            case REPLACE -> {
                if (change.value() == null || change.value().isBlank() || change.value().length() > 8192)
                    throw ChatException.invalid("Invalid provider credential.");
                byte[] plain = (tenant + "/" + provider + "/" + change.value()).getBytes(StandardCharsets.UTF_8);
                byte[] encrypted = requireEncryptor().encrypt(plain);
                java.util.Arrays.fill(plain, (byte) 0);
                yield "v1:" + HexFormat.of().formatHex(encrypted);
            }
        };
    }

    public boolean configured(@Nullable String stored) {
        return stored != null && (!DEPLOYMENT.equals(stored) || !deploymentKey.isBlank());
    }

    public String resolve(UUID tenant, UUID provider, @Nullable String stored) {
        if (stored == null) return "";
        if (DEPLOYMENT.equals(stored)) return deploymentKey;
        try {
            var parts = stored.split(":", 2);
            if (parts.length != 2 || !"v1".equals(parts[0])) throw ChatException.providerUnavailable();
            var plain = new String(requireEncryptor().decrypt(HexFormat.of().parseHex(parts[1])), StandardCharsets.UTF_8);
            String prefix = tenant + "/" + provider + "/";
            if (!plain.startsWith(prefix)) throw ChatException.providerUnavailable();
            return plain.substring(prefix.length());
        } catch (RuntimeException invalid) { throw ChatException.providerUnavailable(); }
    }

    private AesGcmBytesEncryptor requireEncryptor() {
        if (encryptor == null) throw ChatException.providerUnavailable();
        return encryptor;
    }
}
