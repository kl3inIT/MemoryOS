package io.memoryos.connector.persistence;

import static org.junit.jupiter.api.Assertions.*;

import io.memoryos.iam.tenant.TenantId;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class CredentialCipherTest {
    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final String GOOGLE = "GOOGLE_OAUTH";
    private static final String SHAREPOINT = "SHAREPOINT_APP";
    private static final String REFRESH_TOKEN = "refresh-token";

    @Test
    void encryptionBindsTenantCredentialAndKeyVersionAndRejectsTampering() {
        var cipher = new CredentialCipher(KEY, "v1", GOOGLE);
        var tenant = new TenantId(UUID.randomUUID());
        UUID credential = UUID.randomUUID();
        byte[] secret = "refresh-secret".getBytes(StandardCharsets.UTF_8);
        var first = cipher.encrypt(tenant, credential, REFRESH_TOKEN, secret);
        var second = cipher.encrypt(tenant, credential, REFRESH_TOKEN, secret);
        assertArrayEquals(secret, cipher.decrypt(tenant, credential, REFRESH_TOKEN, first));
        assertFalse(Arrays.equals(first.nonce(), second.nonce()));
        assertThrows(IllegalStateException.class,
                () -> cipher.decrypt(new TenantId(UUID.randomUUID()), credential, REFRESH_TOKEN, first));
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(tenant, UUID.randomUUID(), REFRESH_TOKEN, first));
        var changedVersion = new CredentialCipher.EncryptedCredential(first.ciphertext(), first.nonce(), "v2");
        assertThrows(IllegalStateException.class,
                () -> new CredentialCipher(KEY, "v2", GOOGLE).decrypt(tenant, credential, REFRESH_TOKEN, changedVersion));
        byte[] corrupted = first.ciphertext();
        corrupted[0] ^= 1;
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(tenant, credential, REFRESH_TOKEN,
                new CredentialCipher.EncryptedCredential(corrupted, first.nonce(), "v1")));
    }

    @Test
    void appAndRefreshEnvelopesCannotBeSubstituted() {
        var cipher = new CredentialCipher(KEY, "v1", GOOGLE);
        var tenant = new TenantId(UUID.randomUUID());
        UUID credential = UUID.randomUUID();
        byte[] secret = "app-secret".getBytes(StandardCharsets.UTF_8);
        var encrypted = cipher.encrypt(tenant, credential, "oauth-client", secret);
        assertArrayEquals(secret, cipher.decrypt(tenant, credential, "oauth-client", encrypted));
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(tenant, credential, REFRESH_TOKEN, encrypted));
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(tenant, UUID.randomUUID(), "oauth-client", encrypted));
    }

    @Test
    void credentialKindsCannotBeSubstitutedUnderTheSameKey() {
        var tenant = new TenantId(UUID.randomUUID());
        UUID credential = UUID.randomUUID();
        byte[] secret = "entra-client-secret".getBytes(StandardCharsets.UTF_8);
        var sharePoint = new CredentialCipher(KEY, "v1", SHAREPOINT).encrypt(tenant, credential, "client-secret", secret);
        assertThrows(IllegalStateException.class,
                () -> new CredentialCipher(KEY, "v1", GOOGLE).decrypt(tenant, credential, "client-secret", sharePoint));
        assertArrayEquals(secret,
                new CredentialCipher(KEY, "v1", SHAREPOINT).decrypt(tenant, credential, "client-secret", sharePoint));
    }

    @Test
    void googleEnvelopesWrittenBeforeTheSharedCipherStayReadable() throws Exception {
        var tenant = new TenantId(UUID.randomUUID());
        UUID credential = UUID.randomUUID();
        byte[] secret = "stored-refresh-token".getBytes(StandardCharsets.UTF_8);
        byte[] nonce = new byte[12];
        Arrays.fill(nonce, (byte) 7);
        // The additional authenticated data GoogleDriveCredentialCipher wrote before the cipher was shared.
        byte[] aad = ("1|" + tenant.value() + "|" + credential + "|GOOGLE_OAUTH|v1|" + REFRESH_TOKEN)
                .getBytes(StandardCharsets.UTF_8);
        Cipher legacy = Cipher.getInstance("AES/GCM/NoPadding");
        legacy.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(KEY, "AES"), new GCMParameterSpec(128, nonce));
        legacy.updateAAD(aad);
        var stored = new CredentialCipher.EncryptedCredential(legacy.doFinal(secret), nonce, "v1");

        assertArrayEquals(secret, new CredentialCipher(KEY, "v1", GOOGLE).decrypt(tenant, credential, REFRESH_TOKEN, stored));
    }
}
