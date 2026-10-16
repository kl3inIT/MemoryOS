package io.memoryos.connector;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

/**
 * A Google Cloud service-account key as downloaded from the console. Only the identity and the RSA private key are
 * kept; provider endpoints are fixed and never taken from the upload.
 */
public final class GoogleDriveServiceAccountKey implements AutoCloseable {
    public static final int MAX_JSON_BYTES = 16 * 1024;
    private static final int MIN_RSA_BITS = 2048;
    private static final String PEM_HEADER = "-----BEGIN PRIVATE KEY-----";
    private static final String PEM_FOOTER = "-----END PRIVATE KEY-----";
    private static final Pattern CLIENT_EMAIL = Pattern.compile("[a-z0-9-]{1,63}@[a-z0-9.-]{1,100}\\.gserviceaccount\\.com");
    private static final Pattern CLIENT_ID = Pattern.compile("[0-9]{1,32}");
    private static final Pattern PRIVATE_KEY_ID = Pattern.compile("[A-Za-z0-9]{1,128}");
    /** Fields whose value, when present, must name Google's own endpoints. */
    private static final Map<String, String> FIXED = Map.of(
            "token_uri", "https://oauth2.googleapis.com/token",
            "universe_domain", "googleapis.com");
    private static final Set<String> REQUIRED = Set.of("type", "client_email", "client_id", "private_key_id", "private_key");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ObjectReader READER = MAPPER.reader().with(
            DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY, DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final String clientEmail;
    private final String clientId;
    private final String privateKeyId;
    private final byte[] pkcs8;

    private GoogleDriveServiceAccountKey(String clientEmail, String clientId, String privateKeyId, byte[] pkcs8) {
        this.clientEmail = clientEmail;
        this.clientId = clientId;
        this.privateKeyId = privateKeyId;
        this.pkcs8 = pkcs8;
    }

    /** Reads the downloaded JSON key; every malformed, weak or foreign key fails the same way. */
    public static GoogleDriveServiceAccountKey parse(String json) {
        Objects.requireNonNull(json, "json");
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        try {
            if (json.isBlank() || payload.length > MAX_JSON_BYTES) throw GoogleDriveException.invalidServiceAccountKey();
            JsonNode root = READER.readTree(payload);
            if (root == null || !root.isObject()) throw GoogleDriveException.invalidServiceAccountKey();
            for (String field : REQUIRED) {
                if (!root.path(field).isString() || root.path(field).asString().isBlank()) {
                    throw GoogleDriveException.invalidServiceAccountKey();
                }
            }
            FIXED.forEach((field, value) -> {
                if (root.has(field) && !value.equals(root.path(field).asString())) throw GoogleDriveException.invalidServiceAccountKey();
            });
            String clientEmail = root.path("client_email").asString();
            String clientId = root.path("client_id").asString();
            String privateKeyId = root.path("private_key_id").asString();
            if (!"service_account".equals(root.path("type").asString()) || !CLIENT_EMAIL.matcher(clientEmail).matches()
                    || !CLIENT_ID.matcher(clientId).matches() || !PRIVATE_KEY_ID.matcher(privateKeyId).matches()) {
                throw GoogleDriveException.invalidServiceAccountKey();
            }
            return new GoogleDriveServiceAccountKey(clientEmail, clientId, privateKeyId, pkcs8(root.path("private_key").asString()));
        } catch (GoogleDriveException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw GoogleDriveException.invalidServiceAccountKey();
        } finally {
            Arrays.fill(payload, (byte) 0);
        }
    }

    /** Reads the stored form written by {@link #encode()}. */
    public static GoogleDriveServiceAccountKey decode(byte[] stored) {
        return parse(new String(stored, StandardCharsets.UTF_8));
    }

    /** The minimal key JSON that {@link #decode(byte[])} reads back; the caller wipes it. */
    public byte[] encode() {
        var node = MAPPER.createObjectNode()
                .put("type", "service_account")
                .put("client_email", clientEmail)
                .put("client_id", clientId)
                .put("private_key_id", privateKeyId)
                .put("private_key", PEM_HEADER + "\n" + Base64.getMimeEncoder().encodeToString(pkcs8) + "\n" + PEM_FOOTER + "\n");
        return MAPPER.writeValueAsBytes(node);
    }

    public String clientEmail() { return clientEmail; }
    public String clientId() { return clientId; }
    public String privateKeyId() { return privateKeyId; }

    public PrivateKey privateKey() {
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        } catch (GeneralSecurityException exception) {
            throw GoogleDriveException.invalidServiceAccountKey();
        }
    }

    @Override public void close() { Arrays.fill(pkcs8, (byte) 0); }
    @Override public String toString() { return "GoogleDriveServiceAccountKey[" + clientEmail + "]"; }

    private static byte[] pkcs8(String pem) {
        String trimmed = pem.strip();
        if (!trimmed.startsWith(PEM_HEADER) || !trimmed.endsWith(PEM_FOOTER)) throw GoogleDriveException.invalidServiceAccountKey();
        byte[] der = Base64.getMimeDecoder().decode(trimmed.substring(PEM_HEADER.length(), trimmed.length() - PEM_FOOTER.length()));
        try {
            var key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
            if (!(key instanceof RSAPrivateCrtKey rsa) || rsa.getModulus().bitLength() < MIN_RSA_BITS) {
                throw GoogleDriveException.invalidServiceAccountKey();
            }
            return der;
        } catch (GeneralSecurityException exception) {
            Arrays.fill(der, (byte) 0);
            throw GoogleDriveException.invalidServiceAccountKey();
        }
    }
}
