package io.memoryos.connector.sharepoint;

import io.memoryos.connector.SharePointException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

/**
 * Entra application certificate taken from a PKCS#12 upload. MemoryOS keeps the private key and the
 * certificate only; the uploaded PKCS#12 bytes and its password are never stored.
 */
public record SharePointCertificate(byte[] privateKey, byte[] certificate, String thumbprint, Instant notAfter)
        implements AutoCloseable {

    public static final int MAX_UPLOAD_BYTES = 16 * 1024;
    private static final int MIN_RSA_BITS = 2048;

    public SharePointCertificate {
        privateKey = Objects.requireNonNull(privateKey, "privateKey").clone();
        certificate = Objects.requireNonNull(certificate, "certificate").clone();
        Objects.requireNonNull(thumbprint, "thumbprint");
        Objects.requireNonNull(notAfter, "notAfter");
    }

    /**
     * Reads and validates a PKCS#12 keystore. The caller owns {@code pkcs12} and {@code password} and is
     * responsible for wiping them.
     */
    public static SharePointCertificate read(byte[] pkcs12, char[] password, Instant now) {
        Objects.requireNonNull(pkcs12, "pkcs12");
        Objects.requireNonNull(password, "password");
        if (pkcs12.length == 0 || pkcs12.length > MAX_UPLOAD_BYTES) {
            throw SharePointException.invalidCertificate(
                    "Upload a PKCS#12 (.pfx) file of at most 16 KiB.", "SharePoint certificate upload size is out of range");
        }
        KeyStore store;
        try {
            store = KeyStore.getInstance("PKCS12");
            store.load(new ByteArrayInputStream(pkcs12), password);
        } catch (GeneralSecurityException | IOException exception) {
            throw SharePointException.invalidCertificate(
                    "The file is not a PKCS#12 keystore, or the password is wrong.",
                    "SharePoint certificate upload could not be opened");
        }
        PrivateKey key = null;
        X509Certificate leaf = null;
        try {
            for (String alias : Collections.list(store.aliases())) {
                if (!store.isKeyEntry(alias)) continue;
                Key candidate = store.getKey(alias, password);
                Certificate chain = store.getCertificate(alias);
                if (!(candidate instanceof PrivateKey privateKey) || !(chain instanceof X509Certificate x509)) continue;
                if (key != null) {
                    throw SharePointException.invalidCertificate(
                            "The keystore must contain exactly one certificate and private key.",
                            "SharePoint certificate upload contains more than one key entry");
                }
                key = privateKey;
                leaf = x509;
            }
        } catch (GeneralSecurityException exception) {
            throw SharePointException.invalidCertificate(
                    "The file is not a PKCS#12 keystore, or the password is wrong.",
                    "SharePoint certificate key could not be read");
        }
        if (key == null || leaf == null) {
            throw SharePointException.invalidCertificate(
                    "The keystore must contain exactly one certificate and private key.",
                    "SharePoint certificate upload contains no usable key entry");
        }
        if (!(key instanceof RSAPrivateKey rsa) || rsa.getModulus().bitLength() < MIN_RSA_BITS) {
            throw SharePointException.invalidCertificate(
                    "Use an RSA certificate of at least 2048 bits.", "SharePoint certificate key is not RSA-2048 or stronger");
        }
        if (!"PKCS#8".equals(key.getFormat())) {
            throw SharePointException.invalidCertificate(
                    "The private key could not be read.", "SharePoint certificate key is not encodable as PKCS#8");
        }
        if (leaf.getNotAfter().toInstant().isBefore(Objects.requireNonNull(now, "now"))) {
            throw SharePointException.invalidCertificate(
                    "The certificate has expired. Upload a current certificate.", "SharePoint certificate has expired");
        }
        byte[] encoded = key.getEncoded();
        try {
            byte[] der = leaf.getEncoded();
            if (der.length == 0 || der.length > MAX_UPLOAD_BYTES) {
                throw SharePointException.invalidCertificate(
                        "The certificate is too large.", "SharePoint certificate DER size is out of range");
            }
            return new SharePointCertificate(encoded, der, thumbprint(der), leaf.getNotAfter().toInstant());
        } catch (CertificateEncodingException exception) {
            throw SharePointException.invalidCertificate(
                    "The certificate could not be read.", "SharePoint certificate could not be encoded");
        } finally {
            Arrays.fill(encoded, (byte) 0);
        }
    }

    private static String thumbprint(byte[] der) {
        try {
            return HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-1").digest(der));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-1 is required to compute a certificate thumbprint", exception);
        }
    }

    @Override public byte[] privateKey() { return privateKey.clone(); }

    @Override public byte[] certificate() { return certificate.clone(); }

    @Override public void close() { Arrays.fill(privateKey, (byte) 0); }

    @Override public @NonNull String toString() { return "SharePointCertificate[" + thumbprint + "]"; }
}
