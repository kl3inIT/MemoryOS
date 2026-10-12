package io.memoryos.connector.sharepoint;

import static org.junit.jupiter.api.Assertions.*;

import io.memoryos.connector.SharePointException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class SharePointCertificateTest {
    private static final char[] PASSWORD = "changeit".toCharArray();
    private static final Instant NOW = Instant.parse("2026-09-16T00:00:00Z");

    @Test
    void readsPrivateKeyCertificateAndThumbprint() throws Exception {
        try (var material = SharePointCertificate.read(fixture("rsa2048"), PASSWORD.clone(), NOW)) {
            assertTrue(material.thumbprint().matches("[0-9A-F]{40}"), material.thumbprint());
            assertTrue(material.notAfter().isAfter(NOW));
            assertNotNull(KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(material.privateKey())));
            var certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(material.certificate()));
            assertEquals(material.notAfter(), certificate.getNotAfter().toInstant());
            assertEquals(material.thumbprint(), HexFormat.of().withUpperCase()
                    .formatHex(MessageDigest.getInstance("SHA-1").digest(certificate.getEncoded())));
            assertFalse(material.toString().contains("privateKey"));
        }
    }

    @Test
    void rejectsWrongPassword() {
        assertInvalid(() -> SharePointCertificate.read(fixture("rsa2048"), "wrong".toCharArray(), NOW));
    }

    @Test
    void rejectsKeysWeakerThanRsa2048() {
        assertInvalid(() -> SharePointCertificate.read(fixture("rsa1024"), PASSWORD.clone(), NOW));
    }

    @Test
    void rejectsExpiredCertificates() throws Exception {
        Instant notAfter;
        try (var material = SharePointCertificate.read(fixture("rsa2048"), PASSWORD.clone(), NOW)) {
            notAfter = material.notAfter();
        }
        Instant afterExpiry = notAfter.plus(Duration.ofDays(1));
        assertInvalid(() -> SharePointCertificate.read(fixture("rsa2048"), PASSWORD.clone(), afterExpiry));
    }

    @Test
    void rejectsKeystoresWithMoreThanOneKey() throws Exception {
        assertInvalid(() -> SharePointCertificate.read(twoKeyStore(), PASSWORD.clone(), NOW));
    }

    @Test
    void rejectsKeystoresWithoutAnyKey() throws Exception {
        var store = KeyStore.getInstance("PKCS12");
        store.load(null, PASSWORD.clone());
        store.setCertificateEntry("only-certificate", certificateOf("rsa2048"));
        assertInvalid(() -> SharePointCertificate.read(bytes(store), PASSWORD.clone(), NOW));
    }

    @Test
    void rejectsUploadsThatAreNotKeystores() {
        byte[] random = "not a keystore".getBytes(StandardCharsets.UTF_8);
        assertInvalid(() -> SharePointCertificate.read(random, PASSWORD.clone(), NOW));
    }

    @Test
    void rejectsEmptyAndOversizedUploads() {
        assertInvalid(() -> SharePointCertificate.read(new byte[0], PASSWORD.clone(), NOW));
        assertInvalid(() -> SharePointCertificate.read(new byte[SharePointCertificate.MAX_UPLOAD_BYTES + 1], PASSWORD.clone(), NOW));
    }

    private static void assertInvalid(Executable call) {
        var exception = assertThrows(SharePointException.class, call::execute);
        assertEquals("SOURCE_SHAREPOINT_CERTIFICATE_INVALID", exception.code());
    }

    private interface Executable {
        void execute() throws Exception;
    }

    private static byte[] twoKeyStore() throws Exception {
        var combined = KeyStore.getInstance("PKCS12");
        combined.load(null, PASSWORD.clone());
        for (String fixture : new String[] {"rsa2048", "rsa1024"}) {
            var source = KeyStore.getInstance("PKCS12");
            source.load(new ByteArrayInputStream(fixture(fixture)), PASSWORD.clone());
            String alias = Objects.requireNonNull(source.aliases().nextElement());
            combined.setKeyEntry(fixture, source.getKey(alias, PASSWORD.clone()), PASSWORD.clone(),
                    source.getCertificateChain(alias));
        }
        return bytes(combined);
    }

    private static X509Certificate certificateOf(String fixture) throws Exception {
        var store = KeyStore.getInstance("PKCS12");
        store.load(new ByteArrayInputStream(fixture(fixture)), PASSWORD.clone());
        return (X509Certificate) store.getCertificate(store.aliases().nextElement());
    }

    private static byte[] bytes(KeyStore store) throws Exception {
        var output = new ByteArrayOutputStream();
        store.store(output, PASSWORD.clone());
        return output.toByteArray();
    }

    private static byte[] fixture(String name) {
        try (var stream = SharePointCertificateTest.class.getResourceAsStream("/sharepoint/" + name + ".pfx.base64")) {
            return Base64.getDecoder().decode(new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8).strip());
        } catch (IOException exception) {
            throw new IllegalStateException("missing certificate fixture " + name, exception);
        }
    }
}
