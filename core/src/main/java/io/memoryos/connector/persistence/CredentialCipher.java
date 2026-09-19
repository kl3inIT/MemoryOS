package io.memoryos.connector.persistence;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Objects;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import io.memoryos.iam.tenant.TenantId;

/**
 * AES-GCM envelope shared by connector credential kinds. The credential kind belongs to the additional
 * authenticated data, so an envelope of one kind never decrypts under another kind's key.
 */
public final class CredentialCipher {

    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final int FORMAT_VERSION = 1;

    private final SecretKeySpec key;
    private final String keyVersion;
    private final String credentialKind;
    private final SecureRandom secureRandom;

    public CredentialCipher(byte[] key, String keyVersion, String credentialKind) {
        this(key, keyVersion, credentialKind, new SecureRandom());
    }

    CredentialCipher(byte[] key, String keyVersion, String credentialKind, SecureRandom secureRandom) {
        Objects.requireNonNull(key, "key must not be null");
        if (key.length != KEY_BYTES) {
            throw new IllegalArgumentException("Credential key must contain exactly 32 bytes");
        }
        this.key = new SecretKeySpec(key, "AES");
        this.keyVersion = requireText(keyVersion, "keyVersion");
        this.credentialKind = requireText(credentialKind, "credentialKind");
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom must not be null");
    }

    public EncryptedCredential encrypt(TenantId tenantId, UUID credentialId, String purpose, byte[] plaintext) {
        requireContext(tenantId, credentialId);
        Objects.requireNonNull(plaintext, "plaintext must not be null");
        if (plaintext.length == 0) {
            throw new IllegalArgumentException("plaintext must not be empty");
        }
        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(tenantId, credentialId, purpose));
            return new EncryptedCredential(cipher.doFinal(plaintext), nonce, keyVersion);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Credential encryption failed", exception);
        }
    }

    public byte[] decrypt(TenantId tenantId, UUID credentialId, String purpose, EncryptedCredential credential) {
        requireContext(tenantId, credentialId);
        Objects.requireNonNull(credential, "credential must not be null");
        if (!keyVersion.equals(credential.keyVersion())) {
            throw new IllegalStateException("Stored credential uses an unavailable key version");
        }
        try {
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, credential.nonce));
            cipher.updateAAD(aad(tenantId, credentialId, purpose));
            return cipher.doFinal(credential.ciphertext);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Stored credential could not be decrypted", exception);
        }
    }

    private byte[] aad(TenantId tenantId, UUID credentialId, String purpose) {
        return (FORMAT_VERSION + "|" + tenantId.value() + "|" + credentialId + "|" + credentialKind + "|" + keyVersion + "|" + purpose)
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

        @Override public String toString() { return "EncryptedCredential[redacted]"; }
    }
}
