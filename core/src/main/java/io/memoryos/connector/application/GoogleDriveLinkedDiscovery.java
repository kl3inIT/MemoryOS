package io.memoryos.connector.application;

import io.memoryos.connector.GoogleDriveLinkReader;
import io.memoryos.connector.GoogleDriveProvider;
import io.memoryos.connector.GoogleDriveProvider.FileMetadata;
import io.memoryos.connector.GoogleDriveProviderException;
import io.memoryos.connector.GoogleDriveProviderException.Failure;
import io.memoryos.connector.GoogleDriveSourceService.DiscoveryError;
import io.memoryos.connector.GoogleDriveSourceService.LinkedDocument;
import io.memoryos.connector.GoogleDriveSourceService.LinkedDocumentStatus;
import io.memoryos.connector.GoogleDriveSourceService.LinkOrigin;
import io.memoryos.connector.GoogleDriveSourceService.Root;
import io.memoryos.connector.SourceException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One explicit discovery run; acquired bytes never enter a database transaction. */
final class GoogleDriveLinkedDiscovery {
    private static final Set<String> SUPPORTED = Set.of(
            "application/vnd.google-apps.presentation",
            "application/vnd.google-apps.document", "application/vnd.google-apps.spreadsheet",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "text/csv",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/pdf", "text/plain", "text/markdown", "text/x-markdown");
    private final GoogleDriveProvider.Session session;
    private final GoogleDriveLinkReader reader;
    private final long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
    private final Map<String, FileMetadata> metadata = new HashMap<>();
    private final Map<String, Failure> failures = new HashMap<>();
    private final Map<String, List<GoogleDriveLinkReader.Link>> content = new HashMap<>();
    private final Map<String, LinkedHashSet<LinkOrigin>> origins = new LinkedHashMap<>();
    private final Set<DiscoveryError> errors = new LinkedHashSet<>();
    private int operations;
    private int edges;

    GoogleDriveLinkedDiscovery(GoogleDriveProvider.Session session, GoogleDriveLinkReader reader) {
        this.session = session;
        this.reader = reader;
    }

    Result discover(List<Root> roots, List<LinkedDocument> previous) {
        Set<String> rootIds = new HashSet<>();
        var pending = new ArrayDeque<Path>();
        roots.forEach(root -> {
            rootIds.add(root.id());
            pending.add(new Path(root.id(), root.id(), true));
        });
        var selected = new LinkedHashMap<String, LinkedDocument>();
        previous.stream().filter(LinkedDocument::selected).forEach(document -> {
            selected.put(document.id(), document);
            origins.put(document.id(), new LinkedHashSet<>());
        });
        scanPaths(pending, selected);
        for (var document : selected.values()) {
            pending.add(new Path(document.id(), document.id(), false));
            scanPaths(pending, selected);
        }
        var documents = new ArrayList<LinkedDocument>();
        for (var entry : origins.entrySet()) {
            check();
            String id = entry.getKey();
            var old = selected.get(id);
            String name = old == null ? id : old.name();
            String mime = old == null ? "application/octet-stream" : old.mimeType();
            LinkedDocumentStatus status = LinkedDocumentStatus.UNAVAILABLE;
            boolean covered = false;
            try {
                var file = metadata(id);
                name = bounded(file.name(), 255);
                mime = bounded(file.mimeType(), 160);
                status = file.trashed() ? LinkedDocumentStatus.UNAVAILABLE
                        : supported(file) ? LinkedDocumentStatus.AVAILABLE : LinkedDocumentStatus.UNSUPPORTED;
                covered = covered(file, rootIds);
            } catch (GoogleDriveProviderException exception) {
                inputError(id, name, exception);
                if (exception.failure() == Failure.UNSUPPORTED) status = LinkedDocumentStatus.UNSUPPORTED;
            }
            documents.add(new LinkedDocument(id, name, mime, old != null, covered, status, List.copyOf(entry.getValue())));
        }
        check();
        return new Result(List.copyOf(documents), List.copyOf(errors));
    }

    private void scanPaths(ArrayDeque<Path> pending, Map<String, LinkedDocument> selected) {
        var visited = new HashSet<Path>();
        while (!pending.isEmpty()) {
            check();
            Path path = pending.removeFirst();
            if (!visited.add(path)) continue;
            try {
                var file = metadata(path.fileId());
                if (file.trashed()) throw new GoogleDriveProviderException(Failure.NOT_FOUND);
                if (file.shortcutTargetId() != null || "application/vnd.google-apps.shortcut".equals(file.mimeType()))
                    throw new GoogleDriveProviderException(Failure.UNSUPPORTED);
                if (file.folder()) {
                    if (!path.folderAuthority()) throw new GoogleDriveProviderException(Failure.UNSUPPORTED);
                    if (file.id().equals(path.rootId()) && (file.id().equals(file.driveId())
                            || file.driveId() == null && file.parents().isEmpty() && file.id().equals(metadata("root").id())))
                        throw new GoogleDriveProviderException(Failure.UNSUPPORTED);
                    list(path, pending);
                } else {
                    scan(path, file);
                }
            } catch (GoogleDriveProviderException exception) {
                inputError(path.fileId(), knownName(path.fileId(), selected), exception);
            }
        }
    }

    private void list(Path path, ArrayDeque<Path> pending) {
        String token = null;
        var tokens = new HashSet<String>();
        do {
            operation();
            var page = session.listFiles(path.fileId(), token);
            check();
            for (var child : page.files()) {
                metadata.putIfAbsent(child.id(), child);
                pending.add(new Path(path.rootId(), child.id(), true));
                if (pending.size() > 2500) limit();
            }
            token = page.nextPageToken();
            if (token != null && !tokens.add(token)) throw new GoogleDriveProviderException(Failure.MALFORMED);
        } while (token != null);
    }

    private void scan(Path path, FileMetadata file) {
        List<GoogleDriveLinkReader.Link> links = content.get(file.id());
        if (links == null) {
            if (content.size() >= 100) limit();
            content.put(file.id(), List.of());
            if (!supported(file)) throw new GoogleDriveProviderException(Failure.UNSUPPORTED);
            check();
            var acquired = session.acquire(file);
            if (!file.id().equals(acquired.descriptor().providerFileId())
                    || !file.version().equals(acquired.descriptor().providerVersion()))
                throw new GoogleDriveProviderException(Failure.INCONSISTENT);
            check();
            links = reader.read(acquired);
            check();
            content.put(file.id(), links);
        }
        for (var link : links) {
            String id;
            try { id = DefaultGoogleDriveSourceService.fileId(link.url()); }
            catch (SourceException ignored) { continue; }
            var targetOrigins = origins.computeIfAbsent(id, _ -> new LinkedHashSet<>());
            if (origins.size() > 500) limit();
            if (targetOrigins.add(new LinkOrigin(path.rootId(), file.id(), bounded(file.name(), 255), bounded(link.location(), 256)))
                    && ++edges > 2000) limit();
        }
    }

    FileMetadata metadata(String id) {
        check();
        if (failures.containsKey(id)) throw new GoogleDriveProviderException(failures.get(id));
        var saved = metadata.get(id);
        if (saved != null) return saved;
        operation();
        try {
            var file = session.metadata(id);
            check();
            if (!"root".equals(id) && !id.equals(file.id())) throw new GoogleDriveProviderException(Failure.INCONSISTENT);
            metadata.put(id, file);
            return file;
        } catch (GoogleDriveProviderException exception) {
            failures.put(id, exception.failure());
            throw exception;
        }
    }

    boolean covered(FileMetadata file, Set<String> roots) {
        if (roots.contains(file.id())) return true;
        var pending = new ArrayDeque<>(file.parents());
        var visited = new HashSet<String>();
        while (!pending.isEmpty()) {
            check();
            String parent = pending.removeFirst();
            if (roots.contains(parent)) return true;
            if (!visited.add(parent)) continue;
            var ancestor = metadata(parent);
            if (!ancestor.trashed()) pending.addAll(ancestor.parents());
        }
        return false;
    }

    static boolean supported(FileMetadata file) {
        return !file.trashed() && !file.folder() && file.shortcutTargetId() == null && supported(file.mimeType());
    }

    static boolean supported(String mimeType) { return SUPPORTED.contains(mimeType); }

    private void inputError(String id, String name, GoogleDriveProviderException exception) {
        if (exception.failure() == Failure.AUTHENTICATION || exception.failure() == Failure.QUOTA
                || exception.failure() == Failure.LIMIT_EXCEEDED) throw exception;
        errors.add(new DiscoveryError(id, bounded(name, 255), "SOURCE_GOOGLE_" + exception.failure().name()));
    }

    private String knownName(String id, Map<String, LinkedDocument> previous) {
        if (metadata.containsKey(id)) return metadata.get(id).name();
        return previous.containsKey(id) ? previous.get(id).name() : id;
    }

    private void operation() { check(); if (++operations > 512) limit(); }
    private void check() { if (System.nanoTime() >= deadline) limit(); }
    private static void limit() { throw new GoogleDriveProviderException(Failure.LIMIT_EXCEEDED); }
    private static String bounded(String value, int limit) { return value.substring(0, Math.min(value.length(), limit)); }
    private record Path(String rootId, String fileId, boolean folderAuthority) {}
    record Result(List<LinkedDocument> documents, List<DiscoveryError> errors) {}
}
