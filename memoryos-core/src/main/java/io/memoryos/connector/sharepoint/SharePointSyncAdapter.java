package io.memoryos.connector.sharepoint;

import io.memoryos.BusinessException;
import io.memoryos.FailureEvidence;
import io.memoryos.connector.ConnectorSyncPort.Work;
import io.memoryos.connector.SharePointException;
import io.memoryos.connector.SharePointGateway;
import io.memoryos.connector.SharePointGateway.EntraMember;
import io.memoryos.connector.SharePointGateway.Principal;
import io.memoryos.connector.SharePointProviderException;
import io.memoryos.connector.SharePointProviderException.Failure;
import io.memoryos.connector.SharePointSourceService.RootKind;
import io.memoryos.connector.SourceAccess;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceInputDescriptor;
import io.memoryos.connector.SourceInputFormat;
import io.memoryos.connector.SourceStorageFailure;
import io.memoryos.connector.SourceType;
import io.memoryos.connector.sharepoint.persistence.JdbcSharePointAclRepository;
import io.memoryos.connector.sharepoint.persistence.JdbcSharePointAclRepository.Item;
import io.memoryos.connector.sharepoint.persistence.JdbcSharePointSourceRepository;
import io.memoryos.connector.sharepoint.persistence.JdbcSharePointSourceRepository.ResolvedRoot;
import io.memoryos.connector.sharepoint.persistence.JdbcSharePointSyncRepository;
import io.memoryos.connector.sharepoint.persistence.JdbcSharePointSyncRepository.Location;
import io.memoryos.connector.sharepoint.persistence.JdbcSharePointSyncRepository.Phase;
import io.memoryos.connector.sharepoint.persistence.JdbcSharePointSyncRepository.Retry;
import io.memoryos.connector.sharepoint.persistence.JdbcSharePointSyncRepository.Run;
import io.memoryos.connector.sharepoint.persistence.JdbcSharePointSyncRepository.Target;
import io.memoryos.connector.sync.SourceProviderCapabilities;
import io.memoryos.connector.sync.SyncRun;
import io.memoryos.connector.sync.SourceSyncAdapter;
import io.memoryos.connector.sync.persistence.JdbcSourceSyncRepository;
import io.memoryos.connector.sync.persistence.JdbcSourceSyncRepository.DueSource;
import io.memoryos.connector.sync.persistence.JdbcSourceSyncRepository.ItemFailure;
import io.memoryos.objectstorage.ObjectStorageException;
import io.memoryos.shared.TenantId;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
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
 *
 * <p>For a Source that admits readers by SharePoint's own permissions (MEM-136), an item's permissions are read when
 * it is acquired, and every run then reads again the permissions of each item the Source holds, when that is due, and
 * the membership of the groups named there. The change log does not report a change of permissions alone, so the
 * second read is the only way to see one.
 */
@Component
public class SharePointSyncAdapter implements SourceSyncAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger(SharePointSyncAdapter.class);
    private static final int MAX_STEPS = 16;
    private static final long EXECUTION_NANOS = Duration.ofSeconds(45).toNanos();
    private static final int MAX_CONTENT_BYTES = 100 * 1024 * 1024;
    /** Items from earlier runs a refresh retries; the rest wait for a later refresh. */
    private static final int MAX_RETRIES = 500;
    // A page snapshot is stored as JSON, like the other native snapshots; its input format identifies the reader.
    private static final String PAGE_MEDIA_TYPE = "application/json";
    /** How often the permissions of every item are read again, as Onyx does. */
    private static final Duration PERMISSION_INTERVAL = Duration.ofMinutes(30);
    private static final int PERMISSION_BATCH = 64;
    /** The library every site keeps its pages in; its address is the same in every site language. */
    private static final String PAGES_LIBRARY = "/sitepages/";

    private final JdbcSharePointSyncRepository runs;
    private final JdbcSharePointSourceRepository sharePoint;
    private final JdbcSourceSyncRepository attempts;
    private final SharePointConnectionService connections;
    private final JdbcSharePointAclRepository acls;
    private final SharePointGroupSynchronizer groups;

    public SharePointSyncAdapter(JdbcSharePointSyncRepository runs, JdbcSharePointSourceRepository sharePoint,
            JdbcSourceSyncRepository attempts, SharePointConnectionService connections,
            JdbcSharePointAclRepository acls, SharePointGroupSynchronizer groups) {
        this.runs = runs;
        this.sharePoint = sharePoint;
        this.attempts = attempts;
        this.connections = connections;
        this.acls = acls;
        this.groups = groups;
    }

    @Override
    public SourceType type() {
        return SourceType.SHAREPOINT;
    }

    @Override
    public SourceProviderCapabilities capabilities() {
        return new SourceProviderCapabilities(true);
    }

    @Override
    public List<DueSource> due(int limit) {
        return runs.due(limit);
    }

    /** The {@code webUrl} Microsoft Graph returned for the file or page when it was acquired, on a SharePoint host. */
    @Override
    public @Nullable String documentUrl(@Nullable String providerFileId, @Nullable String sourceUrl) {
        if (sourceUrl == null || sourceUrl.length() > 2048) return null;
        try {
            var uri = new URI(sourceUrl);
            String host = uri.getHost();
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getRawUserInfo() == null && host != null
                    && host.toLowerCase(Locale.ROOT).endsWith(".sharepoint.com") ? sourceUrl : null;
        } catch (URISyntaxException malformed) {
            return null;
        }
    }

    @Override
    public long credentialRevision(TenantId tenant, SourceId source) {
        return connections.state(tenant, source).credentialRevision();
    }

    @Override
    public boolean credentialCurrent(TenantId tenant, SourceId source, long credentialRevision) {
        return connections.current(tenant, source, credentialRevision);
    }

    /**
     * Auto Sync needs a certificate credential. A Source that starts admitting readers by SharePoint's permissions
     * has read none yet, so nobody reads its documents until its next run, which is asked for now.
     */
    @Override
    public void accessChanging(TenantId tenant, SourceId source, SourceAccess access) {
        if (access != SourceAccess.SYNC) return;
        if (!connections.certificate(tenant, source)) throw SharePointException.syncRequiresCertificate();
        acls.permissionsDue(tenant, source);
        sharePoint.requestSynchronization(tenant, source);
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
        // Asking again changes nothing until an administrator grants the application what Auto Sync needs.
        if (failure instanceof SharePointException denied && SharePointException.PERMISSIONS_DENIED.equals(denied.code())) {
            return RunFailure.fail(denied.code());
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
            // SharePoint's permissions are read only for a Source that admits readers by them.
            var sharing = run.fenced(_ -> acls.sharing(work).orElse(null));
            return new Walk(run, current, session, tenantHost, sharing.orElse(null)).walk();
        }
    }

    /**
     * Lists the libraries and sites the run covers, ordered so a continued run resumes where it stopped, and
     * records them with the items an earlier run failed on. This is the only time a run asks Microsoft which
     * sites and libraries exist.
     */
    private Optional<Run> resolveScope(SyncRun run, Run current, SharePointGateway.Session session,
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

    private static void libraries(SharePointGateway.Session session, String siteId,
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
        private final SharePointGateway.Session session;
        private final String tenantHost;
        /** How the Source reads SharePoint's permissions; null when it does not admit readers by them. */
        private final JdbcSharePointAclRepository.@Nullable Sharing sharing;
        /** The members of each group asked about in this slice, so a group many items name is asked once. */
        private final Map<String, List<Principal>> siteGroups = new HashMap<>();
        private final Map<String, List<EntraMember>> entraGroups = new HashMap<>();
        private final long deadline = System.nanoTime() + EXECUTION_NANOS;
        private int steps;

        Walk(SyncRun run, Run current, SharePointGateway.Session session, String tenantHost,
                JdbcSharePointAclRepository.@Nullable Sharing sharing) {
            this.run = run;
            this.work = run.work();
            this.current = current;
            this.session = session;
            this.tenantHost = tenantHost;
            this.sharing = sharing;
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
            Phase phase = current.phase();
            if (phase == Phase.CONTENT) {
                var pending = content();
                if (pending != null) return pending;
                phase = sharing == null ? Phase.FINISH : Phase.GROUPS;
                if (!entered(phase)) return Slice.STOPPED;
            }
            // A Source that stopped admitting readers by SharePoint's permissions meanwhile reads no more of them.
            // Membership comes first: a site answers for it in one request, so an application that may not read
            // permissions is found out before every item is asked about.
            if (phase == Phase.GROUPS && sharing != null) {
                while (groups.advance(run, session, tenantHost)) {
                    if (interrupted()) return interruption();
                    if (System.nanoTime() >= deadline) return Slice.CONTINUE;
                }
                if (interrupted()) return interruption();
                phase = sharing.permissionsDue() ? Phase.PERMISSIONS : Phase.FINISH;
                if (!entered(phase)) return Slice.STOPPED;
            }
            if (phase == Phase.PERMISSIONS && sharing != null) {
                var pending = permissions();
                if (pending != null) return pending;
                if (!entered(Phase.FINISH)) return Slice.STOPPED;
            }
            return finish();
        }

        private boolean entered(Phase phase) {
            return run.fenced(_ -> {
                runs.phase(current, phase);
                return true;
            }).isPresent();
        }

        /** The content of the run's scope; null once all of it was read. */
        private @Nullable Slice content() {
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
                var queued = new HashSet<>(queue);
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
            return pages(drivesDone ? current.checkpointSiteId() : null,
                    drivesDone ? current.checkpointLink() : null);
        }

        /**
         * Reads again who may read each item the Source holds, in the order of their identifiers; null once every
         * item was asked about. An item this run already asked about, when it acquired it, is not asked twice.
         */
        private @Nullable Slice permissions() {
            String after = current.permissionCheckpoint();
            while (true) {
                String from = after;
                var batch = run.fenced(_ -> acls.items(work, from, current.windowEnd(), PERMISSION_BATCH));
                if (batch.isEmpty()) return Slice.STOPPED;
                if (batch.get().isEmpty()) break;
                for (Item item : batch.get()) {
                    if (System.nanoTime() >= deadline) return permissionCheckpoint(after) ? Slice.CONTINUE : Slice.STOPPED;
                    permissions(item);
                    if (interrupted()) return interruption();
                    after = item.providerFileId();
                }
                if (!permissionCheckpoint(after)) return Slice.STOPPED;
            }
            return run.fenced(_ -> {
                acls.permissionsRead(work, PERMISSION_INTERVAL);
                return true;
            }).isPresent() ? null : Slice.STOPPED;
        }

        private boolean permissionCheckpoint(@Nullable String providerFileId) {
            if (providerFileId == null) return true;
            return run.fenced(_ -> {
                runs.permissionCheckpoint(current, providerFileId);
                return true;
            }).isPresent();
        }

        /**
         * Who SharePoint lets read one item, read whole or not at all. A read that fails keeps the last complete
         * answer in force, and before the first one nobody reads the item. An item SharePoint no longer has is read
         * by nobody.
         */
        private void permissions(Item item) {
            try {
                var location = location(item);
                if (location == null) {
                    recordAclFailure(item, "SOURCE_SHAREPOINT_" + Failure.MALFORMED.name());
                    return;
                }
                boolean linked = Objects.requireNonNull(sharing).sharingLinksPublic() && !item.page()
                        && item.driveId() != null && session.sharingLinkScopes(item.driveId(), item.providerFileId())
                                .stream().anyMatch(scope -> scope.equals("anonymous") || scope.equals("organization"));
                var assignments = item.page()
                        ? session.pageRoleAssignments(tenantHost, location.webUrl(), serverRelative(item))
                        : session.itemRoleAssignments(tenantHost, location.webUrl(),
                                Objects.requireNonNull(location.listId()), Objects.requireNonNull(location.listItemId()));
                var readers = SharePointAccess.readers(location.webUrl(), assignments, new SharePointAccess.Groups() {
                    @Override public List<Principal> siteGroup(long id) {
                        return siteGroups.computeIfAbsent(location.webUrl() + "::" + id, _ -> members(
                                () -> session.siteGroupMembers(tenantHost, location.webUrl(), id)));
                    }

                    @Override public List<EntraMember> entraGroup(String id) {
                        return entraGroups.computeIfAbsent(id, _ -> members(() -> entraGroupMembers(id)));
                    }
                });
                run.fenced(_ -> {
                    acls.recordSuccess(work, item.providerFileId(), linked || readers.everyone(), readers.userEmails(),
                            readers.groupIds());
                    return true;
                });
            } catch (SharePointProviderException exception) {
                if (runLevel(exception)) throw exception;
                if (exception.failure() == Failure.NOT_FOUND) {
                    run.fenced(_ -> {
                        acls.recordSuccess(work, item.providerFileId(), false, List.of(), List.of());
                        return true;
                    });
                    return;
                }
                recordAclFailure(item, "SOURCE_SHAREPOINT_" + exception.failure().name());
            }
        }

        private void recordAclFailure(Item item, String code) {
            run.fenced(_ -> {
                acls.recordFailure(work, item.providerFileId(), code);
                return true;
            });
        }

        /**
         * Where SharePoint keeps the item, or null when that cannot be told. A file acquired before this was
         * remembered is asked about once more; a page lives in its site's page library.
         */
        private @Nullable Location location(Item item) {
            if (item.webUrl() != null && (item.page() || (item.listId() != null && item.listItemId() != null))) {
                return new Location(item.listId(), item.listItemId(), item.webUrl());
            }
            Location found;
            if (item.page()) {
                String web = pageWeb(item.path());
                found = web == null ? null : new Location(null, null, web);
            } else {
                var kept = item.driveId() == null ? null : session.item(item.driveId(), item.providerFileId()).listItem();
                found = kept == null ? null : new Location(kept.listId(), kept.listItemId(), kept.webUrl());
            }
            if (found != null) {
                run.fenced(_ -> {
                    acls.located(work, item.providerFileId(), found.listId(), found.listItemId(), found.webUrl());
                    return true;
                });
            }
            return found;
        }

        /** The groups inside an Entra group. Its people are kept by the group membership, not by a document. */
        private List<EntraMember> entraGroupMembers(String id) {
            var members = new ArrayList<EntraMember>();
            String link = null;
            do {
                var page = session.entraGroupMembers(id, link);
                for (EntraMember member : page.members()) if (member.group()) members.add(member);
                link = page.nextLink();
            } while (link != null);
            return members;
        }

        /** A group that no longer exists has no members; nobody reads through it. */
        private <T> List<T> members(Supplier<List<T>> read) {
            try {
                return read.get();
            } catch (SharePointProviderException exception) {
                if (exception.failure() != Failure.NOT_FOUND) throw exception;
                return List.of();
            }
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
                        if (sharing != null) permissions(held(item));
                        acquire(item);
                    } else if (retry.siteId() != null) {
                        var page = session.page(retry.siteId(), retry.providerFileId());
                        run.fenced(_ -> {
                            observePage(retry.siteId(), page.metadata());
                            return true;
                        });
                        if (sharing != null) permissions(held(page.metadata()));
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
                            return !current.prune() && run.unchanged(metadata.pageId(), metadata.contentVersion(),
                                    null, metadata.lastModifiedAt());
                        }).orElse(true);
                        if (!current.prune() && !unchanged) {
                            if (sharing != null) permissions(held(metadata));
                            acquirePage(siteId, metadata, null);
                        }
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

        private void observePage(String siteId, SharePointGateway.SitePageMetadata metadata) {
            String web = pageWeb(metadata.webUrl());
            runs.observe(work.tenantId(), work.sourceId(), metadata.pageId(), "PAGE", null, siteId,
                    metadata.title(), metadata.webUrl(), metadata.contentVersion(), metadata.eTag(), 0,
                    current.prune() ? current.id() : null, web == null ? null : new Location(null, null, web));
            run.observed(metadata.pageId(), metadata.title());
        }

        private void acquirePage(String siteId, SharePointGateway.SitePageMetadata metadata,
                SharePointGateway.@Nullable PageContent read) {
            try {
                var page = read == null ? session.page(siteId, metadata.pageId()) : read;
                var descriptor = new SourceInputDescriptor(SourceInputFormat.SHAREPOINT_PAGE, metadata.pageId(),
                        page.metadata().contentVersion(), metadata.webUrl());
                run.acquire(new SyncRun.Content(descriptor, metadata.title() + ".json", PAGE_MEDIA_TYPE, true,
                        page.snapshot(), null, metadata.lastModifiedAt()), _ -> { });
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
            List<SharePointGateway.DriveItem> page;
            String next;
            var folders = new ArrayList<String>();
            if (folderId != null) {
                // Graph keeps a change log only for the library root, so a folder's children are listed and filtered by window.
                var children = session.children(target.driveId(), folderId, link);
                page = children.items();
                next = children.nextLink();
            } else {
                SharePointGateway.DeltaPage delta;
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
        private void file(Target target, SharePointGateway.DriveItem item, String path) {
            boolean done = run.fenced(_ -> {
                observe(item, target.driveId(), target.siteId(), path);
                if (current.prune()) return true;
                if (item.size() > MAX_CONTENT_BYTES) {
                    run.skipped(item.id());
                    return true;
                }
                return run.unchanged(item.id(), item.contentVersion(), item.createdAt(), item.lastModifiedAt());
            }).orElse(true);
            if (done) return;
            // Read before the content, so the document is never searchable ahead of who may read it.
            if (sharing != null) permissions(held(item));
            acquire(item);
        }

        private void observe(SharePointGateway.DriveItem item, @Nullable String driveId, @Nullable String siteId,
                String path) {
            var kept = item.listItem();
            runs.observe(work.tenantId(), work.sourceId(), item.id(), "FILE", driveId, siteId, item.name(), path,
                    item.contentVersion(), item.eTag(), item.size(), current.prune() ? current.id() : null,
                    kept == null ? null : new Location(kept.listId(), kept.listItemId(), kept.webUrl()));
            run.observed(item.id(), item.name());
        }

        private void acquire(SharePointGateway.DriveItem item) {
            try {
                var read = session.item(item.driveId() == null ? "" : item.driveId(), item.id());
                var content = session.content(read, tenantHost, MAX_CONTENT_BYTES);
                var descriptor = new SourceInputDescriptor(SourceInputFormat.BINARY, item.id(), read.contentVersion(),
                        read.webUrl());
                run.acquire(new SyncRun.Content(descriptor, content.filename(), content.mediaType(), false,
                        content.bytes(), read.createdAt(), read.lastModifiedAt()), _ -> { });
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
        private boolean insideWindow(SharePointGateway.DriveItem item) {
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

    /** A file as the permission read needs it, from what Microsoft just listed. */
    private static Item held(SharePointGateway.DriveItem item) {
        var kept = item.listItem();
        return new Item(item.id(), false, item.driveId(), null, kept == null ? null : kept.listId(),
                kept == null ? null : kept.listItemId(), kept == null ? null : kept.webUrl());
    }

    private static Item held(SharePointGateway.SitePageMetadata page) {
        return new Item(page.pageId(), true, null, page.webUrl(), null, null, pageWeb(page.webUrl()));
    }

    /** The site a page belongs to: what its address has before the page library. Null when it has none. */
    private static @Nullable String pageWeb(@Nullable String pageUrl) {
        if (pageUrl == null) return null;
        int library = pageUrl.toLowerCase(Locale.ROOT).lastIndexOf(PAGES_LIBRARY);
        return library <= 0 ? null : pageUrl.substring(0, library);
    }

    /** The server-relative address of a page, with the percent-encoding Microsoft gave it. */
    private static String serverRelative(Item page) {
        try {
            return Objects.requireNonNull(new URI(Objects.requireNonNull(page.path())).getRawPath());
        } catch (URISyntaxException | NullPointerException malformed) {
            throw new SharePointProviderException(Failure.MALFORMED);
        }
    }

    private static String path(SharePointGateway.DriveItem item) {
        String parent = item.parentPath() == null ? "" : item.parentPath();
        return parent + "/" + (item.name() == null ? item.id() : item.name());
    }

    private record Listed(@Nullable String nextLink, List<String> folders) {}
}
