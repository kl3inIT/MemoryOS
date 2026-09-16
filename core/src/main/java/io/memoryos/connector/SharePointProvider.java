package io.memoryos.connector;

import java.util.Arrays;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

public interface SharePointProvider {
    Session open(Credential credential);

    interface Session extends AutoCloseable {
        /** {@code GET /sites/root}: proves the token works and resolves the Tenant SharePoint host. */
        RootSite root();

        @Override void close();
    }

    enum AuthMethod { CLIENT_SECRET, CERTIFICATE }

    /** Only the worldwide cloud is supported; the enum keeps room for the sovereign clouds. Endpoints belong to the provider bundle. */
    enum Cloud { GLOBAL }

    record Credential(Cloud cloud, String directoryId, String clientId, AuthMethod authMethod,
                      byte @Nullable [] clientSecret, byte @Nullable [] privateKey, byte @Nullable [] certificate)
            implements AutoCloseable {
        public Credential {
            Objects.requireNonNull(cloud, "cloud");
            Objects.requireNonNull(directoryId, "directoryId");
            Objects.requireNonNull(clientId, "clientId");
            Objects.requireNonNull(authMethod, "authMethod");
            clientSecret = clientSecret == null ? null : clientSecret.clone();
            privateKey = privateKey == null ? null : privateKey.clone();
            certificate = certificate == null ? null : certificate.clone();
            if (authMethod == AuthMethod.CLIENT_SECRET
                    ? clientSecret == null || privateKey != null || certificate != null
                    : clientSecret != null || privateKey == null || certificate == null) {
                throw new IllegalArgumentException("SharePoint credential carries exactly one authentication payload");
            }
        }

        public static Credential clientSecret(Cloud cloud, String directoryId, String clientId, byte[] secret) {
            return new Credential(cloud, directoryId, clientId, AuthMethod.CLIENT_SECRET, secret, null, null);
        }

        public static Credential certificate(Cloud cloud, String directoryId, String clientId, byte[] privateKey, byte[] certificate) {
            return new Credential(cloud, directoryId, clientId, AuthMethod.CERTIFICATE, null, privateKey, certificate);
        }

        @Override public byte @Nullable [] clientSecret() { return clientSecret == null ? null : clientSecret.clone(); }
        @Override public byte @Nullable [] privateKey() { return privateKey == null ? null : privateKey.clone(); }
        @Override public byte @Nullable [] certificate() { return certificate == null ? null : certificate.clone(); }

        @Override public void close() {
            if (clientSecret != null) Arrays.fill(clientSecret, (byte) 0);
            if (privateKey != null) Arrays.fill(privateKey, (byte) 0);
        }

        @Override public String toString() { return "SharePointCredential[redacted]"; }
    }

    record RootSite(String siteId, String webUrl, String hostname) {
        public RootSite {
            Objects.requireNonNull(siteId, "siteId");
            Objects.requireNonNull(webUrl, "webUrl");
            Objects.requireNonNull(hostname, "hostname");
        }
    }
}
