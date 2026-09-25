package io.memoryos.connector.sharepoint;

import io.memoryos.BusinessException;
import io.memoryos.FailureEvidence;
import io.memoryos.connector.ConnectorSyncPort.Work;
import io.memoryos.connector.SharePointProvider;
import io.memoryos.connector.SharePointProviderException;
import io.memoryos.connector.SharePointProviderException.Failure;
import io.memoryos.connector.SharePointSourceService.RootKind;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceInputDescriptor;
import io.memoryos.connector.SourceInputFormat;
import io.memoryos.connector.SourceStorageFailure;
import io.memoryos.connector.SourceType;
import io.memoryos.connector.sharepoint.persistence.JdbcSharePointSourceRepository;
import io.memoryos.connector.sharepoint.persistence.JdbcSharePointSourceRepository.ResolvedRoot;
import io.memoryos.connector.sharepoint.persistence.JdbcSharePointSyncRepository;
import io.memoryos.connector.sharepoint.persistence.JdbcSharePointSyncRepository.Retry;
import io.memoryos.connector.sharepoint.persistence.JdbcSharePointSyncRepository.Run;
import io.memoryos.connector.sharepoint.persistence.JdbcSharePointSyncRepository.Target;
import io.memoryos.connector.sync.SyncRun;
import io.memoryos.connector.sync.SyncTraversal;
import io.memoryos.connector.sync.persistence.JdbcSourceSyncRepository;
import io.memoryos.connector.sync.persistence.JdbcSourceSyncRepository.DueSource;
import io.memoryos.connector.sync.persistence.JdbcSourceSyncRepository.ItemFailure;
import io.memoryos.connector.sync.persistence.SyncTarget;
import io.memoryos.objectstorage.ObjectStorageException;
import io.memoryos.shared.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Walks a SharePoint Source. A refresh first retries what an earlier run failed on, then reads each library's
 * change log since the previous window and applies what it reports, including the tombstones that say an item
 * is gone. A prune lists the whole scope and removes what it no longer finds, but only when the listing
 * finished, so a site that fails to answer never causes its documents to be deleted. The libraries and sites a
 * run covers are resolved once, when it starts.
 */
@Component
public class SharePointSyncTraversal implements SyncTraversal {
    private static final Logger LOGGER = LoggerFactory.getLogger(SharePointSyncTraversal.class);
    private static final int MAX_STEPS = 16;
    private static final long EXECUTION_NANOS = Duration.ofSeconds(45).toNanos();
    private static final int MAX_CONTENT_BYTES = 100 * 1024 * 1024;
    /** Items from earlier runs a refresh retries; the rest wait for a later refresh. */
    private static final int MAX_RETRIES = 500;
    // A page snapshot is stored as JSON, like the other native snapshots; its input format identifies the reader.
    private static final String PAGE_MEDIA_TYPE = "application/json";

    private final JdbcSharePointSyncRepository runs;
    private final JdbcSharePointSourceRepository sharePoint;
    private final JdbcSourceSyncRepository attempts;
    private final SharePointConnectionService connections;

    public SharePointSyncTraversal(JdbcSharePointSyncRepository runs, JdbcSharePointSourceRepository sharePoint,
            JdbcSourceSyncRepository attempts, SharePointConnectionService connections) {
        this.runs = runs;
        this.sharePoint = sharePoint;
        this.attempts = attempts;
        this.connections = connections;
    }

    @Override
    public SourceType type() {
        return SourceType.SHAREPOINT;
    }

    @Override
    public SyncTarget target() {
        return SyncTarget.SHAREPOINT;
    }

    @Override
    public List<DueSource> due(int limit) {
        return runs.due(limit);
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
    public void postponed(TenantId tenant, SourceId source) {
        runs.postponePrune(tenant, source);
    }

    @Override
    public RunFailure classify(RuntimeException failure) {
        if (failure instanceof SharePointProviderException provider) {
            String code = "SOURCE_SHAREPOINT_" + provider.failure().name();
            if (provider.failure() == Failure.AUTHENTICATION) return RunFailure.reconnect(code);
            return RunFailure.retry(code, provider.retryAfter());
        }
        if (failure instanceof BusinessException) return RunFailure.fail("SOURCE_SHAREPOINT_CONNECTION_UNAVAILABLE");
        return RunFailure.retry("SOURCE_SHAREPOINT_INTERNAL", null);
    }

    @Override
    public void authenticationFailed(Work work) {
        var credential = sharePoint.credentialId(work.tenantId(), work.sourceId());
        connections.authenticationFailed(work.tenantId(), credential, work.credentialRevision());
    }

    @Override
    public void ended(Work work, boolean failed, @Nullable String code) {
        runs.closeRun(work, failed ? "FAILED" : "CANCELLED", code);
        if (failed) runs.postponePrune(work.tenantId(), work.sourceId());
    }

    @Override
    public Slice walk(SyncRun run) {
        var work = run.work();
        var state = run.fenced(_ -> runs.state(work.tenantId(), work.sourceId()));
        if (state.isEmpty()) return Slice.STOPPED;
        try (var connection = connections.open(work.tenantId(), work.sourceId())) {
            if (connection.credentialRevision() != work.credentialRevision()) return run.stop();
            var session = connection.session();
            var opened = run.fenced(_ -> runs.openRun(work).orElseGet(() -> runs.startRun(work,
                    state.get().pruneDue() ? "PRUNE" : "REFRESH",
                    state.get().pruneDue() ? null : state.get().refreshWindowEnd())));
            if (opened.isEmpty()) return Slice.STOPPED;
            var current = opened.get();
            if (!current.scopeResolved()) {
                log("sharepoint.sync.run.started", current, null);
                current = resolveScope(run, current, session, state.get()).orElse(null);
                if (current == null) return Slice.STOPPED;
            }
            String tenantHost = connection.tenantHost() == null
                    ? session.root().hostname() : connection.tenantHost();
            return new Walk(run, current, session, tenantHost).walk();
        }
    }

    /**
     * Lists the libraries and sites the run covers, ordered so a continued run resumes where it stopped, and
     * records them with the items an earlier run failed on. This is the only time a run asks Microsoft which
     * sites and libraries exist.
     */
    private Optional<Run> resolveScope(SyncRun run, Run current, SharePointProvider.Session session,
            JdbcSharePointSyncRepository.SourceState state) {
        var work = run.work();
        var roots = run.fenced(_ -> sharePoint.resolvedRoots(work.tenantId(), work.sourceId()));
        var exclusions = run.fenced(_ -> sharePoint.exclusions(work.tenantId(), work.sourceId(), "SITE"));
        if (roots.isEmpty() || exclusions.isEmpty()) return Optional.empty();
        var excludedSites = SharePointGlob.all(exclusions.get());
        var targets = new LinkedHashMap<String, Target>();
        var sites = new LinkedHashSet<String>();
        if (roots.get().isEmpty()) {
            String link = null;
            do {
                var listed = session.sites(link);
                for (var site : listed.sites()) {
                    if (site.personalSite() || SharePointGlob.excluded(excludedSites, site.webUrl())) continue;
                    sites.add(site.siteId());
                    if (state.includeDocuments()) libraries(session, site.siteId(), targets);
                }
                link = listed.nextLink();
            } while (link != null);
        } else {
            for (ResolvedRoot root : roots.get()) {
                if (root.siteId() != null) sites.add(root.siteId());
                if (!state.includeDocuments()) continue;
                if (root.kind() == RootKind.SITE) {
                    libraries(session, Objects.requireNonNull(root.siteId()), targets);
                } else {
                    String driveId = Objects.requireNonNull(root.driveId());
                    targets.putIfAbsent(driveId, new Target(driveId, root.siteId(), root.itemId()));
                }
            }
        }
        // A Source that collects only pages walks no library; a prune then removes files it held before.
        var drives = targets.values().stream().sorted(Comparator.comparing(Target::driveId)).toList();
        var pageSites = state.includePages() ? List.copyOf(sites) : List.<String>of();
        return run.fenced(_ -> {
            var retries = current.prune() ? List.<String>of()
                    : attempts.unresolvedItems(work.tenantId(), work.sourceId(), work.operationId(), MAX_RETRIES);
            runs.resolveScope(current, drives, pageSites, retries, work.sourceId());
            return runs.openRun(work).orElseThrow();
        });
    }

    private static void libraries(SharePointProvider.Session session, String siteId,
            LinkedHashMap<String, Target> targets) {
        for (var library : session.libraries(siteId)) {
            targets.putIfAbsent(library.driveId(), new Target(library.driveId(), siteId, null));
        }
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

    /** One execution slice over the run's recorded scope. */
    private final class Walk {
        private final SyncRun run;
        private final Work work;
        private final Run current;
        private final SharePointProvider.Session session;
        private final String tenantHost;
        private final long deadline = System.nanoTime() + EXECUTION_NANOS;
        private int steps;

        Walk(SyncRun run, Run current, SharePointProvider.Session session, String tenantHost) {
            this.run = run;
            this.work = run.work();
            this.current = current;
            this.session = session;
            this.tenantHost = tenantHost;
        }

        private boolean sliceSpent() {
            return steps++ >= MAX_STEPS || System.nanoTime() >= deadline;
        }

        private boolean interrupted() {
            return run.stopped() || run.aborted();
        }

        private Slice interruption() {
            return run.aborted() ? Slice.ABORTED : Slice.STOPPED;
        }

        Slice walk() {
            if (!current.prune()) {
                var pending = retries();
                if (pending != null) return pending;
            }
            var excludedPaths = run.fenced(_ -> sharePoint.exclusions(work.tenantId(), work.sourceId(), "PATH"))
                    .map(SharePointGlob::all).orElse(null);
            if (excludedPaths == null) return Slice.STOPPED;
            var drives = runs.drives(current);
            String cursorDrive = current.checkpointDriveId();
            String cursorLink = current.checkpointLink();
            // A checkpoint with a site but no drive means the drives are done and the pages are in progress.
            boolean drivesDone = cursorDrive == null && current.checkpointSiteId() != null;
            for (Target target : drivesDone ? List.<Target>of() : drives) {
                if (cursorDrive != null && target.driveId().compareTo(cursorDrive) < 0) continue;
                boolean resuming = target.driveId().equals(cursorDrive);
                String link = resuming ? cursorLink : null;
                // A folder root is walked breadth first, as Onyx does: its subfolders queue behind it.
                String folder = target.itemId() == null ? null
                        : resuming && current.checkpointFolderId() != null ? current.checkpointFolderId() : target.itemId();
                var queue = new ArrayDeque<String>(resuming ? current.checkpointFolders() : List.of());
                // Mirrors the queue, so a folder already waiting is found without scanning it.
                var queued = new HashSet<String>(queue);
                while (true) {
                    if (sliceSpent()) {
                        String pending = link;
                        String listing = folder;
                        var waiting = List.copyOf(queue);
                        run.fenced(_ -> {
                            runs.checkpoint(current, target.driveId(), target.siteId(), pending, listing, waiting);
                            return true;
                        });
                        return Slice.CONTINUE;
                    }
                    var listed = page(target, folder, link, excludedPaths);
                    if (interrupted()) return interruption();
                    for (String subfolder : listed.folders()) {
                        if (queued.add(subfolder)) queue.add(subfolder);
                    }
                    link = listed.nextLink();
                    if (link != null) continue;
                    if (queue.isEmpty()) break;
                    folder = queue.poll();
                    queued.remove(folder);
                }
                run.fenced(_ -> {
                    runs.checkpoint(current, target.driveId(), target.siteId(), null);
                    return true;
                });
                cursorDrive = null;
                cursorLink = null;
            }
            var pending = pages(drivesDone ? current.checkpointSiteId() : null,
                    drivesDone ? current.checkpointLink() : null);
            return pending == null ? finish() : pending;
        }

        /** Retries the items an earlier run failed on, one step each; null once none remains. */
        private @Nullable Slice retries() {
            for (Retry retry : runs.retries(current)) {
                if (sliceSpent()) return Slice.CONTINUE;
                try {
                    if (retry.driveId() != null) {
                        var item = session.item(retry.driveId(), retry.providerFileId());
                        if (!item.file()) {
                            forgetFailure(retry);
                            continue;
                        }
                        run.fenced(_ -> {
                            observe(item, retry.driveId(), retry.siteId(), path(item));
                            return true;
                        });
                        acquire(item);
                    } else if (retry.siteId() != null) {
                        var page = session.page(retry.siteId(), retry.providerFileId());
                        run.fenced(_ -> {
                            observePage(retry.siteId(), page.metadata());
                            return true;
                        });
                        acquirePage(retry.siteId(), page.metadata(), page);
                    }
                } catch (SharePointProviderException exception) {
                    if (exception.failure() != Failure.NOT_FOUND) throw exception;
                    // The item is gone; the change log or the next prune removes what the Source still holds.
                    forgetFailure(retry);
                    continue;
                }
                if (interrupted()) return interruption();
                run.fenced(_ -> {
                    runs.retried(current, retry);
                    return true;
                });
            }
            return null;
        }

        private void forgetFailure(Retry retry) {
            run.fenced(_ -> {
                run.resolved(retry.providerFileId());
                runs.retried(current, retry);
                return true;
            });
        }

        /**
         * Site pages, when the Source collects them. Pages have no change log, so a refresh compares each page's
         * version against what is held rather than asking Microsoft what changed.
         */
        private @Nullable Slice pages(@Nullable String resumeSite, @Nullable String resumeLink) {
            boolean resuming = resumeSite != null;
            for (String siteId : runs.sites(current)) {
                if (resuming && !siteId.equals(resumeSite)) continue;
                String link = resuming ? resumeLink : null;
                resuming = false;
                do {
                    if (sliceSpent()) {
                        String pendingLink = link;
                        run.fenced(_ -> {
                            runs.checkpoint(current, null, siteId, pendingLink);
                            return true;
                        });
                        return Slice.CONTINUE;
                    }
                    var listed = session.pages(siteId, link);
                    for (var metadata : listed.pages()) {
                        if (!insideWindow(metadata.lastModifiedAt())) continue;
                        boolean unchanged = run.fenced(_ -> {
                            observePage(siteId, metadata);
                            return !current.prune() && run.unchanged(metadata.pageId(), metadata.contentVersion());
                        }).orElse(true);
                        if (!current.prune() && !unchanged) acquirePage(siteId, metadata, null);
                        if (interrupted()) return interruption();
                    }
                    link = listed.nextLink();
                } while (link != null);
                run.fenced(_ -> {
                    runs.checkpoint(current, null, siteId, null);
                    return true;
                });
            }
            return null;
        }

        private void observePage(String siteId, SharePointProvider.SitePageMetadata metadata) {
            runs.observe(work.tenantId(), work.sourceId(), metadata.pageId(), "PAGE", null, siteId,
                    metadata.title(), metadata.webUrl(), metadata.contentVersion(), metadata.eTag(), 0,
                    current.prune() ? current.id() : null);
            run.observed(metadata.pageId(), metadata.title());
        }

        private void acquirePage(String siteId, SharePointProvider.SitePageMetadata metadata,
                SharePointProvider.@Nullable PageContent read) {
            try {
                var page = read == null ? session.page(siteId, metadata.pageId()) : read;
                var descriptor = new SourceInputDescriptor(SourceInputFormat.SHAREPOINT_PAGE, metadata.pageId(),
                        page.metadata().contentVersion(), metadata.webUrl());
                run.acquire(new SyncRun.Content(descriptor, metadata.title() + ".json", PAGE_MEDIA_TYPE, true,
                        page.snapshot()), _ -> { });
            } catch (SharePointProviderException exception) {
                if (runLevel(exception)) throw exception;
                if (vanished(exception, metadata.pageId())) return;
                // A page whose canvas cannot be read affects only itself.
                failed("PAGE:", metadata.pageId(), metadata.title(), "SOURCE_SHAREPOINT_" + exception.failure().name(),
                        exception);
            } catch (ObjectStorageException exception) {
                failed("PAGE:", metadata.pageId(), metadata.title(),
                        "SOURCE_STORAGE_WRITE_" + SourceStorageFailure.code(exception), exception);
            }
        }

        /**
         * One page of a library's change log, or of one folder's children when the root is a folder. A folder
         * listing also returns the subfolders it found, which the caller walks next.
         */
        private Listed page(Target target, @Nullable String folderId, @Nullable String link,
                List<SharePointGlob> excludedPaths) {
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
                    delta = session.delta(target.driveId(), current.prune() ? null : token(), link);
                } catch (SharePointProviderException exception) {
                    if (exception.failure() != Failure.RESYNC_REQUIRED) throw exception;
                    // Microsoft no longer accepts the saved link or window, so the library is read again from its start.
                    delta = session.delta(target.driveId(), null, null);
                }
                page = delta.items();
                next = delta.nextLink();
            }
            for (var item : page) {
                if (item.deleted()) {
                    if (!current.prune()) remove(item.id());
                    continue;
                }
                if (item.folder()) {
                    // Folders are always walked: a folder's timestamp does not change when a file deep inside it does.
                    if (folderId != null) folders.add(item.id());
                    continue;
                }
                if (!item.file()) continue;
                String path = path(item);
                if (SharePointGlob.excluded(excludedPaths, path)) continue;
                if (folderId != null && !insideWindow(item)) continue;
                file(target, item, path);
                if (interrupted()) break;
            }
            return new Listed(next, folders);
        }

        /**
         * A file: it is recorded, and recognised as the version already held, in one fence; only a changed file
         * is downloaded, and it is recognised or adopted in a second.
         */
        private void file(Target target, SharePointProvider.DriveItem item, String path) {
            boolean done = run.fenced(_ -> {
                observe(item, target.driveId(), target.siteId(), path);
                if (current.prune()) return true;
                if (item.size() > MAX_CONTENT_BYTES) {
                    run.skipped(item.id());
                    return true;
                }
                return run.unchanged(item.id(), item.contentVersion());
            }).orElse(true);
            if (!done) acquire(item);
        }

        private void observe(SharePointProvider.DriveItem item, @Nullable String driveId, @Nullable String siteId,
                String path) {
            runs.observe(work.tenantId(), work.sourceId(), item.id(), "FILE", driveId, siteId, item.name(), path,
                    item.contentVersion(), item.eTag(), item.size(), current.prune() ? current.id() : null);
            run.observed(item.id(), item.name());
        }

        private void acquire(SharePointProvider.DriveItem item) {
            try {
                var read = session.item(item.driveId() == null ? "" : item.driveId(), item.id());
                var content = session.content(read, tenantHost, MAX_CONTENT_BYTES);
                var descriptor = new SourceInputDescriptor(SourceInputFormat.BINARY, item.id(), read.contentVersion(),
                        read.webUrl());
                run.acquire(new SyncRun.Content(descriptor, content.filename(), content.mediaType(), false,
                        content.bytes()), _ -> { });
            } catch (SharePointProviderException exception) {
                if (runLevel(exception)) throw exception;
                if (vanished(exception, item.id())) return;
                // One unreadable item does not fail the run; the next refresh retries it.
                failed("FILE:", item.id(), item.name(), "SOURCE_SHAREPOINT_" + exception.failure().name(), exception);
            } catch (ObjectStorageException exception) {
                failed("FILE:", item.id(), item.name(), "SOURCE_STORAGE_WRITE_" + SourceStorageFailure.code(exception),
                        exception);
            }
        }

        /**
         * An item deleted between the listing and the download is skipped, not failed: the change log reports its
         * tombstone and a prune removes it.
         */
        private boolean vanished(SharePointProviderException exception, String providerFileId) {
            if (exception.failure() != Failure.NOT_FOUND) return false;
            run.fenced(_ -> {
                run.skipped(providerFileId);
                return true;
            });
            return true;
        }

        private void failed(String kind, String providerFileId, @Nullable String name, String code,
                RuntimeException exception) {
            run.fenced(_ -> run.itemFailed(new ItemFailure(kind + providerFileId, providerFileId, name, code,
                    exception.getMessage(), FailureEvidence.detail(exception), false)));
        }

        /** Removes what a tombstone reports, matching by the provider identifier it carries. */
        private void remove(String providerFileId) {
            run.fenced(pair -> {
                var item = runs.item(work.tenantId(), work.sourceId(), providerFileId);
                if (item.isPresent()) {
                    run.remove(pair, item.get());
                    run.removed(1);
                }
                runs.forget(work.tenantId(), work.sourceId(), providerFileId);
                run.resolved(providerFileId);
                return true;
            });
        }

        /** Ends the run: a refresh records its window, a prune removes what its complete listing did not see. */
        private Slice finish() {
            return run.fenced(pair -> {
                runs.listingComplete(current);
                if (current.prune()) {
                    var missing = runs.missing(work.tenantId(), work.sourceId(), current.id());
                    if (!missing.isEmpty()) {
                        for (var entry : missing) {
                            run.remove(pair, entry.itemId());
                            runs.forget(work.tenantId(), work.sourceId(), entry.providerFileId());
                        }
                        run.removed(missing.size());
                        return Slice.CONTINUE;
                    }
                    runs.finishPrune(current);
                } else {
                    runs.finishRefresh(current);
                }
                runs.completeRun(current);
                run.complete();
                log("sharepoint.sync.run.finished", current, "completed");
                return Slice.COMPLETED;
            }).orElse(Slice.STOPPED);
        }

        private @Nullable String token() {
            return current.windowStart() == null ? null : current.windowStart().toString();
        }

        /**
         * Only the children listing needs the window: the change log already reports what changed, and filtering
         * it again would drop an item that was moved into scope without its timestamp changing.
         */
        private boolean insideWindow(SharePointProvider.DriveItem item) {
            return insideWindow(item.lastModifiedAt() == null ? item.createdAt() : item.lastModifiedAt());
        }

        private boolean insideWindow(@Nullable Instant changed) {
            return current.windowStart() == null || changed == null || !changed.isBefore(current.windowStart());
        }
    }

    /** Failures that concern the connection rather than one item: the run retries or reconnects instead. */
    private static boolean runLevel(SharePointProviderException exception) {
        return exception.failure() == Failure.AUTHENTICATION || exception.failure() == Failure.QUOTA
                || exception.failure() == Failure.UNAVAILABLE;
    }

    private static String path(SharePointProvider.DriveItem item) {
        String parent = item.parentPath() == null ? "" : item.parentPath();
        return parent + "/" + (item.name() == null ? item.id() : item.name());
    }

    private record Listed(@Nullable String nextLink, List<String> folders) {}
}
