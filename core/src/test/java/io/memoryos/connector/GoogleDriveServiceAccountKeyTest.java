package io.memoryos.connector;

import static org.junit.jupiter.api.Assertions.*;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class GoogleDriveServiceAccountKeyTest {
    private static final String CLIENT_EMAIL = "indexer@memoryos-prod.iam.gserviceaccount.com";

    @Test
    void readsTheDownloadedKeyAndRoundTripsItsStoredForm() throws Exception {
        try (var key = GoogleDriveServiceAccountKey.parse(json(pem(2048), "\"token_uri\":\"https://oauth2.googleapis.com/token\","))) {
            assertEquals(CLIENT_EMAIL, key.clientEmail());
            assertEquals("104512345678901234567", key.clientId());
            assertEquals("3f2a9c", key.privateKeyId());
            assertInstanceOf(RSAPrivateKey.class, key.privateKey());
            assertFalse(key.toString().contains("PRIVATE"));
            try (var stored = GoogleDriveServiceAccountKey.decode(key.encode())) {
                assertEquals(key.clientEmail(), stored.clientEmail());
                assertEquals(key.privateKey(), stored.privateKey());
            }
        }
    }

    @Test
    void rejectsKeysThatAreNotServiceAccountKeys() throws Exception {
        String pem = pem(2048);
        assertInvalid(() -> GoogleDriveServiceAccountKey.parse(json(pem, "").replace("service_account", "authorized_user")));
        assertInvalid(() -> GoogleDriveServiceAccountKey.parse(json(pem, "").replace(CLIENT_EMAIL, "someone@example.com")));
        assertInvalid(() -> GoogleDriveServiceAccountKey.parse(json("not a key", "")));
        assertInvalid(() -> GoogleDriveServiceAccountKey.parse(json(pem(1024), "")));
    }

    @Test
    void rejectsUntrustedEndpointsAndAmbiguousJson() throws Exception {
        String pem = pem(2048);
        assertInvalid(() -> GoogleDriveServiceAccountKey.parse(json(pem, "\"token_uri\":\"https://attacker.example/token\",")));
        assertInvalid(() -> GoogleDriveServiceAccountKey.parse(json(pem, "\"universe_domain\":\"attacker.example\",")));
        assertInvalid(() -> GoogleDriveServiceAccountKey.parse(json(pem, "\"type\":\"service_account\",")));
        assertInvalid(() -> GoogleDriveServiceAccountKey.parse(json(pem, "") + "{}"));
        assertInvalid(() -> GoogleDriveServiceAccountKey.parse("[]"));
        assertInvalid(() -> GoogleDriveServiceAccountKey.parse(" "));
        assertInvalid(() -> GoogleDriveServiceAccountKey.parse("x".repeat(GoogleDriveServiceAccountKey.MAX_JSON_BYTES + 1)));
    }

    private static void assertInvalid(Executable parse) {
        var failure = assertThrows(GoogleDriveException.class, parse);
        assertEquals("GOOGLE_DRIVE_SERVICE_ACCOUNT_KEY_INVALID", failure.code());
    }

    private static String json(String pem, String extra) {
        return "{" + extra + "\"type\":\"service_account\",\"project_id\":\"memoryos-prod\",\"private_key_id\":\"3f2a9c\","
                + "\"private_key\":\"" + pem.replace("\n", "\\n") + "\",\"client_email\":\"" + CLIENT_EMAIL + "\","
                + "\"client_id\":\"104512345678901234567\",\"auth_uri\":\"https://accounts.google.com/o/oauth2/auth\","
                + "\"universe_domain\":\"googleapis.com\"}";
    }

    static String pem(int bits) throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(bits);
        byte[] encoded = generator.generateKeyPair().getPrivate().getEncoded();
        return "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(encoded)
                + "\n-----END PRIVATE KEY-----\n";
    }
}
