package io.memoryos.sandbox;

import io.memoryos.objectstorage.ObjectContent;
import io.memoryos.objectstorage.ObjectKey;
import io.memoryos.objectstorage.ObjectStorage;
import io.memoryos.objectstorage.ObjectStorageException;
import io.memoryos.objectstorage.ObjectStorageFailureCode;
import io.memoryos.objectstorage.ObjectUploadSpecification;
import io.memoryos.sandbox.persistence.JdbcSandboxRepository;
import io.memoryos.shared.TenantId;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A conversation's {@code /workspace} kept in Object Storage across its sandboxes (MEM-270), as Onyx Craft tars the
 * session's files to its file store and extracts them into the next sandbox ({@code sandbox_daemon/snapshot.py}). Its
 * bounds are Onyx's: 500 MiB of files and a 100 MiB archive. The attachments are left out, since every turn copies them
 * in again, and so are MemoryOS's run files and generated dependency directories, as Onyx leaves out
 * {@code node_modules}. tar runs in the sandbox as its user, so an archive can hold nothing that user could not write.
 */
final class WorkspaceArchives {
    static final long MAX_FILES_BYTES = 500L * 1024 * 1024;
    static final long MAX_ARCHIVE_BYTES = ObjectUploadSpecification.MAX_SIZE_BYTES;
    static final Duration COMMAND_TIMEOUT = Duration.ofMinutes(5);
    /** A new sandbox is not recorded until its workspace is back, so this bounds the start with the runtime's 90 s. */
    static final Duration RESTORE_TIMEOUT = Duration.ofMinutes(3);
    /** Longer than taking and uploading an archive can last, so the Worker never removes one being recorded. */
    static final Duration SWEEP_GRACE = Duration.ofHours(1);
    static final String ARCHIVE = SandboxService.WORKSPACE + "/.memoryos/workspace.tar.gz";
    static final String INCOMING = SandboxService.WORKSPACE + "/.memoryos-restore.tar.gz";
    private static final int TOO_LARGE = 3;
    private static final String EXCLUDED = "--exclude=./attachments --exclude=./.memoryos --exclude=./.memoryos-restore.tar.gz"
            + " --exclude=node_modules --exclude=.next --exclude=__pycache__ --exclude=.venv";
    /** tar exits 1 when a file changed while it was read; the archive is still whole. */
    static final String TAKE = """
            set -u
            mkdir -p .memoryos && rm -f %1$s
            size=$(du -sb %2$s . | cut -f1)
            if [ "$size" -gt %3$d ]; then echo "$size"; exit %4$d; fi
            tar --create --gzip --file %1$s %2$s --warning=no-file-changed . || [ $? -eq 1 ] || exit 2
            stat -c %%s %1$s
            """.formatted(ARCHIVE, EXCLUDED, MAX_FILES_BYTES, TOO_LARGE);
    static final String EXTRACT = """
            set -eu
            tar --extract --gzip --file %1$s --no-same-owner --no-same-permissions
            rm -f %1$s
            """.formatted(INCOMING);
    private static final Logger LOG = LoggerFactory.getLogger(WorkspaceArchives.class);

    private final JdbcSandboxRepository workspaces;
    private final TransactionTemplate transactions;
    private final Clock clock;

    WorkspaceArchives(JdbcSandboxRepository workspaces, TransactionTemplate transactions, Clock clock) {
        this.workspaces = workspaces;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * Archives the sandbox's workspace under a new key and makes it the conversation's; the archive before it stays
     * until the Worker removes it. A workspace past the bounds, or a failure, leaves the previous archive in place.
     */
    void save(SandboxRuntime runtime, ObjectStorage storage, TenantId tenant, UUID conversation, String id) {
        var taken = runtime.run(id, new SandboxCommand(TAKE, SandboxService.WORKSPACE, SandboxService.USER, COMMAND_TIMEOUT));
        if (taken.exitCode() == TOO_LARGE) {
            notSaved("too_large", null);
            return;
        }
        long size = taken.exitCode() == 0 && !taken.timedOut() ? size(taken.stdout()) : -1;
        if (size < 1) {
            notSaved("command_failed", null);
            return;
        }
        if (size > MAX_ARCHIVE_BYTES) {
            notSaved("too_large", null);
            return;
        }
        String key = "sandbox/" + tenant.value() + "/" + conversation + "/" + UUID.randomUUID() + ".tar.gz";
        transactions.executeWithoutResult(_ -> workspaces.archiveBegun(tenant, conversation, key, clock.instant()));
        try (InputStream archive = runtime.open(id, ARCHIVE)) {
            storage.write(new ObjectKey(key), archive, size, "application/gzip");
        } catch (Exception failed) {
            notSaved("upload_failed", failed);
            return;
        }
        Instant at = clock.instant();
        transactions.executeWithoutResult(_ -> workspaces.archived(tenant, conversation, key, at));
        runtime.run(id, new SandboxCommand("rm -f " + ARCHIVE, SandboxService.WORKSPACE, SandboxService.USER,
                Duration.ofSeconds(30)));
        LOG.atInfo().addKeyValue("event", "sandbox.workspace.saved").addKeyValue("bytes", size).log("Workspace saved");
    }

    /** Extracts {@code key} into a new sandbox; a failure leaves the workspace empty and the turn goes on. */
    void restore(SandboxRuntime runtime, @Nullable ObjectStorage storage, String id, String key) {
        if (storage == null) return;
        try {
            try (ObjectContent archive = storage.open(new ObjectKey(key))) {
                runtime.write(id, INCOMING, archive.inputStream());
            }
            var extracted = runtime.run(id, new SandboxCommand(EXTRACT, SandboxService.WORKSPACE, SandboxService.USER,
                    RESTORE_TIMEOUT));
            if (extracted.exitCode() != 0 || extracted.timedOut()) {
                restoreFailed("command_failed", null);
                return;
            }
            LOG.atInfo().addKeyValue("event", "sandbox.workspace.restored").log("Workspace restored");
        } catch (RuntimeException failed) {
            restoreFailed("restore_failed", failed);
        }
    }

    /** Deletes up to {@code limit} archives no conversation starts from; answers how many went. */
    int sweep(ObjectStorage storage, int limit) {
        int deleted = 0;
        for (String key : workspaces.unusedArchives(clock.instant().minus(SWEEP_GRACE), limit)) {
            try {
                storage.delete(new ObjectKey(key));
            } catch (ObjectStorageException failed) {
                if (failed.code() != ObjectStorageFailureCode.NOT_FOUND) {
                    LOG.atWarn().addKeyValue("event", "sandbox.workspace.delete_failed")
                            .addKeyValue("error_type", failed.getClass().getSimpleName())
                            .addKeyValue("error_code", failed.code().name()).log("Workspace archive not deleted");
                    continue;
                }
            }
            transactions.executeWithoutResult(_ -> workspaces.archiveDeleted(key));
            deleted++;
        }
        return deleted;
    }

    private static long size(String stdout) {
        try {
            String[] lines = stdout.strip().split("\n");
            return Long.parseLong(lines[lines.length - 1].strip());
        } catch (NumberFormatException unreadable) {
            return -1;
        }
    }

    private static void notSaved(String reason, @Nullable Exception failed) {
        var log = LOG.atWarn().addKeyValue("event", "sandbox.workspace.not_saved").addKeyValue("reason", reason);
        if (failed != null) log = log.addKeyValue("error_type", failed.getClass().getSimpleName());
        log.log("Workspace not saved");
    }

    private static void restoreFailed(String reason, @Nullable Exception failed) {
        var log = LOG.atWarn().addKeyValue("event", "sandbox.workspace.restore_failed").addKeyValue("reason", reason);
        if (failed != null) log = log.addKeyValue("error_type", failed.getClass().getSimpleName());
        log.log("Workspace not restored");
    }
}
