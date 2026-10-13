package io.memoryos.connector.lark.persistence;

import io.memoryos.connector.LarkException;
import io.memoryos.shared.CredentialCipher;
import java.util.Arrays;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Lark keeps its own encryption key, so a missing key fails Lark alone. */
@Component
public final class LarkCredentialConfiguration {
    static final String CREDENTIAL_KIND = "LARK_APP";

    private final String encodedKey;
    private final String keyVersion;
    private volatile CredentialCipher cipher;

    public LarkCredentialConfiguration(
            @Value("${memoryos.lark.credential-encryption-key:}") String encodedKey,
            @Value("${memoryos.lark.credential-key-version:}") String keyVersion) {
        this.encodedKey = encodedKey;
        this.keyVersion = keyVersion;
    }

    public CredentialCipher cipher() {
        var existing = cipher;
        if (existing != null) return existing;
        synchronized (this) {
            if (cipher == null) {
                byte[] key = null;
                try {
                    if (keyVersion.isBlank() || keyVersion.length() > 64) throw LarkException.notConfigured();
                    key = Base64.getDecoder().decode(encodedKey);
                    cipher = new CredentialCipher(key, keyVersion, CREDENTIAL_KIND);
                } catch (IllegalArgumentException exception) {
                    throw LarkException.notConfigured();
                } finally {
                    if (key != null) Arrays.fill(key, (byte) 0);
                }
            }
            return cipher;
        }
    }
}
