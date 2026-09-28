package io.memoryos.connector.googledrive;

import io.memoryos.BusinessException;
import io.memoryos.FailureEvidence;
import io.memoryos.connector.ConnectorSyncPort.Work;
import io.memoryos.connector.GoogleDriveProvider;
import io.memoryos.connector.GoogleDriveProviderException;
import io.memoryos.connector.GoogleDriveProviderException.Failure;
import io.memoryos.connector.GoogleDriveSourceService.ScopeMode;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceInputFormat;
import io.memoryos.connector.SourceStorageFailure;
import io.memoryos.connector.SourceType;
import io.memoryos.connector.googledrive.persistence.JdbcGoogleDriveAclRepository;
import io.memoryos.connector.googledrive.persistence.JdbcGoogleDriveSourceRepository;
import io.memoryos.connector.googledrive.persistence.JdbcGoogleDriveSyncRepository;
import io.memoryos.connector.googledrive.persistence.JdbcGoogleDriveSyncRepository.Node;
import io.memoryos.connector.sync.SyncRun;
import io.memoryos.connector.sync.SyncTraversal;
import io.memoryos.connector.sync.persistence.JdbcSourceSyncRepository.DueSource;
import io.memoryos.connector.sync.persistence.JdbcSourceSyncRepository.ItemFailure;
import io.memoryos.connector.sync.persistence.SyncTarget;
import io.memoryos.objectstorage.ObjectStorageException;
import io.memoryos.shared.TenantId;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Walks a Google Drive Source: its roots and approved links breadth first through a durable frontier, each
 * file's sharing settings, and its membership in a root. A complete listing prunes what it no longer found;
 * a listing that gave up on a node prunes nothing and releases only what it confirmed.
 */
@Component
public class GoogleDriveSyncTraversal implements SyncTraversal {
    private static final Logger LOGGER = LoggerFactory.getLogger(GoogleDriveSyncTraversal.class);
    private static final int MAX_STEPS = 16;
    private static final int MAX_ANCESTORS = 64;
    private static final String SERVICE_ACCOUNT = "SERVICE_ACCOUNT";
    private static final long EXECUTION_NANOS = Duration.ofSeconds(45).toNanos();

    private final JdbcGoogleDriveSyncRepository google;
    private final JdbcGoogleDriveSourceRepository drive;
    private final JdbcGoogleDriveAclRepository acls;
    private final GoogleDriveConnectionService connections;
    private final GoogleGroupSynchronizer groups;

    public GoogleDriveSyncTraversal(JdbcGoogleDriveSyncRepository google, JdbcGoogleDriveSourceRepository drive,
            JdbcGoogleDriveAclRepository acls, GoogleDriveConnectionService connections,
            GoogleGroupSynchronizer groups) {
        this.google = google;
        this.drive = drive;
        this.acls = acls;
        this.connections = connections;
        this.groups = groups;
    }

    @Override
    public SourceType type() {
        return SourceType.GOOGLE_DRIVE;
    }

    @Override
    public SyncTarget target() {
        return SyncTarget.GOOGLE_DRIVE;
    }

    @Override
    public List<DueSource> due(int limit) {
        return google.due(limit);
    }

    @Override
    public long credentialRevision(TenantId tenant, SourceId source) {
        return connections.state(tenant, source).credentialRevision();
    }

    @Override
    public boolean credentialCurrent(TenantId tenant, SourceId source, long credentialRevision) {
        return connections.current(tenant, source, credentialRevision);
    }

    @Override
    public RunFailure classify(RuntimeException failure) {
        if (failure instanceof GoogleDriveProviderException provider) {
            String code = "SOURCE_GOOGLE_" + provider.failure().name();
            if (provider.requiresReconnect()) return RunFailure.reconnect(code);
            return RunFailure.retry(code, provider.retryAfter());
        }
        if (failure instanceof BusinessException) return RunFailure.fail("SOURCE_GOOGLE_CONNECTION_UNAVAILABLE");
        return RunFailure.retry("SOURCE_GOOGLE_INTERNAL", null);
    }

    @Override
    public void authenticationFailed(Work work) {
        connections.authenticationFailed(work.tenantId(), work.sourceId(), work.credentialRevision());
    }

    @Override
    public Slice walk(SyncRun run) {
        var work = run.work();
        var scopeMode = run.fenced(_ -> drive.scopeMode(work.tenantId(), work.sourceId()));
        var credential = run.fenced(_ -> connections.state(work.tenantId(), work.sourceId()));
        if (scopeMode.isEmpty() || credential.isEmpty()) return Slice.STOPPED;
        try (var connection = connections.open(work.tenantId(), work.sourceId())) {
            if (connection.credentialRevision() != work.credentialRevision()) return run.stop();
            var session = connection.session();
            Set<String> roots = new HashSet<>();
            drive.roots(work.tenantId(), work.sourceId()).forEach(root -> roots.add(root.id()));
            Set<String> approved = Set.copyOf(drive.approvedIds(work.tenantId(), work.sourceId()));
            String generalRoot = scopeMode.get() == ScopeMode.GENERAL ? verifyGeneralRoot(session, roots) : null;
            var walk = new Walk(run, session, roots, approved, generalRoot);
            var groupSync = new GroupSync(work, credential.get(), session);
            long deadline = System.nanoTime() + EXECUTION_NANOS;
            for (int step = 0; step < MAX_STEPS && System.nanoTime() < deadline; step++) {
                groupSync.advance();
                var slice = walk.step();
                if (run.stopped()) return Slice.STOPPED;
                if (slice != null) {
                    while (System.nanoTime() < deadline && groupSync.advance()) { }
                    return slice;
                }
            }
        }
        return Slice.CONTINUE;
    }

    private static String verifyGeneralRoot(GoogleDriveProvider.Session session, Set<String> roots) {
        var current = GoogleDriveRootValidation.resolveMyDriveRoot(session);
        if (roots.size() != 1 || !roots.contains(current.id())) {
            throw new GoogleDriveProviderException(Failure.INCONSISTENT);
        }
        return current.id();
    }

    /** One slice's view of the frontier. */
    private final class Walk {
        private final SyncRun run;
        private final Work work;
        private final GoogleDriveProvider.Session session;
        private final Set<String> roots;
        private final Set<String> approved;
        private final @Nullable String generalRoot;
        /** Whether the node being read already recorded its sharing settings, which a later failure keeps. */
        private boolean aclRecorded;

        Walk(SyncRun run, GoogleDriveProvider.Session session, Set<String> roots, Set<String> approved,
                @Nullable String generalRoot) {
            this.run = run;
            this.work = run.work();
            this.session = session;
            this.roots = roots;
            this.approved = approved;
            this.generalRoot = generalRoot;
        }

        /** Reads one node, or ends the run once the frontier is empty; null while the slice goes on. */
        @Nullable Slice step() {
            if ("START".equals(google.phase(work))) {
                run.fenced(_ -> {
                    google.start(work);
                    return true;
                });
                return null;
            }
            var pending = google.next(work);
            if (pending.isPresent()) {
                read(pending.get());
                return run.aborted() ? Slice.ABORTED : null;
            }
            if (generalRoot != null) verifyGeneralRoot(session, roots);
            return run.fenced(pair -> {
                if (google.hasFailedNodes(work)) {
                    // A node the run gave up on hides what lies beneath it: remove nothing, release what was confirmed.
                    google.releaseConfirmed(work);
                    run.complete();
                    return Slice.COMPLETED;
                }
                var missing = google.pruneCandidates(work);
                missing.forEach(item -> run.remove(pair, item));
                run.removed(missing.size());
                if (!missing.isEmpty()) return null;
                google.releaseListed(work);
                run.complete();
                return Slice.COMPLETED;
            }).orElse(null);
        }

        private void read(Node node) {
            boolean generalRootNode = node.fileId().equals(generalRoot);
            aclRecorded = false;
            GoogleDriveProvider.FileMetadata file = null;
            try {
                file = session.metadata(node.fileId());
                if (generalRootNode) {
                    GoogleDriveRootValidation.requireMyDriveRoot(file);
                    if (!node.fileId().equals(file.id())) throw new GoogleDriveProviderException(Failure.INCONSISTENT);
                }
                if (file.trashed()) {
                    absent(node, file, "SOURCE_GOOGLE_TRASHED");
                    return;
                }
                if (approved.contains(file.id()) && file.folder() && !roots.contains(file.id()))
                    throw new GoogleDriveProviderException(Failure.UNSUPPORTED);
                String root = approved.contains(file.id()) && !file.folder() ? file.id() : membership(file);
                if (root == null || google.excluded(work, file.id())) {
                    absent(node, file, "SOURCE_GOOGLE_OUT_OF_SCOPE");
                    return;
                }
                if (file.shortcutTargetId() != null || "application/vnd.google-apps.shortcut".equals(file.mimeType()))
                    throw new GoogleDriveProviderException(Failure.UNSUPPORTED);
                if (!generalRootNode && file.folder() && roots.contains(file.id())
                        && (file.id().equals(file.driveId())
                        || file.driveId() == null && file.parents().isEmpty()
                        && file.id().equals(session.metadata("root").id()))) {
                    throw new GoogleDriveProviderException(Failure.UNSUPPORTED);
                }
                if ("FOLDER".equals(node.kind())) {
                    list(node, file, root);
                } else if (file.folder()) {
                    var acl = sharing(file);
                    var folder = file;
                    run.fenced(_ -> {
                        acl.record(work, folder.id(), acls);
                        aclRecorded = true;
                        google.observe(work, folder.id(), root, folder.version());
                        google.enqueueNode(work, folder.id(), "FOLDER");
                        google.checkpoint(work, node, null);
                        return true;
                    });
                } else {
                    leaf(node, file, root);
                }
            } catch (GoogleDriveProviderException exception) {
                if (generalRootNode || exception.requiresReconnect()
                        || exception.failure() == Failure.QUOTA || exception.failure() == Failure.UNAVAILABLE) {
                    if (!aclRecorded) recordAclFailure(node.fileId(), exception);
                    throw exception;
                }
                if (exception.failure() == Failure.NOT_FOUND) {
                    absent(node, file, "SOURCE_GOOGLE_NOT_FOUND");
                } else {
                    failed(node, file, "SOURCE_GOOGLE_" + exception.failure().name(),
                            exception.failure() == Failure.UNSUPPORTED, !aclRecorded, exception);
                }
            } catch (ObjectStorageException exception) {
                failed(node, file, "SOURCE_STORAGE_WRITE_" + SourceStorageFailure.code(exception), false, false,
                        exception);
            } catch (BusinessException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                if (generalRootNode) throw exception;
                LOGGER.atWarn().addKeyValue("event", "google_drive.sync.node.failed")
                        .addKeyValue("source_id", work.sourceId().value())
                        .addKeyValue("error_type", exception.getClass().getName())
                        .log("Google Drive item failed unexpectedly; recorded for retry");
                failed(node, file, "SOURCE_ACQUISITION_INTERNAL", false, false, exception);
            }
        }

        /** One page of a folder's children, which join the frontier. */
        private void list(Node node, GoogleDriveProvider.FileMetadata folder, String root) {
            var page = session.listFiles(folder.id(), node.pageToken());
            if (page.nextPageToken() != null && page.nextPageToken().equals(node.pageToken()))
                throw new GoogleDriveProviderException(Failure.MALFORMED);
            run.fenced(_ -> {
                google.observe(work, folder.id(), root, folder.version());
                page.files().forEach(child -> {
                    google.enqueueNode(work, child.id(), "FILE");
                    if (!child.folder()) run.observed(child.id(), child.name());
                });
                google.checkpoint(work, node, page.nextPageToken());
                return true;
            });
        }

        /**
         * A file: its sharing settings and whether the held version is current are recorded in one fence; only
         * a changed file is downloaded, and it is recognised or adopted in a second.
         */
        private void leaf(Node node, GoogleDriveProvider.FileMetadata file, String root) {
            var acl = sharing(file);
            boolean unchanged = run.fenced(_ -> {
                run.observed(file.id(), file.name());
                acl.record(work, file.id(), acls);
                aclRecorded = true;
                if (!run.unchanged(file.id(), file.version())) return false;
                google.observe(work, file.id(), root, file.version());
                google.checkpoint(work, node, null);
                return true;
            }).orElse(true);
            if (unchanged) return;
            var content = session.acquire(file);
            if (!file.id().equals(content.descriptor().providerFileId())
                    || !file.version().equals(content.descriptor().providerVersion()))
                throw new GoogleDriveProviderException(Failure.INCONSISTENT);
            var acquired = run.acquire(new SyncRun.Content(content.descriptor(), content.filename(),
                    content.mediaType(), content.descriptor().format() != SourceInputFormat.BINARY, content.bytes()),
                    new SyncRun.AcquireHooks() {
                        @Override
                        public boolean inScope() {
                            return !google.excluded(work, file.id());
                        }

                        @Override
                        public void recorded(boolean adopted) {
                            google.observe(work, file.id(), root, file.version());
                            google.checkpoint(work, node, null);
                        }
                    });
            if (acquired == SyncRun.Acquired.OUT_OF_SCOPE) absent(node, file, "SOURCE_GOOGLE_OUT_OF_SCOPE");
        }

        /**
         * Reads a file's sharing settings. A failure is recorded on the snapshot and the file is still read,
         * except that a revoked credential stops the run.
         */
        private Acl sharing(GoogleDriveProvider.FileMetadata file) {
            try {
                var permissions = session.permissions(file.id());
                if (!file.version().equals(session.metadata(file.id()).version()))
                    throw new GoogleDriveProviderException(Failure.INCONSISTENT);
                return new Acl(permissions, null);
            } catch (GoogleDriveProviderException exception) {
                if (exception.requiresReconnect()) {
                    recordAclFailure(file.id(), exception);
                    aclRecorded = true;
                    throw exception;
                }
                return new Acl(null, exception);
            }
        }

        private void recordAclFailure(String fileId, GoogleDriveProviderException exception) {
            run.fenced(_ -> {
                acls.recordFailure(work, fileId, "SOURCE_GOOGLE_" + exception.failure().name(), exception.getMessage());
                return true;
            });
        }

        /** The file is no longer part of the Source: trashed, gone, outside every root or excluded. */
        private void absent(Node node, GoogleDriveProvider.@Nullable FileMetadata file, String errorCode) {
            run.fenced(_ -> {
                if (file != null && !file.folder()) run.observed(file.id(), file.name());
                run.skipped(node.fileId());
                acls.recordFailure(work, node.fileId(), errorCode, null);
                google.observe(work, node.fileId(), null, null);
                google.checkpoint(work, node, null);
                return true;
            });
        }

        /** Counts a failed read; after its in-run retries the node becomes an item error of the run. */
        private void failed(Node node, GoogleDriveProvider.@Nullable FileMetadata file, String code,
                boolean unsupported, boolean recordAcl, RuntimeException exception) {
            run.fenced(_ -> {
                if (file != null && !file.folder()) run.observed(file.id(), file.name());
                if (recordAcl) acls.recordFailure(work, node.fileId(), code, exception.getMessage());
                String state = google.failNode(work, node, code, unsupported);
                if ("FAILED".equals(state) || "UNSUPPORTED".equals(state)) {
                    run.itemFailed(new ItemFailure(node.kind() + ":" + node.fileId(), node.fileId(),
                            file == null ? null : file.name(), code, exception.getMessage(),
                            FailureEvidence.detail(exception), unsupported));
                }
                return true;
            });
        }

        /**
         * The root a file belongs to, walking its ancestors. A folder this run already placed answers from the
         * membership it recorded, so Drive is asked only about ancestors the run has not listed.
         */
        private @Nullable String membership(GoogleDriveProvider.FileMetadata file) {
            if (roots.contains(file.id())) return file.id();
            var level = new ArrayList<>(file.parents());
            var visited = new HashSet<String>();
            long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
            while (!level.isEmpty()) {
                for (String parent : level) if (roots.contains(parent)) return parent;
                level.removeIf(parent -> !visited.add(parent));
                if (level.isEmpty()) return null;
                var placed = google.placed(work, level);
                for (String parent : level) {
                    var known = placed.get(parent);
                    if (known != null && known.isPresent()) return known.get();
                }
                var next = new ArrayList<String>();
                for (String parent : level) {
                    if (placed.containsKey(parent)) continue;
                    if (visited.size() > MAX_ANCESTORS || System.nanoTime() >= deadline)
                        throw new GoogleDriveProviderException(Failure.LIMIT_EXCEEDED);
                    var metadata = session.metadata(parent);
                    if (metadata.trashed()) return null;
                    next.addAll(metadata.parents());
                }
                level = next;
            }
            return null;
        }
    }

    /** A file's sharing settings as read, or why they could not be read. */
    private record Acl(@Nullable List<GoogleDriveProvider.Permission> permissions,
                       @Nullable GoogleDriveProviderException failure) {
        void record(Work work, String fileId, JdbcGoogleDriveAclRepository acls) {
            if (failure == null) acls.recordSuccess(work, fileId, Objects.requireNonNull(permissions));
            else acls.recordFailure(work, fileId, "SOURCE_GOOGLE_" + failure.failure().name(), failure.getMessage());
        }
    }

    /**
     * Google Group membership of a service-account credential rides along the Drive sync steps one Directory page
     * at a time; it stops asking once nothing is due during this execution.
     */
    private final class GroupSync {
        private final Work work;
        private final GoogleDriveConnectionService.State credential;
        private final GoogleDriveProvider.Session session;
        private boolean pending;

        GroupSync(Work work, GoogleDriveConnectionService.State credential, GoogleDriveProvider.Session session) {
            this.work = work;
            this.credential = credential;
            this.session = session;
            this.pending = SERVICE_ACCOUNT.equals(credential.authMethod())
                    && credential.credentialRevision() == work.credentialRevision();
        }

        boolean advance() {
            if (pending) {
                pending = groups.advance(work.tenantId(), credential.credentialId(), work.credentialRevision(),
                        credential.accountEmail(), session);
            }
            return pending;
        }
    }
}
