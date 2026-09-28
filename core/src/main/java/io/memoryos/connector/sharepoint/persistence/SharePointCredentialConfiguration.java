package io.memoryos.connector.sharepoint.persistence;

import io.memoryos.connector.SharePointException;
import io.memoryos.connector.source.persistence.CredentialCipher;
import java.util.Arrays;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** SharePoint keeps its own encryption key, so a missing key fails SharePoint alone. */
@Component
public final class SharePointCredentialConfiguration {
    static final String CREDENTIAL_KIND = "SHAREPOINT_APP";

    private final String encodedKey;
    private final String keyVersion;
    private volatile CredentialCipher cipher;

    public SharePointCredentialConfiguration(
            @Value("${memoryos.sharepoint.credential-encryption-key:}") String encodedKey,
            @Value("${memoryos.sharepoint.credential-key-version:}") String keyVersion) {
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
                    if (keyVersion.isBlank() || keyVersion.length() > 64) throw SharePointException.notConfigured();
                    key = Base64.getDecoder().decode(encodedKey);
                    cipher = new CredentialCipher(key, keyVersion, CREDENTIAL_KIND);
                } catch (IllegalArgumentException exception) {
                    throw SharePointException.notConfigured();
                } finally {
                    if (key != null) Arrays.fill(key, (byte) 0);
                }
            }
            return cipher;
        }
    }
}
