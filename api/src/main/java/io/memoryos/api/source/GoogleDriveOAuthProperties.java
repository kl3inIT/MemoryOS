package io.memoryos.api.source;

import io.memoryos.connector.GoogleDriveException;
import java.net.URI;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public final class GoogleDriveOAuthProperties {
    private final Environment environment;
    public GoogleDriveOAuthProperties(Environment environment) { this.environment = environment; }

    public URI redirectUri() { return uri(required("redirect-uri"), true); }
    public URI authorizationUri() { return endpoint("authorization-uri", "https://accounts.google.com/o/oauth2/v2/auth"); }
    public URI tokenUri() { return endpoint("token-uri", "https://oauth2.googleapis.com/token"); }
    public URI revocationUri() { return endpoint("revocation-uri", "https://oauth2.googleapis.com/revoke"); }
    public URI driveApiBaseUrl() { return endpoint("drive-api-base-url", "https://www.googleapis.com/drive/v3"); }
    public URI jwkSetUri() { return endpoint("jwk-set-uri", "https://www.googleapis.com/oauth2/v3/certs"); }
    public URI issuerUri() { return endpoint("issuer-uri", "https://accounts.google.com"); }

    public void requireConfigured() {
        redirectUri(); authorizationUri(); tokenUri();
        revocationUri(); driveApiBaseUrl(); jwkSetUri(); issuerUri();
    }

    private URI endpoint(String key, String fallback) { return uri(environment.getProperty("memoryos.google-drive." + key, fallback), false); }
    private String required(String key) {
        String value = environment.getProperty("memoryos.google-drive." + key, "");
        if (value.isBlank()) throw GoogleDriveException.notConfigured();
        return value;
    }
    private static URI uri(String value, boolean callback) {
        try {
            URI uri = URI.create(value);
            boolean secure = "https".equals(uri.getScheme()) && uri.getHost() != null;
            boolean loopback = "http".equals(uri.getScheme()) && ("127.0.0.1".equals(uri.getHost())
                    || "[::1]".equals(uri.getHost()) || "localhost".equals(uri.getHost()));
            if ((!secure && !loopback) || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || (callback && !"/login/oauth2/code/google-drive".equals(uri.getPath()))) {
                throw GoogleDriveException.notConfigured();
            }
            return uri;
        } catch (IllegalArgumentException exception) { throw GoogleDriveException.notConfigured(); }
    }
}
