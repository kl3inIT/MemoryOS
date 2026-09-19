package io.memoryos.connector.persistence;

import io.memoryos.connector.GoogleDriveException;
import java.util.Arrays;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class GoogleDriveCredentialConfiguration {
    private final String encodedKey;
    private final String keyVersion;
    private volatile CredentialCipher cipher;

    public GoogleDriveCredentialConfiguration(
            @Value("${memoryos.google-drive.credential-encryption-key:}") String encodedKey,
            @Value("${memoryos.google-drive.credential-key-version:}") String keyVersion) {
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
                    if (keyVersion.isBlank() || keyVersion.length() > 64) throw GoogleDriveException.notConfigured();
                    key = Base64.getDecoder().decode(encodedKey);
                    cipher = new CredentialCipher(key, keyVersion, "GOOGLE_OAUTH");
                } catch (IllegalArgumentException exception) {
                    throw GoogleDriveException.notConfigured();
                } finally {
                    if (key != null) Arrays.fill(key, (byte) 0);
                }
            }
            return cipher;
        }
    }
}
