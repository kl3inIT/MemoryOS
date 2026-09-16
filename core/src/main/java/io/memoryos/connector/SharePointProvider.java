package io.memoryos.connector;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

public interface SharePointProvider {
    Session open(Credential credential);

    interface Session extends AutoCloseable {
        /** {@code GET /sites/root}: proves the token works and resolves the Tenant SharePoint host. */
        RootSite root();

        /** Resolves a site by its server-relative path, for example {@code /sites/Finance}. */
        Site site(String hostname, String sitePath);

        /** Document libraries of a site. Personal sites also return a cache library, which is not content. */
        List<Library> libraries(String siteId);

        /** Resolves a folder inside a library by its path below the library root. */
        Folder folder(String driveId, List<String> folderSegments);

        /** One page of {@code /sites/getAllSites}; {@code nextLink} continues it. */
        SitePage sites(@Nullable String nextLink);

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

    /** {@code name} is null for sites that have none, such as a Tenant's search centre. */
    record Site(String siteId, String webUrl, @Nullable String displayName, boolean personalSite) {
        public Site {
            Objects.requireNonNull(siteId, "siteId");
            Objects.requireNonNull(webUrl, "webUrl");
        }
    }

    record SitePage(List<Site> sites, @Nullable String nextLink) {
        public SitePage { sites = List.copyOf(sites); }
    }

    /**
     * {@code path} is the library's server-relative URL path, which stays in English even when the display
     * name is localized, so matching a pasted address never depends on the site language.
     */
    record Library(String driveId, String name, String path) {
        public Library {
            Objects.requireNonNull(driveId, "driveId");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(path, "path");
        }
    }

    record Folder(String itemId, String name) {
        public Folder {
            Objects.requireNonNull(itemId, "itemId");
            Objects.requireNonNull(name, "name");
        }
    }
}
