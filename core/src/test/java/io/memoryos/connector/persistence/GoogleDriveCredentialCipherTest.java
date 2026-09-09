package io.memoryos.connector.persistence;

import static org.junit.jupiter.api.Assertions.*;
import io.memoryos.iam.TenantId;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GoogleDriveCredentialCipherTest {
    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    @Test
    void encryptionBindsTenantCredentialAndKeyVersionAndRejectsTampering() {
        var cipher = new GoogleDriveCredentialCipher(KEY, "v1");
        var tenant = new TenantId(UUID.randomUUID());
        UUID credential = UUID.randomUUID();
        byte[] secret = "refresh-secret".getBytes(StandardCharsets.UTF_8);
        var first = cipher.encrypt(tenant, credential, secret);
        var second = cipher.encrypt(tenant, credential, secret);
        assertArrayEquals(secret, cipher.decrypt(tenant, credential, first));
        assertFalse(Arrays.equals(first.nonce(), second.nonce()));
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(new TenantId(UUID.randomUUID()), credential, first));
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(tenant, UUID.randomUUID(), first));
        var changedVersion = new GoogleDriveCredentialCipher.EncryptedCredential(first.ciphertext(), first.nonce(), "v2");
        assertThrows(IllegalStateException.class, () -> new GoogleDriveCredentialCipher(KEY, "v2").decrypt(tenant, credential, changedVersion));
        byte[] corrupted = first.ciphertext(); corrupted[0] ^= 1;
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(tenant, credential,
                new GoogleDriveCredentialCipher.EncryptedCredential(corrupted, first.nonce(), "v1")));
    }

    @Test
    void appAndRefreshEnvelopesCannotBeSubstituted() {
        var cipher = new GoogleDriveCredentialCipher(KEY, "v1");
        var tenant = new TenantId(UUID.randomUUID());
        UUID credential = UUID.randomUUID();
        byte[] secret = "app-secret".getBytes(StandardCharsets.UTF_8);
        var encrypted = cipher.encrypt(tenant, credential, "oauth-client", secret);
        assertArrayEquals(secret, cipher.decrypt(tenant, credential, "oauth-client", encrypted));
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(tenant, credential, encrypted));
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(tenant, UUID.randomUUID(), "oauth-client", encrypted));
    }
}
