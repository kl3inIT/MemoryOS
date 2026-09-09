package io.memoryos.connector.application;

import io.memoryos.connector.GoogleDriveConnectionService;
import io.memoryos.connector.GoogleDriveProvider;
import io.memoryos.connector.GoogleDriveProvider.FileMetadata;
import io.memoryos.connector.GoogleDriveProviderException;
import io.memoryos.connector.GoogleDriveProviderException.Failure;
import io.memoryos.connector.GoogleDriveSourceService.*;
import io.memoryos.connector.SourceException;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.persistence.JdbcGoogleDriveSourceRepository;
import io.memoryos.connector.persistence.JdbcGoogleDriveSourceRepository.TreeEntry;
import io.memoryos.iam.TenantId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** One bounded metadata-only read; the service fences database authority before and after it. */
final class GoogleDriveSelectionTree {
    private final JdbcGoogleDriveSourceRepository drive;
    private final TenantId tenant;
    private final SourceId source;
    private final JdbcGoogleDriveSourceRepository.ConfigurationRow config;
    private final Map<String, Root> roots = new HashMap<>();
    private final Map<String, FileMetadata> metadata = new HashMap<>();
    private final Map<String, Failure> failures = new HashMap<>();
    private final long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
    private final boolean usable;
    private final boolean currentDiscovery;
    private final @Nullable String parentId;
    private final int size;
    private final String prefix;
    private final Position position;
    private final List<TreeEntry> top;
    private GoogleDriveProvider.@Nullable Session session;
    private int operations;

    GoogleDriveSelectionTree(JdbcGoogleDriveSourceRepository drive, TenantId tenant, SourceId source,
            JdbcGoogleDriveSourceRepository.ConfigurationRow config, GoogleDriveConnectionService.State state,
            List<Root> roots, boolean usable, @Nullable String parentId, @Nullable String cursor, int size) {
        this.drive = drive;
        this.tenant = tenant;
        this.source = source;
        this.config = config;
        roots.forEach(root -> this.roots.put(root.id(), root));
        this.usable = usable;
        this.currentDiscovery = config.discoveryScopeRevision() == config.revision()
                && config.discoveryCredentialRevision() == state.credentialRevision();
        this.parentId = parentId;
        this.size = size;
        this.prefix = "tree|" + tenant + "|" + source + "|" + config.revision() + "|" + config.discoveryRevision()
                + "|" + state.credentialId() + "|" + state.credentialRevision() + "|" + (parentId == null ? "" : parentId) + "|";
        this.position = Position.parse(prefix, cursor);
        if (parentId == null && position.provider()) throw invalidCursor();
        this.top = parentId == null ? entries(null) : List.of();
    }

    static void validate(@Nullable String parentId, @Nullable String cursor, int size) {
        if (size < 1 || size > 100 || parentId != null && (!validId(parentId) || "root".equals(parentId))
                || cursor != null && (cursor.isBlank() || cursor.length() > 65536))
            throw SourceException.invalid("Invalid selection tree page.", "selection tree bounds exceeded");
    }

    boolean requiresProvider() {
        return parentId != null || usable && top.stream().limit(size).anyMatch(this::needsCoverageCheck);
    }

    Page read(GoogleDriveProvider.@Nullable Session session) {
        this.session = session;
        if (parentId == null) return linkedPage(top);
        if (!usable) throw SourceException.conflict("Google connection is unavailable");
        if (config.scopeMode() == ScopeMode.GENERAL) {
            var root = metadata("root");
            GoogleDriveRootValidation.requireMyDriveRoot(root);
            if (roots.size() != 1 || !roots.containsKey(root.id())) throw new GoogleDriveProviderException(Failure.INCONSISTENT);
        }
        var authority = drive.treeAuthority(tenant, source, parentId);
        if (!authority.root() && !authority.approved() && roots.values().stream().noneMatch(GoogleDriveSelectionTree::folder))
            throw SourceException.notFound();
        var parent = metadata(parentId);
        boolean approved = config.scopeMode() == ScopeMode.SPECIFIC && authority.approved() && GoogleDriveLinkedDiscovery.supported(parent);
        boolean covered = !approved && covered(parent);
        if (!covered && !approved) throw SourceException.notFound();
        if (parent.trashed()) throw new GoogleDriveProviderException(Failure.NOT_FOUND);
        if (parent.folder()) {
            if (!covered || parent.shortcutTargetId() != null) throw SourceException.notFound();
            return folderPage(parent);
        }
        if (!GoogleDriveLinkedDiscovery.supported(parent)) throw new GoogleDriveProviderException(Failure.UNSUPPORTED);
        if (position.provider()) throw invalidCursor();
        return linkedPage(entries(parentId));
    }

    private List<TreeEntry> entries(@Nullable String parent) {
        return drive.treeEntries(tenant, source, parent, currentDiscovery, position.sortKind(), position.name(), position.id(), size);
    }

    private Page linkedPage(List<TreeEntry> entries) {
        boolean more = entries.size() > size;
        var visible = entries.subList(0, Math.min(size, entries.size()));
        var items = new ArrayList<SelectionTreeItem>(visible.size());
        for (var entry : visible) {
            var item = entry.item();
            boolean included = item.coveredByRoots();
            boolean expandable = usable && (entry.root() && item.kind() == SelectionKind.FOLDER
                    || entry.outgoing() && item.status() == LinkedDocumentStatus.AVAILABLE && supported(item.mimeType())
                    && (entry.root() || entry.approved()));
            var status = item.status();
            if (usable && needsCoverageCheck(entry)) {
                try {
                    var file = metadata(item.id());
                    included = covered(file);
                    expandable = included && GoogleDriveLinkedDiscovery.supported(file);
                    if (file.trashed()) status = LinkedDocumentStatus.UNAVAILABLE;
                    else if (!GoogleDriveLinkedDiscovery.supported(file)) status = LinkedDocumentStatus.UNSUPPORTED;
                } catch (GoogleDriveProviderException exception) {
                    if (exception.failure() != Failure.NOT_FOUND && exception.failure() != Failure.UNSUPPORTED) throw exception;
                    included = false;
                    expandable = false;
                    status = exception.failure() == Failure.NOT_FOUND ? LinkedDocumentStatus.UNAVAILABLE : LinkedDocumentStatus.UNSUPPORTED;
                }
            }
            items.add(new SelectionTreeItem(item.id(), item.name(), item.mimeType(), item.kind(), item.selected(),
                    included, status, item.origins(), expandable));
        }
        String next = more ? encode(prefix + Position.after(visible.getLast().item())) : null;
        return new Page(List.copyOf(items), next);
    }

    private boolean needsCoverageCheck(TreeEntry entry) {
        var item = entry.item();
        return !entry.root() && !entry.approved() && item.coveredByRoots() && entry.outgoing()
                && item.status() == LinkedDocumentStatus.AVAILABLE && supported(item.mimeType());
    }

    private Page folderPage(FileMetadata parent) {
        if (!position.provider() && position.sortKind() != -1) throw invalidCursor();
        operation();
        var page = Objects.requireNonNull(session).listFiles(parent.id(), position.token());
        check();
        if (page.files().size() > 1000 || page.nextPageToken() != null
                && (page.nextPageToken().isBlank() || page.nextPageToken().length() > 16384
                || page.nextPageToken().equals(position.token()))) throw new GoogleDriveProviderException(Failure.MALFORMED);
        var ids = new HashSet<String>();
        for (var file : page.files()) {
            validateMetadata(file.id(), file);
            if (!ids.add(file.id()) || !file.parents().contains(parent.id())
                    || config.scopeMode() == ScopeMode.GENERAL && file.driveId() != null)
                throw new GoogleDriveProviderException(Failure.INCONSISTENT);
            metadata.putIfAbsent(file.id(), file);
        }
        String fingerprint = position.offset() > 0 || page.files().size() > size ? fingerprint(page) : "";
        if (position.offset() > page.files().size() || position.offset() > 0 && !fingerprint.equals(position.fingerprint()))
            throw SourceException.staleConfiguration();
        int end = Math.min(position.offset() + size, page.files().size());
        var visible = page.files().subList(position.offset(), end);
        var outgoing = drive.treeOutgoingParents(tenant, source, visible.stream().map(FileMetadata::id).toList());
        var items = visible.stream().map(file -> {
            boolean supported = GoogleDriveLinkedDiscovery.supported(file);
            var status = file.trashed() ? LinkedDocumentStatus.UNAVAILABLE
                    : file.folder() && file.shortcutTargetId() == null || supported ? LinkedDocumentStatus.AVAILABLE : LinkedDocumentStatus.UNSUPPORTED;
            return new SelectionTreeItem(file.id(), file.name(), file.mimeType(), file.folder() ? SelectionKind.FOLDER : SelectionKind.FILE,
                    roots.containsKey(file.id()), true, status, List.of(), status == LinkedDocumentStatus.AVAILABLE
                    && (file.folder() || outgoing.contains(file.id())));
        }).toList();
        String next = end < page.files().size() ? encode(prefix + Position.provider(position.token(), end, fingerprint))
                : page.nextPageToken() == null ? null : encode(prefix + Position.provider(page.nextPageToken(), 0, ""));
        return new Page(items, next);
    }

    private boolean covered(FileMetadata file) {
        if (file.trashed() || config.scopeMode() == ScopeMode.GENERAL && file.driveId() != null) return false;
        var direct = roots.get(file.id());
        if (direct != null) return folder(direct) == file.folder() && file.shortcutTargetId() == null;
        if (roots.values().stream().noneMatch(GoogleDriveSelectionTree::folder)) return false;
        var pending = new ArrayDeque<>(file.parents());
        var visited = new HashSet<String>();
        while (!pending.isEmpty()) {
            String id = pending.removeFirst();
            if (!visited.add(id)) continue;
            if (visited.size() > 64) throw new GoogleDriveProviderException(Failure.LIMIT_EXCEEDED);
            FileMetadata ancestor;
            try { ancestor = metadata(id); }
            catch (GoogleDriveProviderException exception) {
                if (exception.failure() == Failure.NOT_FOUND) continue;
                throw exception;
            }
            if (ancestor.trashed() || !ancestor.folder() || ancestor.shortcutTargetId() != null
                    || config.scopeMode() == ScopeMode.GENERAL && ancestor.driveId() != null) continue;
            var root = roots.get(id);
            if (root != null && folder(root)) return true;
            pending.addAll(ancestor.parents());
        }
        return false;
    }

    private FileMetadata metadata(String id) {
        check();
        var cached = metadata.get(id);
        if (cached != null) return cached;
        var failure = failures.get(id);
        if (failure != null) throw new GoogleDriveProviderException(failure);
        operation();
        try {
            var file = Objects.requireNonNull(session).metadata(id);
            check();
            validateMetadata(id, file);
            metadata.put(id, file);
            metadata.putIfAbsent(file.id(), file);
            return file;
        } catch (GoogleDriveProviderException exception) {
            failures.put(id, exception.failure());
            throw exception;
        }
    }

    private static void validateMetadata(String id, FileMetadata file) {
        if (file == null || !validId(file.id()) || !"root".equals(id) && !id.equals(file.id())
                || file.name() == null || file.name().isBlank() || file.name().length() > 255
                || file.mimeType() == null || file.mimeType().length() > 160 || file.version() == null || file.version().isBlank()
                || file.parents().size() > 100 || file.parents().stream().anyMatch(parent -> !validId(parent)))
            throw new GoogleDriveProviderException(Failure.MALFORMED);
    }

    private void operation() { check(); if (++operations > 256) throw new GoogleDriveProviderException(Failure.LIMIT_EXCEEDED); }
    private void check() { if (System.nanoTime() >= deadline) throw new GoogleDriveProviderException(Failure.LIMIT_EXCEEDED); }
    private static boolean validId(String id) { return id != null && id.matches("[A-Za-z0-9_-]{1,256}"); }
    private static boolean folder(Root root) { return folder(root.mimeType()); }
    private static boolean folder(String mimeType) { return "application/vnd.google-apps.folder".equals(mimeType); }
    private static boolean supported(String mimeType) { return GoogleDriveLinkedDiscovery.supported(mimeType); }
    private static String fingerprint(GoogleDriveProvider.FilePage page) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (var file : page.files()) {
                digest.update(file.id().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            if (page.nextPageToken() != null) digest.update(page.nextPageToken().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    private static String encode(String value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
    private static String decode(String value) { return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8); }
    private static SourceException invalidCursor() { return SourceException.invalid("Invalid selection tree cursor.", "malformed selection tree cursor"); }
    record Page(List<SelectionTreeItem> items, @Nullable String nextCursor) {}

    private record Position(boolean provider, int sortKind, String name, String id, @Nullable String token, int offset, String fingerprint) {
        static Position parse(String prefix, @Nullable String cursor) {
            if (cursor == null) return new Position(false, -1, "", "", null, 0, "");
            try {
                String decoded = decode(cursor);
                if (!decoded.startsWith(prefix)) throw SourceException.staleConfiguration();
                String[] parts = decoded.substring(prefix.length()).split("\\|", -1);
                if (parts.length != 4) throw invalidCursor();
                if ("D".equals(parts[0])) {
                    int kind = Integer.parseInt(parts[1]);
                    String name = decode(parts[2]);
                    if (kind < 0 || kind > 2 || name.length() > 255 || !validId(parts[3])) throw invalidCursor();
                    return new Position(false, kind, name, parts[3], null, 0, "");
                }
                if (!"P".equals(parts[0])) throw invalidCursor();
                int offset = Integer.parseInt(parts[1]);
                String token = decode(parts[2]);
                if (offset < 0 || offset >= 1000 || token.length() > 16384 || !token.isEmpty() && token.isBlank()
                        || offset > 0 && !parts[3].matches("[a-f0-9]{64}") || offset == 0 && !parts[3].isEmpty()) throw invalidCursor();
                return new Position(true, -1, "", "", token.isEmpty() ? null : token, offset, parts[3]);
            } catch (IllegalArgumentException exception) { throw invalidCursor(); }
        }
        static String after(SelectionItem item) { return "D|" + item.kind().ordinal() + "|" + encode(item.name()) + "|" + item.id(); }
        static String provider(@Nullable String token, int offset, String fingerprint) {
            return "P|" + offset + "|" + encode(token == null ? "" : token) + "|" + fingerprint;
        }
    }
}
