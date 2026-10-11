package io.memoryos.connector.lark;

import io.memoryos.BusinessException;
import io.memoryos.FailureEvidence;
import io.memoryos.connector.ConnectorSyncPort.Work;
import io.memoryos.connector.LarkGateway;
import io.memoryos.connector.LarkGateway.ObjectType;
import io.memoryos.connector.LarkProviderException;
import io.memoryos.connector.LarkProviderException.Failure;
import io.memoryos.connector.LarkSourceService.RootKind;
import io.memoryos.connector.LarkSourceService.RootView;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceInputDescriptor;
import io.memoryos.connector.SourceInputFormat;
import io.memoryos.connector.SourceStorageFailure;
import io.memoryos.connector.SourceType;
import io.memoryos.connector.lark.persistence.JdbcLarkSourceRepository;
import io.memoryos.connector.lark.persistence.JdbcLarkSyncRepository;
import io.memoryos.connector.lark.persistence.JdbcLarkSyncRepository.Run;
import io.memoryos.connector.sync.SourceProviderCapabilities;
import io.memoryos.connector.sync.SourceSyncAdapter;
import io.memoryos.connector.sync.SyncRun;
import io.memoryos.connector.sync.persistence.JdbcSourceSyncRepository.DueSource;
import io.memoryos.connector.sync.persistence.JdbcSourceSyncRepository.ItemFailure;
import io.memoryos.objectstorage.ObjectStorageException;
import io.memoryos.shared.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Walks a Lark Suite Source. Every run lists its roots whole, breadth first: the nodes of a Wiki space, the entries
 * of a Drive folder. A Docx, a Sheet or an uploaded file whose version the Source does not hold is read; any other
 * kind of object is recorded as skipped. Lark has no change log an app can follow, so what a run did not list is what
 * has gone, and it is removed only when the listing reached the end of every root: a space that fails to answer
 * never causes its documents to be deleted.
 */
@Component
public class LarkSyncAdapter implements SourceSyncAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger(LarkSyncAdapter.class);
    private static final int MAX_STEPS = 16;
    private static final long EXECUTION_NANOS = Duration.ofSeconds(45).toNanos();
    private static final int MAX_CONTENT_BYTES = 100 * 1024 * 1024;
    // A snapshot is stored as JSON, like the other native snapshots; its input format identifies the reader.
    private static final String SNAPSHOT_MEDIA_TYPE = "application/json";

    private final JdbcLarkSyncRepository runs;
    private final JdbcLarkSourceRepository lark;
    private final LarkConnectionService connections;

    public LarkSyncAdapter(JdbcLarkSyncRepository runs, JdbcLarkSourceRepository lark,
            LarkConnectionService connections) {
        this.runs = runs;
        this.lark = lark;
        this.connections = connections;
    }

    @Override
    public SourceType type() {
        return SourceType.LARK;
    }

    @Override
    public SourceProviderCapabilities capabilities() {
        return new SourceProviderCapabilities(false);
    }

    @Override
    public List<DueSource> due(int limit) {
        return runs.due(limit);
    }

    /** The address Lark returned for the node or the file when it was acquired, on a Lark host. */
    @Override
    public @Nullable String documentUrl(@Nullable String providerFileId, @Nullable String sourceUrl) {
        return LarkLinks.documentUrl(sourceUrl);
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
     * A refused credential asks for it to be replaced; a throttled or silent Lark is asked again. Anything else
     * that ends a listing (a root the app was removed from, an answer that cannot be read) fails the run, because
     * asking again changes nothing until somebody acts.
     */
    @Override
    public RunFailure classify(RuntimeException failure) {
        if (failure instanceof LarkProviderException provider) {
            String code = "SOURCE_LARK_" + provider.failure().name();
            return switch (provider.failure()) {
                case AUTHENTICATION -> RunFailure.reconnect(code);
                case QUOTA, UNAVAILABLE -> RunFailure.retry(code, provider.retryAfter());
                default -> RunFailure.fail(code);
            };
        }
        if (failure instanceof BusinessException) return RunFailure.fail("SOURCE_LARK_CONNECTION_UNAVAILABLE");
        return RunFailure.retry("SOURCE_LARK_INTERNAL", null);
    }

    @Override
    public void authenticationFailed(Work work) {
        var credential = lark.credentialId(work.tenantId(), work.sourceId());
        connections.authenticationFailed(work.tenantId(), credential, work.credentialRevision());
    }

    @Override
    public void ended(Work work, boolean failed, @Nullable String code) {
        runs.closeRun(work, failed ? "FAILED" : "CANCELLED", code);
    }

    @Override
    public Slice walk(SyncRun run) {
        var work = run.work();
        var roots = run.fenced(_ -> lark.roots(work.tenantId(), work.sourceId()));
        if (roots.isEmpty()) return Slice.STOPPED;
        try (var connection = connections.open(work.tenantId(), work.sourceId())) {
            if (connection.credentialRevision() != work.credentialRevision()) return run.stop();
            var opened = run.fenced(_ -> runs.openRun(work).orElseGet(() -> {
                var started = runs.startRun(work);
                log("lark.sync.run.started", started, "started");
                return started;
            }));
            if (opened.isEmpty()) return Slice.STOPPED;
            return new Walk(run, opened.get(), roots.get(), connection.session()).walk();
        }
    }

    /** Stable event fields only. Space names, document titles and identifiers of what was read never appear here. */
    private static void log(String event, Run run, String outcome) {
        LOGGER.atInfo().addKeyValue("event", event).addKeyValue("source_id", run.sourceId().value())
                .addKeyValue("outcome", outcome).log("Lark synchronization run");
    }

    /** One execution slice over the Source's roots. */
    private final class Walk {
        private final SyncRun run;
        private final Work work;
        private final Run current;
        private final List<RootView> roots;
        private final LarkGateway.Session session;
        private final long deadline = System.nanoTime() + EXECUTION_NANOS;
        private int steps;

        Walk(SyncRun run, Run current, List<RootView> roots, LarkGateway.Session session) {
            this.run = run;
            this.work = run.work();
            this.current = current;
            this.roots = roots;
            this.session = session;
        }

        private boolean sliceSpent() {
            return steps++ >= MAX_STEPS || System.nanoTime() >= deadline;
        }

        private boolean interrupted() {
            return run.stopped() || run.aborted();
        }

        Slice walk() {
            for (int position = current.checkpointRoot(); position < roots.size(); position++) {
                RootView root = roots.get(position);
                boolean resuming = position == current.checkpointRoot();
                // Null is the root itself: the top level of the space, or the folder the Source names.
                String container = resuming ? current.checkpointContainer() : null;
                String page = resuming ? current.checkpointPage() : null;
                var queue = new ArrayDeque<String>(resuming ? current.checkpointPending() : List.of());
                // Mirrors what was queued in this slice, so a container Lark lists twice is walked once.
                var queued = new HashSet<>(queue);
                while (true) {
                    if (sliceSpent()) {
                        int at = position;
                        String listing = container;
                        String pending = page;
                        var waiting = List.copyOf(queue);
                        run.fenced(_ -> {
                            runs.checkpoint(current, at, listing, pending, waiting);
                            return true;
                        });
                        return Slice.CONTINUE;
                    }
                    var listed = list(root, container, page);
                    if (interrupted()) return run.aborted() ? Slice.ABORTED : Slice.STOPPED;
                    for (String child : listed.containers())
                        if (queued.add(child)) queue.add(child);
                    page = listed.nextPage();
                    if (page != null) continue;
                    if (queue.isEmpty()) break;
                    container = queue.poll();
                }
                int next = position + 1;
                run.fenced(_ -> {
                    runs.checkpoint(current, next, null, null, List.of());
                    return true;
                });
            }
            return finish();
        }

        /** One page of a container: its objects are recorded and read, its containers are returned to be walked. */
        private Listed list(RootView root, @Nullable String container, @Nullable String page) {
            var containers = new ArrayList<String>();
            if (root.kind() == RootKind.WIKI_SPACE) {
                var nodes = session.nodes(root.token(), container, page);
                for (var node : nodes.nodes()) {
                    // A node is both an object and, when it has children, a container of more nodes.
                    if (node.hasChild()) containers.add(node.nodeToken());
                    object(new Entry(node.objToken(), node.objType(), node.title(), node.url(),
                            node.objCreatedAt(), node.objEditedAt()));
                    if (interrupted()) break;
                }
                return new Listed(nodes.pageToken(), containers);
            }
            var files = session.files(container == null ? root.token() : container, page);
            for (var file : files.files()) {
                if (file.type() == ObjectType.FOLDER) {
                    containers.add(file.token());
                    continue;
                }
                object(new Entry(file.token(), file.type(), file.name(), file.url(), file.createdAt(),
                        file.modifiedAt()));
                if (interrupted()) break;
            }
            return new Listed(files.pageToken(), containers);
        }

        /**
         * An object: it is recorded, and recognised as the version already held, in one fence; only a changed one is
         * read, and it is recognised or adopted in a second.
         */
        private void object(Entry entry) {
            boolean readable = entry.type() == ObjectType.DOCX || entry.type() == ObjectType.SHEET
                    || entry.type() == ObjectType.FILE;
            String version = version(entry);
            boolean done = run.fenced(_ -> {
                run.observed(entry.token(), entry.name());
                if (!readable) {
                    // A Base, a mind map, slides, an old-format document or a shortcut: listed, never indexed.
                    run.skipped(entry.token());
                    return true;
                }
                runs.observe(current, entry.token(), entry.type().name(), entry.name(), version);
                return run.unchanged(entry.token(), version, entry.createdAt(), entry.modifiedAt());
            }).orElse(true);
            if (!done) acquire(entry, version);
        }

        private void acquire(Entry entry, String version) {
            try {
                var content = switch (entry.type()) {
                    case DOCX -> snapshot(entry, version, SourceInputFormat.LARK_DOCX, session.docx(entry.token()));
                    case SHEET -> snapshot(entry, version, SourceInputFormat.LARK_SHEET, session.sheet(entry.token()));
                    default -> {
                        var read = session.download(entry.token(), MAX_CONTENT_BYTES);
                        // Lark names a download by its token when it sends no file name.
                        String filename = read.filename().equals(entry.token()) && !entry.name().isBlank()
                                ? entry.name() : read.filename();
                        yield new SyncRun.Content(new SourceInputDescriptor(SourceInputFormat.BINARY, entry.token(),
                                version, entry.url()), filename, read.mediaType(), false, read.bytes(),
                                entry.createdAt(), entry.modifiedAt());
                    }
                };
                run.acquire(content, _ -> { });
            } catch (LarkProviderException exception) {
                if (runLevel(exception)) throw exception;
                if (exception.failure() == Failure.NOT_FOUND) {
                    // Deleted between the listing and the read; the next run no longer lists it.
                    run.fenced(_ -> {
                        run.skipped(entry.token());
                        return true;
                    });
                    return;
                }
                // One unreadable object does not fail the run; one over a bound is skipped rather than failed.
                failed(entry, "SOURCE_LARK_" + exception.failure().name(), exception,
                        exception.failure() == Failure.LIMIT_EXCEEDED);
            } catch (ObjectStorageException exception) {
                failed(entry, "SOURCE_STORAGE_WRITE_" + SourceStorageFailure.code(exception), exception, false);
            }
        }

        private SyncRun.Content snapshot(Entry entry, String version, SourceInputFormat format,
                LarkGateway.Snapshot snapshot) {
            String name = entry.name().isBlank() ? entry.token() : entry.name();
            return new SyncRun.Content(new SourceInputDescriptor(format, entry.token(), version, entry.url()),
                    name + ".json", SNAPSHOT_MEDIA_TYPE, true, snapshot.bytes(), entry.createdAt(),
                    entry.modifiedAt());
        }

        private void failed(Entry entry, String code, RuntimeException exception, boolean skipped) {
            run.fenced(_ -> run.itemFailed(new ItemFailure(entry.type().name() + ":" + entry.token(), entry.token(),
                    entry.name(), code, exception.getMessage(), FailureEvidence.detail(exception), skipped)));
        }

        /** Ends the run: what its complete listing did not see is removed, in batches, then it completes. */
        private Slice finish() {
            return run.fenced(pair -> {
                runs.listingComplete(current);
                var missing = runs.missing(current);
                if (!missing.isEmpty()) {
                    for (var entry : missing) {
                        run.remove(pair, entry.itemId());
                        runs.forget(work.tenantId(), work.sourceId(), entry.providerFileId());
                    }
                    run.removed(missing.size());
                    return Slice.CONTINUE;
                }
                runs.completeRun(current);
                run.complete();
                log("lark.sync.run.finished", current, "completed");
                return Slice.COMPLETED;
            }).orElse(Slice.STOPPED);
        }
    }

    /** Failures that concern the connection rather than one object: the run retries or reconnects instead. */
    private static boolean runLevel(LarkProviderException exception) {
        return exception.failure() == Failure.AUTHENTICATION || exception.failure() == Failure.QUOTA
                || exception.failure() == Failure.UNAVAILABLE;
    }

    /** Lark versions an object by the second it was last edited. */
    private static String version(Entry entry) {
        Instant edited = entry.modifiedAt() == null ? entry.createdAt() : entry.modifiedAt();
        return edited == null ? "0" : Long.toString(edited.getEpochSecond());
    }

    private record Entry(String token, ObjectType type, String name, @Nullable String url,
                         @Nullable Instant createdAt, @Nullable Instant modifiedAt) {}

    private record Listed(@Nullable String nextPage, List<String> containers) {}
}
