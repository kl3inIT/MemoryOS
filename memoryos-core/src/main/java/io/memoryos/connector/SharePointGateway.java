package io.memoryos.connector;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public interface SharePointGateway {
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

        /**
         * Who SharePoint lets read one list item, as its role assignments, read whole. This and the three calls
         * below go to SharePoint REST on {@code tenantHost}, which accepts only the application token of a
         * certificate credential: a client-secret credential, and any address outside the Tenant host, raise
         * {@link SharePointProviderException.Failure#AUTHORIZATION} before a request is sent. The item is found by
         * the identifier of its list, never by the library's title, which follows the site language.
         */
        List<RoleAssignment> itemRoleAssignments(String tenantHost, String webUrl, String listId, String listItemId);

        /** Who SharePoint lets read one site page, found by its server-relative address as Microsoft wrote it. */
        List<RoleAssignment> pageRoleAssignments(String tenantHost, String webUrl, String serverRelativeUrl);

        /** The role assignments of the site itself, which is where group membership starts from. */
        List<RoleAssignment> webRoleAssignments(String tenantHost, String webUrl);

        /** The direct members of a SharePoint group of the site: people, and Entra groups placed inside it. */
        List<Principal> siteGroupMembers(String tenantHost, String webUrl, long groupId);

        /**
         * One page of the direct members of an Entra group, through Microsoft Graph. A group that no longer exists
         * raises {@link SharePointProviderException.Failure#NOT_FOUND}.
         */
        EntraMemberPage entraGroupMembers(String groupId, @Nullable String link);

        /** One page of every group of the directory. */
        EntraGroupPage entraGroups(@Nullable String link);

        /** The scope of each sharing link of a drive item: {@code anonymous}, {@code organization} or {@code users}. */
        List<String> sharingLinkScopes(String driveId, String itemId);

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

        @Override public @NonNull String toString() { return "SharePointCredential[redacted]"; }
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
                     @Nullable String downloadUrl, @Nullable String driveId, @Nullable ListItem listItem) {
        public DriveItem {
            Objects.requireNonNull(id, "id");
        }

        public boolean file() { return !folder && !deleted; }

        /** The version used to decide whether stored content is still current. */
        public String contentVersion() {
            if (quickXorHash != null) return quickXorHash + ":" + size;
            return (eTag == null ? "" : eTag) + ":" + (lastModifiedAt == null ? "" : lastModifiedAt);
        }

        @Override public @NonNull String toString() { return "DriveItem[" + id + (deleted ? ",deleted]" : "]"); }
    }

    /**
     * Where SharePoint itself keeps a drive item: the list and the row in it, and the site that owns the list.
     * Its permissions are read there.
     */
    record ListItem(String listId, String listItemId, String webUrl) {
        public ListItem {
            Objects.requireNonNull(listId, "listId");
            Objects.requireNonNull(listItemId, "listItemId");
            Objects.requireNonNull(webUrl, "webUrl");
        }
    }

    /** What SharePoint names in a role assignment or lists in a group. */
    enum PrincipalType { USER, ENTRA_GROUP, SHAREPOINT_GROUP, OTHER }

    /**
     * {@code id} is the principal's number in its site, which is how a SharePoint group is asked for its members.
     * {@code loginName} is the claim SharePoint knows it by; for an Entra group it carries the group's identifier.
     */
    record Principal(PrincipalType type, long id, String loginName, @Nullable String title,
                     @Nullable String userPrincipalName) {
        public Principal {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(loginName, "loginName");
        }
    }

    /** {@code limitedAccessOnly}: every role bound is Limited Access, which lets nobody read the item itself. */
    record RoleAssignment(Principal member, boolean limitedAccessOnly) {
        public RoleAssignment {
            Objects.requireNonNull(member, "member");
        }
    }

    /** A direct member of an Entra group: a person, or another group when {@code group}. */
    record EntraMember(boolean group, String id, @Nullable String displayName, @Nullable String userPrincipalName) {
        public EntraMember {
            Objects.requireNonNull(id, "id");
        }
    }

    record EntraMemberPage(List<EntraMember> members, @Nullable String nextLink) {
        public EntraMemberPage { members = List.copyOf(members); }
    }

    record EntraGroup(String id, @Nullable String displayName) {
        public EntraGroup {
            Objects.requireNonNull(id, "id");
        }
    }

    record EntraGroupPage(List<EntraGroup> groups, @Nullable String nextLink) {
        public EntraGroupPage { groups = List.copyOf(groups); }
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

        @Override public @NonNull String toString() { return "PageContent[" + metadata.pageId() + "]"; }
    }

    record Content(String filename, String mediaType, byte[] bytes) {
        public Content {
            Objects.requireNonNull(filename, "filename");
            Objects.requireNonNull(mediaType, "mediaType");
            Objects.requireNonNull(bytes, "bytes");
        }

        @Override public @NonNull String toString() { return "Content[" + bytes.length + " bytes]"; }
    }
}
