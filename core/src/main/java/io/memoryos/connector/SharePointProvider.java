package io.memoryos.connector;

import java.time.Instant;
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

        /**
         * One page of a library's change log. {@code token} is the ISO-8601 instant the previous successful
         * refresh ended at, or null for the whole library. {@code link} continues an unfinished page walk.
         * A token Microsoft no longer accepts raises {@link SharePointProviderException.Failure#RESYNC_REQUIRED}.
         */
        DeltaPage delta(String driveId, @Nullable String token, @Nullable String link);

        /** One page of a folder's direct children, used when the root is a folder rather than a library. */
        ItemPage children(String driveId, String itemId, @Nullable String link);

        /** Current metadata of one item, including the short-lived download address. */
        DriveItem item(String driveId, String itemId);

        /** Downloads an item, refusing any address outside {@code tenantHost}, the Tenant SharePoint host. */
        Content content(DriveItem item, String tenantHost, int maxBytes);

        /** One page of a site's published pages, as metadata only. */
        SitePageList pages(String siteId, @Nullable String link);

        /**
         * One page with its canvas, as a snapshot the page reader turns into text. Each page is read on its
         * own, so a page whose canvas cannot be read affects only itself.
         */
        PageContent page(String siteId, String pageId);

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

    /**
     * A drive item as the change log reports it. A tombstone carries {@code deleted} with an identifier and
     * a parent, but no name, which is why MemoryOS stores the identifier of everything it holds.
     */
    record DriveItem(String id, @Nullable String name, boolean folder, boolean deleted, long size,
                     @Nullable String mimeType, @Nullable String quickXorHash, @Nullable String eTag,
                     @Nullable Instant createdAt, @Nullable Instant lastModifiedAt,
                     @Nullable String parentId, @Nullable String parentPath, @Nullable String webUrl,
                     @Nullable String downloadUrl, @Nullable String driveId) {
        public DriveItem {
            Objects.requireNonNull(id, "id");
        }

        public boolean file() { return !folder && !deleted; }

        /** The version used to decide whether stored content is still current. */
        public String contentVersion() {
            if (quickXorHash != null) return quickXorHash + ":" + size;
            return (eTag == null ? "" : eTag) + ":" + (lastModifiedAt == null ? "" : lastModifiedAt);
        }

        @Override public String toString() { return "DriveItem[" + id + (deleted ? ",deleted]" : "]"); }
    }

    record DeltaPage(List<DriveItem> items, @Nullable String nextLink, @Nullable String deltaLink) {
        public DeltaPage { items = List.copyOf(items); }
    }

    record ItemPage(List<DriveItem> items, @Nullable String nextLink) {
        public ItemPage { items = List.copyOf(items); }
    }

    /** A published site page as listed, without its canvas. */
    record SitePageMetadata(String pageId, String title, String webUrl, @Nullable String eTag,
                            @Nullable Instant lastModifiedAt) {
        public SitePageMetadata {
            Objects.requireNonNull(pageId, "pageId");
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(webUrl, "webUrl");
        }

        /** The version used to decide whether a stored page is still current. */
        public String contentVersion() {
            return (eTag == null ? "" : eTag) + ":" + (lastModifiedAt == null ? "" : lastModifiedAt);
        }
    }

    record SitePageList(List<SitePageMetadata> pages, @Nullable String nextLink) {
        public SitePageList { pages = List.copyOf(pages); }
    }

    /** {@code snapshot} is the JSON the page reader consumes. */
    record PageContent(SitePageMetadata metadata, byte[] snapshot) {
        public PageContent {
            Objects.requireNonNull(metadata, "metadata");
            snapshot = Objects.requireNonNull(snapshot, "snapshot").clone();
        }

        @Override public byte[] snapshot() { return snapshot.clone(); }

        @Override public String toString() { return "PageContent[" + metadata.pageId() + "]"; }
    }

    record Content(String filename, String mediaType, byte[] bytes) {
        public Content {
            Objects.requireNonNull(filename, "filename");
            Objects.requireNonNull(mediaType, "mediaType");
            Objects.requireNonNull(bytes, "bytes");
        }

        @Override public String toString() { return "Content[" + bytes.length + " bytes]"; }
    }
}
