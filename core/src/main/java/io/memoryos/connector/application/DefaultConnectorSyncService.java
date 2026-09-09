package io.memoryos.connector.application;

import io.memoryos.connector.ConnectorSyncPort;
import io.memoryos.connector.GoogleDriveConnectionService;
import io.memoryos.connector.GoogleDriveProvider;
import io.memoryos.connector.GoogleDriveProviderException;
import io.memoryos.connector.GoogleDriveSourceService.ScopeMode;
import io.memoryos.connector.SourceException;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceInputFormat;
import io.memoryos.connector.SourceOperationId;
import io.memoryos.connector.SourceOperationType;
import io.memoryos.connector.SourceRunTrigger;
import io.memoryos.connector.SourceStorageFailure;
import io.memoryos.connector.persistence.JdbcGoogleDriveSourceRepository;
import io.memoryos.connector.persistence.JdbcIndexAttemptRepository;
import io.memoryos.connector.persistence.JdbcSourceDocumentRepository;
import io.memoryos.connector.persistence.JdbcSourceItemRepository;
import io.memoryos.connector.persistence.JdbcSourceRepository;
import io.memoryos.connector.persistence.JdbcSourceSyncRepository;
import io.memoryos.connector.persistence.JdbcSourceSyncRepository.Node;
import io.memoryos.objectstorage.ObjectWriteService;
import io.memoryos.objectstorage.ObjectStorageException;
import io.memoryos.tenant.TenantId;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class DefaultConnectorSyncService implements ConnectorSyncPort {
    private static final int MAX_STEPS = 16;
    private static final long EXECUTION_NANOS = Duration.ofSeconds(45).toNanos();
    private final JdbcSourceSyncRepository sync;
    private final JdbcSourceRepository sources;
    private final JdbcGoogleDriveSourceRepository drive;
    private final JdbcSourceItemRepository items;
    private final JdbcIndexAttemptRepository indexing;
    private final JdbcSourceDocumentRepository documents;
    private final GoogleDriveConnectionService connections;
    private final ObjectWriteService writes;
    private final TransactionTemplate transactions;

    public DefaultConnectorSyncService(JdbcSourceSyncRepository sync, JdbcSourceRepository sources,
            JdbcGoogleDriveSourceRepository drive, JdbcSourceItemRepository items,
            JdbcIndexAttemptRepository indexing, JdbcSourceDocumentRepository documents,
            GoogleDriveConnectionService connections, ObjectWriteService writes, PlatformTransactionManager manager) {
        this.sync = sync;
        this.sources = sources;
        this.drive = drive;
        this.items = items;
        this.indexing = indexing;
        this.documents = documents;
        this.connections = connections;
        this.writes = writes;
        this.transactions = new TransactionTemplate(manager);
    }

    @Override
    public Optional<Work> claim(TenantId tenant, SourceOperationId operation, UUID deliveryId) {
        return Objects.requireNonNull(transactions.execute(_ -> sync.claim(tenant, operation, deliveryId)));
    }

    @Override
    public boolean renew(Work work) {
        return Boolean.TRUE.equals(transactions.execute(_ -> sync.renew(work)));
    }

    @Override
    public int enqueueDue(int limit) {
        int count = 0;
        for (var due : sync.due(limit)) {
            try {
                boolean accepted = Boolean.TRUE.equals(transactions.execute(_ -> {
                    sources.lock(due.tenantId(), due.sourceId());
                    var state = connections.state(due.tenantId(), due.sourceId());
                    sync.postpone(due.tenantId(), due.sourceId());
                    if (!connections.current(due.tenantId(), due.sourceId(), state.credentialRevision())) return false;
                    sync.enqueue(due.tenantId(), due.sourceId(), state.credentialRevision(), SourceRunTrigger.SCHEDULED, null);
                    return true;
                }));
                if (accepted) count++;
            } catch (io.memoryos.BusinessException exception) {
                transactions.executeWithoutResult(_ -> sync.postpone(due.tenantId(), due.sourceId()));
            }
        }
        return count;
    }

    @Override
    public Result execute(Work work) {
        try {
            var scopeMode = fenced(work, () -> drive.scopeMode(work.tenantId(), work.sourceId()));
            try (var connection = connections.open(work.tenantId(), work.sourceId())) {
                if (connection.credentialRevision() != work.credentialRevision()) throw new StaleSyncException();
                Set<String> roots = new HashSet<>();
                drive.roots(work.tenantId(), work.sourceId()).forEach(root -> roots.add(root.id()));
                Set<String> approved = Set.copyOf(drive.approvedIds(work.tenantId(), work.sourceId()));
                String generalRoot = scopeMode == ScopeMode.GENERAL
                        ? verifyGeneralRoot(connection.session(), roots) : null;
                long deadline = System.nanoTime() + EXECUTION_NANOS;
                for (int step = 0; step < MAX_STEPS && System.nanoTime() < deadline; step++) {
                    var result = step(work, connection.session(), roots, approved, generalRoot);
                    if (result != null) return result;
                }
            }
            fenced(work, () -> { sync.continuation(work, null); return true; });
            return Result.CONTINUED;
        } catch (StaleSyncException exception) {
            settle(work, () -> sync.terminal(work, "SUPERSEDED", null));
            return Result.SUPERSEDED;
        } catch (GoogleDriveProviderException exception) {
            String code = "SOURCE_GOOGLE_" + exception.failure().name();
            if (exception.failure() == GoogleDriveProviderException.Failure.AUTHENTICATION) {
                connections.authenticationFailed(work.tenantId(), work.sourceId(), work.credentialRevision());
                settle(work, () -> sync.terminal(work, "FAILED", code));
            } else {
                settle(work, () -> sync.retry(work, code));
            }
            return Result.FAILED;
        } catch (io.memoryos.BusinessException exception) {
            settle(work, () -> sync.terminal(work, "FAILED", "SOURCE_GOOGLE_CONNECTION_UNAVAILABLE"));
            return Result.FAILED;
        } catch (RuntimeException exception) {
            settle(work, () -> sync.retry(work, "SOURCE_GOOGLE_INTERNAL"));
            return Result.FAILED;
        }
    }

    private static String verifyGeneralRoot(GoogleDriveProvider.Session session, Set<String> roots) {
        var current = GoogleDriveRootValidation.resolveMyDriveRoot(session);
        if (roots.size() != 1 || !roots.contains(current.id())) {
            throw new GoogleDriveProviderException(GoogleDriveProviderException.Failure.INCONSISTENT);
        }
        return current.id();
    }

    private @Nullable Result step(Work work, GoogleDriveProvider.Session session, Set<String> roots,
            Set<String> approved, @Nullable String generalRoot) {
        if ("START".equals(sync.phase(work))) {
            fenced(work, () -> { sync.start(work); return true; });
            return null;
        }
        var pending = sync.next(work);
        if (pending.isPresent()) {
            processNode(work, pending.get(), session, roots, approved, generalRoot);
            return null;
        }
        if (generalRoot != null) verifyGeneralRoot(session, roots);
        return fenced(work, () -> {
            if (sync.hasFailures(work)) {
                sync.releaseConfirmed(work);
                sync.terminal(work, "FAILED", "SOURCE_GOOGLE_INCOMPLETE");
                return Result.FAILED;
            }
            var missing = sync.pruneCandidates(work);
            var pair = sources.lock(work.tenantId(), work.sourceId());
            for (var item : missing) {
                items.markDeleting(work.tenantId(), pair, item);
                documents.invalidateItem(work.tenantId(), work.sourceId(), item);
                indexing.cancelForItem(work.tenantId(), work.sourceId(), item);
                sources.createCleanup(new SourceOperationId(UUID.randomUUID()), work.tenantId(),
                        SourceOperationType.REMOVE_ITEM, "ITEM:" + item.value(), work.sourceId(), item);
            }
            sync.removed(work, missing.size());
            if (!missing.isEmpty()) return null;
            sync.finish(work);
            sources.recomputeStatus(work.tenantId(), work.sourceId(), false);
            return Result.COMPLETED;
        });
    }


    private void processNode(Work work, Node node, GoogleDriveProvider.Session session, Set<String> roots,
            Set<String> approved, @Nullable String generalRoot) {
        boolean generalRootNode = node.fileId().equals(generalRoot);
        try {
            var file = session.metadata(node.fileId());
            if (!file.folder()) fenced(work, () -> { sync.observeLeaf(work, file.id(), file.name()); return true; });
            if (generalRootNode) {
                GoogleDriveRootValidation.requireMyDriveRoot(file);
                if (!node.fileId().equals(file.id())) {
                    throw new GoogleDriveProviderException(GoogleDriveProviderException.Failure.INCONSISTENT);
                }
            }
            if (file.trashed()) {
                absent(work, node);
                return;
            }
            if (approved.contains(file.id()) && file.folder() && !roots.contains(file.id()))
                throw new GoogleDriveProviderException(GoogleDriveProviderException.Failure.UNSUPPORTED);
            String root = approved.contains(file.id()) && !file.folder() ? file.id() : membership(file, roots, session);
            if (root == null || sync.excluded(work, file.id())) {
                absent(work, node);
                return;
            }
            if (file.shortcutTargetId() != null || "application/vnd.google-apps.shortcut".equals(file.mimeType()))
                throw new GoogleDriveProviderException(GoogleDriveProviderException.Failure.UNSUPPORTED);
            if (!generalRootNode && file.folder() && roots.contains(file.id())
                    && (file.id().equals(file.driveId())
                    || file.driveId() == null && file.parents().isEmpty()
                    && file.id().equals(session.metadata("root").id()))) {
                throw new GoogleDriveProviderException(GoogleDriveProviderException.Failure.UNSUPPORTED);
            }
            if ("FOLDER".equals(node.kind())) {
                var page = session.listFiles(file.id(), node.pageToken());
                if (page.nextPageToken() != null && page.nextPageToken().equals(node.pageToken()))
                    throw new GoogleDriveProviderException(GoogleDriveProviderException.Failure.MALFORMED);
                fenced(work, () -> {
                    sync.observe(work, file.id(), root, file.version());
                    page.files().forEach(child -> {
                        sync.enqueueNode(work, child.id(), "FILE");
                        if (!child.folder()) sync.observeLeaf(work, child.id(), child.name());
                    });
                    sync.checkpoint(work, node, page.nextPageToken());
                    return true;
                });
            } else if (file.folder()) {
                fenced(work, () -> {
                    sync.observe(work, file.id(), root, file.version());
                    sync.enqueueNode(work, file.id(), "FOLDER");
                    sync.checkpoint(work, node, null);
                    return true;
                });
            } else {
                acquire(work, node, file, root, session);
            }
        } catch (GoogleDriveProviderException exception) {
            if (generalRootNode || exception.failure() == GoogleDriveProviderException.Failure.AUTHENTICATION) throw exception;
            if (exception.failure() == GoogleDriveProviderException.Failure.NOT_FOUND) {
                absent(work, node);
            } else {
                fenced(work, () -> {
                    sync.failedNode(work, node, "SOURCE_GOOGLE_" + exception.failure().name(),
                            exception.failure() == GoogleDriveProviderException.Failure.UNSUPPORTED);
                    return true;
                });
            }
        } catch (StaleSyncException exception) {
            throw exception;
        } catch (ObjectStorageException exception) {
            String code = "SOURCE_STORAGE_WRITE_" + SourceStorageFailure.code(exception);
            fenced(work, () -> { sync.failedNode(work, node, code, false); return true; });
        } catch (RuntimeException exception) {
            if (generalRootNode) throw exception;
            fenced(work, () -> { sync.failedNode(work, node, "SOURCE_ACQUISITION_INTERNAL", false); return true; });
        }
    }

    private void acquire(Work work, Node node, GoogleDriveProvider.FileMetadata file, String root,
            GoogleDriveProvider.Session session) {
        boolean unchanged = fenced(work, () -> {
            var version = items.unchanged(work, file.id(), file.version());
            if (version.isEmpty()) return false;
            sync.observe(work, file.id(), root, file.version());
            sync.unchanged(work, file.id(), indexing.findLive(work.tenantId(), work.sourceId(), version.get()).isPresent());
            sync.checkpoint(work, node, null);
            return true;
        });
        if (unchanged) return;
        var content = session.acquire(file);
        if (!file.id().equals(content.descriptor().providerFileId()) || !file.version().equals(content.descriptor().providerVersion()))
            throw new GoogleDriveProviderException(GoogleDriveProviderException.Failure.INCONSISTENT);
        var staged = writes.stage(work.tenantId(), new ObjectWriteService.Specification(content.filename(),
                content.mediaType(), content.descriptor().format() != SourceInputFormat.BINARY), content.bytes());
        boolean adopted = false;
        try {
            adopted = fenced(work, () -> {
                if (sync.excluded(work, file.id())) throw new StaleSyncException();
                var pair = sources.lock(work.tenantId(), work.sourceId());
                sync.observe(work, file.id(), root, file.version());
                writes.adopt(work.tenantId(), staged);
                var version = items.acceptRemote(work, pair, staged.object(), content.descriptor());
                indexing.cancelForItem(work.tenantId(), work.sourceId(), version.itemId());
                documents.invalidateItem(work.tenantId(), work.sourceId(), version.itemId());
                indexing.create(work.tenantId(), pair, version, work.operationId());
                sync.acquired(work, file.id());
                sync.checkpoint(work, node, null);
                return true;
            });
        } finally {
            if (!adopted) writes.discard(work.tenantId(), staged);
        }
    }

    private void absent(Work work, Node node) {
        fenced(work, () -> {
            sync.observe(work, node.fileId(), null, null);
            sync.skipped(work, node.fileId());
            sync.checkpoint(work, node, null);
            return true;
        });
    }

    private static @Nullable String membership(GoogleDriveProvider.FileMetadata file, Set<String> roots,
            GoogleDriveProvider.Session session) {
        if (roots.contains(file.id())) return file.id();
        var pending = new ArrayDeque<>(file.parents());
        var visited = new HashSet<String>();
        long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        while (!pending.isEmpty()) {
            String parent = pending.removeFirst();
            if (roots.contains(parent)) return parent;
            if (!visited.add(parent)) continue;
            if (visited.size() > 64 || System.nanoTime() >= deadline)
                throw new GoogleDriveProviderException(GoogleDriveProviderException.Failure.LIMIT_EXCEEDED);
            var metadata = session.metadata(parent);
            if (metadata.trashed()) return null;
            pending.addAll(metadata.parents());
        }
        return null;
    }

    private <T> T fenced(Work work, Supplier<T> action) {
        return transactions.execute(_ -> {
            if (!lockSource(work) || !connections.current(work.tenantId(), work.sourceId(), work.credentialRevision()) || !sync.current(work))
                throw new StaleSyncException();
            return action.get();
        });
    }

    private void settle(Work work, Runnable action) {
        transactions.executeWithoutResult(_ -> {
            if (lockSource(work)) action.run();
        });
    }

    private boolean lockSource(Work work) {
        try {
            sources.lock(work.tenantId(), work.sourceId());
            return true;
        } catch (SourceException exception) {
            if ("SOURCE_NOT_FOUND".equals(exception.code())) return false;
            throw exception;
        }
    }

    private static final class StaleSyncException extends RuntimeException {}
}
