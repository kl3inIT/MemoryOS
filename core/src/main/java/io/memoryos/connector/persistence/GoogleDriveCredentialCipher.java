package io.memoryos.connector.persistence;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Objects;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import io.memoryos.iam.TenantId;

public final class GoogleDriveCredentialCipher {

    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final String CREDENTIAL_KIND = "GOOGLE_OAUTH";
    private static final int FORMAT_VERSION = 1;

    private final SecretKeySpec key;
    private final String keyVersion;
    private final SecureRandom secureRandom;

    public GoogleDriveCredentialCipher(byte[] key, String keyVersion) {
        this(key, keyVersion, new SecureRandom());
    }

    GoogleDriveCredentialCipher(byte[] key, String keyVersion, SecureRandom secureRandom) {
        Objects.requireNonNull(key, "key must not be null");
        if (key.length != KEY_BYTES) {
            throw new IllegalArgumentException("Google Drive credential key must contain exactly 32 bytes");
        }
        this.key = new SecretKeySpec(key, "AES");
        this.keyVersion = requireText(keyVersion, "keyVersion");
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom must not be null");
    }

    public EncryptedCredential encrypt(TenantId tenantId, UUID credentialId, byte[] refreshToken) {
        return encrypt(tenantId, credentialId, "refresh-token", refreshToken);
    }

    public EncryptedCredential encrypt(TenantId tenantId, UUID credentialId, String purpose, byte[] refreshToken) {
        requireContext(tenantId, credentialId);
        Objects.requireNonNull(refreshToken, "refreshToken must not be null");
        if (refreshToken.length == 0) {
            throw new IllegalArgumentException("refreshToken must not be empty");
        }
        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(tenantId, credentialId, purpose));
            return new EncryptedCredential(cipher.doFinal(refreshToken), nonce, keyVersion);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Google Drive credential encryption failed", exception);
        }
    }

    public byte[] decrypt(TenantId tenantId, UUID credentialId, EncryptedCredential credential) {
        return decrypt(tenantId, credentialId, "refresh-token", credential);
    }

    public byte[] decrypt(TenantId tenantId, UUID credentialId, String purpose, EncryptedCredential credential) {
        requireContext(tenantId, credentialId);
        Objects.requireNonNull(credential, "credential must not be null");
        if (!keyVersion.equals(credential.keyVersion())) {
            throw new IllegalStateException("Stored Google Drive credential uses an unavailable key version");
        }
        try {
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, credential.nonce));
            cipher.updateAAD(aad(tenantId, credentialId, purpose));
            return cipher.doFinal(credential.ciphertext);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Stored Google Drive credential could not be decrypted", exception);
        }
    }

    private byte[] aad(TenantId tenantId, UUID credentialId, String purpose) {
        return (FORMAT_VERSION + "|" + tenantId.value() + "|" + credentialId + "|" + CREDENTIAL_KIND + "|" + keyVersion + "|" + purpose)
                .getBytes(StandardCharsets.UTF_8);
    }

    private static void requireContext(TenantId tenantId, UUID credentialId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(credentialId, "credentialId must not be null");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    public record EncryptedCredential(byte[] ciphertext, byte[] nonce, String keyVersion) {
        public EncryptedCredential {
            ciphertext = Objects.requireNonNull(ciphertext, "ciphertext must not be null").clone();
            nonce = Objects.requireNonNull(nonce, "nonce must not be null").clone();
            if (nonce.length != NONCE_BYTES) {
                throw new IllegalArgumentException("nonce must contain exactly 12 bytes");
            }
            keyVersion = requireText(keyVersion, "keyVersion");
        }

        @Override
        public byte[] ciphertext() {
            return ciphertext.clone();
        }

        @Override
        public byte[] nonce() {
            return nonce.clone();
        }

        @Override public String toString() { return "EncryptedGoogleCredential[redacted]"; }
    }
}
