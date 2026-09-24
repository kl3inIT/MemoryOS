package io.memoryos.connector.application;

import io.memoryos.connector.ConnectorSyncPort.Result;
import io.memoryos.connector.ConnectorSyncPort.Work;
import io.memoryos.connector.SharePointConnectionService;
import io.memoryos.connector.SharePointGlob;
import io.memoryos.connector.SharePointProvider;
import io.memoryos.connector.SharePointProviderException;
import io.memoryos.connector.SharePointSourceService.RootKind;
import io.memoryos.connector.SourceException;
import io.memoryos.connector.SourceInputDescriptor;
import io.memoryos.connector.SourceInputFormat;
import io.memoryos.connector.SourceItemId;
import io.memoryos.connector.SourceOperationId;
import io.memoryos.connector.SourceOperationType;
import io.memoryos.connector.SourceRunTrigger;
import io.memoryos.connector.SourceStorageFailure;
import io.memoryos.connector.persistence.JdbcIndexAttemptRepository;
import io.memoryos.connector.persistence.JdbcSharePointSourceRepository;
import io.memoryos.connector.persistence.JdbcSharePointSourceRepository.ResolvedRoot;
import io.memoryos.connector.persistence.JdbcSharePointSyncRepository;
import io.memoryos.connector.persistence.JdbcSharePointSyncRepository.Run;
import io.memoryos.connector.persistence.JdbcSourceDocumentRepository;
import io.memoryos.connector.persistence.JdbcSourceItemRepository;
import io.memoryos.connector.persistence.JdbcSourceRepository;
import io.memoryos.objectstorage.ObjectStorageException;
import io.memoryos.objectstorage.ObjectWriteService;
import io.memoryos.iam.tenant.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Synchronizes a SharePoint Source. A refresh reads each library's change log since the previous window and
 * applies what it reports, including the tombstones that say an item is gone. A prune lists the whole scope
 * and removes what it no longer finds, but only when the listing finished, so a site that fails to answer
 * never causes its documents to be deleted.
 */
@Service
public class DefaultSharePointSyncService {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultSharePointSyncService.class);
    private static final int MAX_STEPS = 16;
    private static final long EXECUTION_NANOS = Duration.ofSeconds(45).toNanos();
    private static final int MAX_CONTENT_BYTES = 100 * 1024 * 1024;
    // A page snapshot is stored as JSON, like the other native snapshots; its input format identifies the reader.
    private static final String PAGE_MEDIA_TYPE = "application/json";

    private final JdbcSharePointSyncRepository runs;
    private final JdbcSharePointSourceRepository sharePoint;
    private final JdbcSourceRepository sources;
    private final JdbcSourceItemRepository items;
    private final JdbcIndexAttemptRepository indexing;
    private final JdbcSourceDocumentRepository documents;
    private final SharePointConnectionService connections;
    private final ObjectWriteService writes;
    private final TransactionTemplate transactions;

    public DefaultSharePointSyncService(JdbcSharePointSyncRepository runs, JdbcSharePointSourceRepository sharePoint,
            JdbcSourceRepository sources, JdbcSourceItemRepository items, JdbcIndexAttemptRepository indexing,
            JdbcSourceDocumentRepository documents, SharePointConnectionService connections,
            ObjectWriteService writes, PlatformTransactionManager manager) {
        this.runs = runs;
        this.sharePoint = sharePoint;
        this.sources = sources;
        this.items = items;
        this.indexing = indexing;
        this.documents = documents;
        this.connections = connections;
        this.writes = writes;
        this.transactions = new TransactionTemplate(manager);
    }

    public int enqueueDue(int limit) {
        int count = 0;
        for (var due : runs.due(limit)) {
            try {
                boolean accepted = Boolean.TRUE.equals(transactions.execute(_ -> {
                    sources.lock(due.tenantId(), due.sourceId());
                    if (!runs.automaticSyncEnabled(due.tenantId(), due.sourceId())) return false;
                    var state = connections.state(due.tenantId(), due.sourceId());
                    runs.scheduleNextSync(due.tenantId(), due.sourceId());
                    runs.enqueue(due.tenantId(), due.sourceId(), state.credentialRevision(),
                            SourceRunTrigger.SCHEDULED, null);
                    return true;
                }));
                if (accepted) count++;
            } catch (io.memoryos.BusinessException exception) {
                transactions.executeWithoutResult(_ -> runs.postpone(due.tenantId(), due.sourceId()));
            }
        }
        return count;
    }

    public Result execute(Work work) {
        try {
            var state = fenced(work, () -> runs.state(work.tenantId(), work.sourceId()));
            try (var connection = connections.open(work.tenantId(), work.sourceId())) {
                if (connection.credentialRevision() != work.credentialRevision()) throw new StaleSyncException();
                var run = fenced(work, () -> runs.openRun(work, state.pruneDue() ? "PRUNE" : "REFRESH",
                        state.pruneDue() ? null : windowStart(state.refreshWindowEnd()), Instant.now()));
                log("sharepoint.sync.run.started", run, null);
                String tenantHost = connection.tenantHost() == null
                        ? connection.session().root().hostname() : connection.tenantHost();
                return walk(work, run, connection.session(), tenantHost);
            }
        } catch (StaleSyncException exception) {
            settle(work, () -> runs.terminal(work, "SUPERSEDED", null, null, null));
            return Result.SUPERSEDED;
        } catch (RetryScheduledException exception) {
            // The attempt is already queued again; its run stays open so the retry resumes from the checkpoint.
            return Result.FAILED;
        } catch (SharePointProviderException exception) {
            String code = "SOURCE_SHAREPOINT_" + exception.failure().name();
            if (exception.failure() == SharePointProviderException.Failure.AUTHENTICATION) {
                settle(work, () -> connections.authenticationFailed(work.tenantId(),
                        sharePoint.credentialId(work.tenantId(), work.sourceId()), work.credentialRevision()));
                settle(work, () -> runs.terminal(work, "FAILED", code, exception.getMessage(),
                        io.memoryos.FailureEvidence.detail(exception)));
            } else {
                settle(work, () -> runs.retry(work, code));
            }
            return Result.FAILED;
        } catch (io.memoryos.BusinessException exception) {
            settle(work, () -> runs.terminal(work, "FAILED", "SOURCE_SHAREPOINT_CONNECTION_UNAVAILABLE",
                    exception.getMessage(), io.memoryos.FailureEvidence.detail(exception)));
            return Result.FAILED;
        } catch (RuntimeException exception) {
            LOGGER.atWarn().addKeyValue("event", "sharepoint.sync.run.failed")
                    .addKeyValue("source_id", work.sourceId().value())
                    .setCause(exception).log("SharePoint synchronization run failed unexpectedly");
            settle(work, () -> runs.retry(work, "SOURCE_SHAREPOINT_INTERNAL"));
            return Result.FAILED;
        }
    }

    /** Walks the drives in scope, then its site pages, one page of results per step. */
    private Result walk(Work work, Run run, SharePointProvider.Session session, String tenantHost) {
        var excludedPaths = SharePointGlob.all(sharePoint.exclusions(work.tenantId(), work.sourceId(), "PATH"));
        var scope = scope(work, run, session);
        // A Source that collects only pages walks no library; a prune then removes files it held before.
        var targets = scope.includeDocuments() ? scope.drives() : List.<Target>of();
        long deadline = System.nanoTime() + EXECUTION_NANOS;
        String cursorDrive = run.checkpointDriveId();
        String cursorLink = run.checkpointLink();
        int steps = 0;
        // A checkpoint with a site but no drive means the drives are done and the pages are in progress.
        boolean drivesDone = cursorDrive == null && run.checkpointSiteId() != null;
        for (Target target : drivesDone ? List.<Target>of() : targets) {
            if (cursorDrive != null && target.driveId().compareTo(cursorDrive) < 0) continue;
            boolean resuming = target.driveId().equals(cursorDrive);
            String link = resuming ? cursorLink : null;
            // A folder root is walked breadth first, as Onyx does: its subfolders queue behind it.
            String folder = target.itemId() == null ? null
                    : resuming && run.checkpointFolderId() != null ? run.checkpointFolderId() : target.itemId();
            var queue = new java.util.ArrayDeque<String>(resuming ? run.checkpointFolders() : List.of());
            while (true) {
                if (steps++ >= MAX_STEPS || System.nanoTime() >= deadline) {
                    String pending = link;
                    String current = folder;
                    var waiting = List.copyOf(queue);
                    fenced(work, () -> {
                        runs.checkpoint(run, target.driveId(), target.siteId(), pending, current, waiting);
                        runs.continuation(work, null);
                        return true;
                    });
                    return Result.CONTINUED;
                }
                var listed = page(work, run, session, target, folder, link, excludedPaths, tenantHost);
                for (String subfolder : listed.folders()) {
                    if (!queue.contains(subfolder)) queue.add(subfolder);
                }
                link = listed.nextLink();
                if (link != null) continue;
                if (queue.isEmpty()) break;
                folder = queue.poll();
            }
            String next = target.driveId();
            fenced(work, () -> {
                runs.checkpoint(run, next, target.siteId(), null);
                return true;
            });
            cursorDrive = null;
            cursorLink = null;
        }
        var pending = pages(work, run, session, scope, drivesDone ? run.checkpointSiteId() : null,
                drivesDone ? run.checkpointLink() : null, steps, deadline);
        return pending == null ? finish(work, run) : pending;
    }

    /**
     * Site pages, when the Source collects them. Pages have no change log, so a refresh compares each page's
     * version against what is held rather than asking Microsoft what changed.
     */
    private @Nullable Result pages(Work work, Run run, SharePointProvider.Session session, ScopeTargets scope,
            @Nullable String resumeSite, @Nullable String resumeLink, int steps, long deadline) {
        if (!scope.includePages()) return null;
        boolean resuming = resumeSite != null;
        for (String siteId : scope.sites()) {
            if (resuming && !siteId.equals(resumeSite)) continue;
            String link = resuming ? resumeLink : null;
            resuming = false;
            do {
                if (steps++ >= MAX_STEPS || System.nanoTime() >= deadline) {
                    String pendingLink = link;
                    fenced(work, () -> {
                        runs.checkpoint(run, null, siteId, pendingLink);
                        runs.continuation(work, null);
                        return true;
                    });
                    return Result.CONTINUED;
                }
                var listed = session.pages(siteId, link);
                for (var metadata : listed.pages()) {
                    if (!insideWindow(run, metadata.lastModifiedAt())) continue;
                    fenced(work, () -> {
                        runs.observe(work.tenantId(), work.sourceId(), metadata.pageId(), "PAGE", null, siteId,
                                metadata.title(), metadata.webUrl(), metadata.contentVersion(), metadata.eTag(), 0,
                                run.prune() ? run.id() : null);
                        runs.counted(work, "scanned", 1);
                        return true;
                    });
                    if (!run.prune()) acquirePage(work, session, siteId, metadata);
                }
                link = listed.nextLink();
            } while (link != null);
            fenced(work, () -> {
                runs.checkpoint(run, null, siteId, null);
                return true;
            });
        }
        return null;
    }

    private void acquirePage(Work work, SharePointProvider.Session session, String siteId,
            SharePointProvider.SitePageMetadata metadata) {
        boolean unchanged = fenced(work, () -> {
            if (items.unchanged(work, metadata.pageId(), metadata.contentVersion()).isEmpty()) return false;
            runs.counted(work, "unchanged", 1);
            return true;
        });
        if (unchanged) return;
        try {
            var page = session.page(siteId, metadata.pageId());
            var descriptor = new SourceInputDescriptor(SourceInputFormat.SHAREPOINT_PAGE, metadata.pageId(),
                    page.metadata().contentVersion(), metadata.webUrl());
            var staged = writes.stage(work.tenantId(), new ObjectWriteService.Specification(
                    metadata.title() + ".json", PAGE_MEDIA_TYPE, true), page.snapshot());
            boolean adopted = false;
            try {
                adopted = fenced(work, () -> {
                    var pair = sources.lock(work.tenantId(), work.sourceId());
                    writes.adopt(work.tenantId(), staged);
                    var version = items.acceptRemote(work, pair, staged.object(), descriptor);
                    indexing.cancelForItem(work.tenantId(), work.sourceId(), version.itemId());
                    indexing.create(work.tenantId(), pair, version, work.operationId());
                    runs.counted(work, "acquired", 1);
                    return true;
                });
            } finally {
                if (!adopted) writes.discard(work.tenantId(), staged);
            }
        } catch (SharePointProviderException exception) {
            if (exception.failure() == SharePointProviderException.Failure.AUTHENTICATION
                    || exception.failure() == SharePointProviderException.Failure.QUOTA
                    || exception.failure() == SharePointProviderException.Failure.UNAVAILABLE) {
                throw exception;
            }
            // A page whose canvas cannot be read affects only itself.
            fenced(work, () -> {
                runs.counted(work, "acquisition_failed", 1);
                return true;
            });
        }
    }

    /**
     * One page of a library's change log, or of one folder's children when the root is a folder. A folder
     * listing also returns the subfolders it found, which the caller walks next.
     */
    private Listed page(Work work, Run run, SharePointProvider.Session session, Target target,
            @Nullable String folderId, @Nullable String link, List<SharePointGlob> excludedPaths, String tenantHost) {
        List<SharePointProvider.DriveItem> page;
        String next;
        var folders = new ArrayList<String>();
        if (folderId != null) {
            // Graph keeps a change log only for the library root, so a folder's children are listed and filtered by window.
            var children = session.children(target.driveId(), folderId, link);
            page = children.items();
            next = children.nextLink();
        } else {
            SharePointProvider.DeltaPage delta;
            try {
                delta = session.delta(target.driveId(), run.prune() ? null : token(run), link);
            } catch (SharePointProviderException exception) {
                if (exception.failure() != SharePointProviderException.Failure.RESYNC_REQUIRED) throw exception;
                // Microsoft no longer accepts the saved link or window, so the library is read again from its start.
                delta = session.delta(target.driveId(), null, null);
            }
            page = delta.items();
            next = delta.nextLink();
        }
        int scanned = 0;
        for (var item : page) {
            if (item.deleted()) {
                if (!run.prune()) remove(work, item.id());
                continue;
            }
            if (item.folder()) {
                // Folders are always walked: a folder's timestamp does not change when a file deep inside it does.
                if (folderId != null) folders.add(item.id());
                continue;
            }
            if (!item.file()) continue;
            scanned++;
            String path = path(item);
            if (SharePointGlob.excluded(excludedPaths, path)) continue;
            if (folderId != null && !insideWindow(run, item)) continue;
            fenced(work, () -> {
                runs.observe(work.tenantId(), work.sourceId(), item.id(), "FILE", target.driveId(), target.siteId(),
                        item.name(), path, item.contentVersion(), item.eTag(), item.size(),
                        run.prune() ? run.id() : null);
                return true;
            });
            if (!run.prune()) acquire(work, item, session, tenantHost);
        }
        int counted = scanned;
        fenced(work, () -> {
            runs.counted(work, "scanned", counted);
            return true;
        });
        return new Listed(next, folders);
    }

    /** Drives and sites the run covers, ordered so a continued run resumes where it stopped. */
    private ScopeTargets scope(Work work, Run run, SharePointProvider.Session session) {
        var roots = fenced(work, () -> sharePoint.resolvedRoots(work.tenantId(), work.sourceId()));
        var excludedSites = SharePointGlob.all(fenced(work,
                () -> sharePoint.exclusions(work.tenantId(), work.sourceId(), "SITE")));
        var targets = new LinkedHashMap<String, Target>();
        var sites = new java.util.LinkedHashSet<String>();
        var state = fenced(work, () -> runs.state(work.tenantId(), work.sourceId()));
        if (roots.isEmpty()) {
            String link = null;
            do {
                var listed = session.sites(link);
                for (var site : listed.sites()) {
                    if (site.personalSite() || SharePointGlob.excluded(excludedSites, site.webUrl())) continue;
                    sites.add(site.siteId());
                    for (var library : session.libraries(site.siteId())) {
                        targets.putIfAbsent(library.driveId(), new Target(library.driveId(), site.siteId(), null));
                    }
                }
                link = listed.nextLink();
            } while (link != null);
        } else {
            for (ResolvedRoot root : roots) {
                if (root.siteId() != null) sites.add(root.siteId());
                if (root.kind() == RootKind.SITE) {
                    String siteId = Objects.requireNonNull(root.siteId());
                    for (var library : session.libraries(siteId)) {
                        targets.putIfAbsent(library.driveId(), new Target(library.driveId(), siteId, null));
                    }
                } else {
                    String driveId = Objects.requireNonNull(root.driveId());
                    targets.putIfAbsent(driveId, new Target(driveId, root.siteId(), root.itemId()));
                }
            }
        }
        return new ScopeTargets(targets.values().stream().sorted(Comparator.comparing(Target::driveId)).toList(),
                List.copyOf(sites), state.includeDocuments(), state.includePages());
    }

    /**
     * Stable event fields only: the run kind and its outcome. Site addresses, item names and identifiers of
     * what was read never appear here.
     */
    private static void log(String event, Run run, @Nullable String outcome) {
        LOGGER.atInfo().addKeyValue("event", event).addKeyValue("run_kind", run.kind())
                .addKeyValue("source_id", run.sourceId().value())
                .addKeyValue("outcome", outcome == null ? "started" : outcome)
                .log("SharePoint synchronization run");
    }

    /** Ends the run: a refresh records its window, a prune removes what its complete listing did not see. */
    private Result finish(Work work, Run run) {
        if (!run.prune()) {
            return Objects.requireNonNull(fenced(work, () -> {
                runs.listingComplete(run);
                runs.completeRun(run, "SUCCEEDED", null);
                runs.finishRefresh(work, run.windowEnd());
                sources.recomputeStatus(work.tenantId(), work.sourceId(), false);
                log("sharepoint.sync.run.finished", run, "completed");
                return Result.COMPLETED;
            }));
        }
        return Objects.requireNonNull(fenced(work, () -> {
            runs.listingComplete(run);
            var missing = runs.missing(work.tenantId(), work.sourceId(), run.id());
            if (!missing.isEmpty()) {
                removeAll(work, missing);
                return Result.CONTINUED;
            }
            runs.completeRun(run, "SUCCEEDED", null);
            runs.finishPrune(work);
            sources.recomputeStatus(work.tenantId(), work.sourceId(), false);
            log("sharepoint.sync.run.finished", run, "completed");
            return Result.COMPLETED;
        }));
    }

    private void removeAll(Work work, List<JdbcSharePointSyncRepository.Missing> missing) {
        var pair = sources.lock(work.tenantId(), work.sourceId());
        for (var entry : missing) {
            remove(work, pair, entry.itemId());
            runs.forget(work.tenantId(), work.sourceId(), entry.providerFileId());
        }
        runs.counted(work, "removed", missing.size());
        runs.continuation(work, null);
    }

    /** Removes what a tombstone reports, matching by the provider identifier it carries. */
    private void remove(Work work, String providerFileId) {
        fenced(work, () -> {
            var item = runs.item(work.tenantId(), work.sourceId(), providerFileId);
            if (item.isPresent()) {
                remove(work, sources.lock(work.tenantId(), work.sourceId()), item.get());
                runs.counted(work, "removed", 1);
            }
            runs.forget(work.tenantId(), work.sourceId(), providerFileId);
            return true;
        });
    }

    private void remove(Work work, JdbcSourceRepository.SourcePair pair, SourceItemId item) {
        items.markDeleting(work.tenantId(), pair, item);
        documents.invalidateItem(work.tenantId(), work.sourceId(), item);
        indexing.cancelForItem(work.tenantId(), work.sourceId(), item);
        sources.createCleanup(new SourceOperationId(UUID.randomUUID()), work.tenantId(),
                SourceOperationType.REMOVE_ITEM, "ITEM:" + item.value(), work.sourceId(), item, null);
    }

    private void acquire(Work work, SharePointProvider.DriveItem item, SharePointProvider.Session session,
            String tenantHost) {
        if (item.size() > MAX_CONTENT_BYTES) {
            fenced(work, () -> {
                runs.counted(work, "skipped", 1);
                return true;
            });
            return;
        }
        boolean unchanged = fenced(work, () -> {
            var version = items.unchanged(work, item.id(), item.contentVersion());
            if (version.isEmpty()) return false;
            runs.counted(work, "unchanged", 1);
            return true;
        });
        if (unchanged) return;
        try {
            var current = session.item(item.driveId() == null ? "" : item.driveId(), item.id());
            var content = session.content(current, tenantHost, MAX_CONTENT_BYTES);
            var descriptor = new SourceInputDescriptor(SourceInputFormat.BINARY, item.id(), current.contentVersion(),
                    current.webUrl());
            var staged = writes.stage(work.tenantId(), new ObjectWriteService.Specification(content.filename(),
                    content.mediaType(), false), content.bytes());
            boolean adopted = false;
            try {
                boolean sameContent = fenced(work, () -> {
                    var version = items.sameContent(work, item.id(), staged.object().metadata().checksum().value(),
                            staged.object().filename(), descriptor.providerVersion());
                    if (version.isEmpty()) return false;
                    runs.counted(work, "unchanged", 1);
                    return true;
                });
                if (sameContent) return;
                adopted = fenced(work, () -> {
                    var pair = sources.lock(work.tenantId(), work.sourceId());
                    writes.adopt(work.tenantId(), staged);
                    var version = items.acceptRemote(work, pair, staged.object(), descriptor);
                    indexing.cancelForItem(work.tenantId(), work.sourceId(), version.itemId());
                    // The current Document stays retrievable until the new version publishes over the same mapping.
                    indexing.create(work.tenantId(), pair, version, work.operationId());
                    runs.counted(work, "acquired", 1);
                    return true;
                });
            } finally {
                if (!adopted) writes.discard(work.tenantId(), staged);
            }
        } catch (ObjectStorageException exception) {
            String code = "SOURCE_STORAGE_WRITE_" + SourceStorageFailure.code(exception);
            fenced(work, () -> {
                runs.counted(work, "acquisition_failed", 1);
                runs.retry(work, code);
                return true;
            });
            throw new RetryScheduledException();
        } catch (SharePointProviderException exception) {
            if (exception.failure() == SharePointProviderException.Failure.AUTHENTICATION
                    || exception.failure() == SharePointProviderException.Failure.QUOTA
                    || exception.failure() == SharePointProviderException.Failure.UNAVAILABLE) {
                throw exception;
            }
            // One unreadable item does not fail the run; the next prune reconciles it.
            fenced(work, () -> {
                runs.counted(work, "acquisition_failed", 1);
                return true;
            });
        }
    }

    private static @Nullable String token(Run run) {
        return run.windowStart() == null ? null : run.windowStart().toString();
    }

    private static @Nullable Instant windowStart(@Nullable Instant previousWindowEnd) {
        return previousWindowEnd == null ? null : previousWindowEnd.minus(JdbcSharePointSyncRepository.OVERLAP);
    }

    /**
     * Only the children listing needs the window: the change log already reports what changed, and filtering
     * it again would drop an item that was moved into scope without its timestamp changing.
     */
    private static boolean insideWindow(Run run, SharePointProvider.DriveItem item) {
        return insideWindow(run, item.lastModifiedAt() == null ? item.createdAt() : item.lastModifiedAt());
    }

    private static boolean insideWindow(Run run, @Nullable Instant changed) {
        return run.windowStart() == null || changed == null || !changed.isBefore(run.windowStart());
    }

    private static String path(SharePointProvider.DriveItem item) {
        String parent = item.parentPath() == null ? "" : item.parentPath();
        return parent + "/" + (item.name() == null ? item.id() : item.name());
    }

    private <T> T fenced(Work work, Supplier<T> action) {
        return transactions.execute(_ -> {
            if (!lockSource(work) || !runs.current(work)) throw new StaleSyncException();
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

    private record Target(String driveId, @Nullable String siteId, @Nullable String itemId) {}

    private record Listed(@Nullable String nextLink, List<String> folders) {}

    private record ScopeTargets(List<Target> drives, List<String> sites, boolean includeDocuments,
                                boolean includePages) {}

    private static final class StaleSyncException extends RuntimeException {}

    /** Ends this attempt after it has queued itself again, without closing the run a retry resumes. */
    private static final class RetryScheduledException extends RuntimeException {}
}
