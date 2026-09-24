package io.memoryos.library;

import io.memoryos.library.persistence.JdbcChatLibraryArchiveRepository.Claim;
import io.memoryos.library.persistence.JdbcChatLibraryArchiveRepository;
import io.memoryos.shared.ActorId;
import io.memoryos.iam.tenant.TenantAccessResolver;
import io.memoryos.shared.TenantId;
import io.memoryos.objectstorage.ObjectContent;
import io.memoryos.objectstorage.ObjectStorage;
import io.memoryos.objectstorage.ObjectStorageException;
import io.memoryos.objectstorage.ObjectStorageFailureCode;
import io.memoryos.objectstorage.ObjectWriteService;
import io.memoryos.objectstorage.StoredObjectRegistry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Downloading a library selection as one ZIP (MEM-152). The request is recorded, a Worker packs the files from
 * object storage into a tracked server write, and the owner downloads it until it expires, when the same
 * capability releases its bytes. A file deleted between the request and the packing is skipped and reported,
 * because a selection is normally still being edited while the archive is built.
 */
@Service
public class ChatLibraryArchiveService {
    private static final Logger LOG = LoggerFactory.getLogger(ChatLibraryArchiveService.class);

    public static final int MAX_FILES = 100;
    /** The storage adapter writes an object of at most 32 MiB, so an archive's input is bounded below it. */
    public static final long MAX_TOTAL_BYTES = 30L * 1024 * 1024;
    public static final int MAX_ACTIVE_PER_OWNER = 3;
    public static final int MAX_ATTEMPTS = 3;
    public static final int LIST_LIMIT = 20;
    static final Duration LEASE = Duration.ofMinutes(5);
    static final Duration LIFETIME = Duration.ofHours(6);
    static final String MEDIA_TYPE = "application/zip";

    private final TenantAccessResolver tenants;
    private final JdbcChatLibraryArchiveRepository archives;
    private final LibraryContents contents;
    private final ObjectWriteService writes;
    private final ObjectStorage storage;
    private final StoredObjectRegistry storedObjects;
    private final TransactionTemplate tx;

    public ChatLibraryArchiveService(TenantAccessResolver tenants, JdbcChatLibraryArchiveRepository archives,
                                     LibraryContents contents, ObjectWriteService writes, ObjectStorage storage,
                                     StoredObjectRegistry storedObjects, PlatformTransactionManager transactionManager) {
        this.tenants = tenants; this.archives = archives; this.contents = contents;
        this.writes = writes; this.storage = storage; this.storedObjects = storedObjects;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public record Download(ObjectContent content, String filename) {}

    /** Records a request for the files the caller's library lists right now. */
    @Transactional
    public ChatLibraryArchive request(ActorId actor, List<ChatLibraryArchiveItem> requested) {
        var unique = new LinkedHashSet<>(requested);
        if (unique.isEmpty() || unique.size() > MAX_FILES) {
            throw LibraryException.invalid("Select between 1 and " + MAX_FILES + " files.");
        }
        var tenant = tenants.findActiveTenant(actor).orElseThrow(LibraryException::unavailable);
        if (archives.active(tenant, actor) >= MAX_ACTIVE_PER_OWNER) {
            throw LibraryException.conflict();
        }
        var listed = listed(tenant, actor, unique);
        if (listed.size() != unique.size()) throw LibraryException.unavailable();
        long total = listed.stream().mapToLong(ChatLibraryFile::sizeBytes).sum();
        if (total > MAX_TOTAL_BYTES) {
            throw LibraryException.invalid("The selection exceeds " + MAX_TOTAL_BYTES / (1024 * 1024) + " MiB. Select fewer files.");
        }
        return archives.insert(tenant, actor, UUID.randomUUID(), List.copyOf(unique), total, LIFETIME);
    }

    @Transactional(readOnly = true)
    public List<ChatLibraryArchive> list(ActorId actor) {
        return archives.list(tenant(actor), actor, LIST_LIMIT);
    }

    @Transactional(readOnly = true)
    public ChatLibraryArchive get(ActorId actor, UUID id) {
        return archives.find(tenant(actor), actor, id).orElseThrow(LibraryException::unavailable);
    }

    /** The archive's bytes; only its owner reads them, and only while it lives. */
    public Download open(ActorId actor, UUID id) {
        var tenant = tenant(actor);
        var key = archives.content(tenant, actor, id).orElseThrow(LibraryException::unavailable);
        var name = "memoryos-files-"
                + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now()) + ".zip";
        try {
            return new Download(storage.open(key), name);
        } catch (ObjectStorageException failure) {
            if (failure.code() == ObjectStorageFailureCode.NOT_FOUND) throw LibraryException.unavailable();
            throw failure;
        }
    }

    /** Packs the oldest waiting archive, if any; the Worker calls this on a fixed delay. */
    public boolean buildNext() {
        int abandoned = archives.failAbandoned(MAX_ATTEMPTS);
        if (abandoned > 0) LOG.warn("Library archives failed after {} attempts: {}", MAX_ATTEMPTS, abandoned);
        var claimed = archives.claim(LEASE, MAX_ATTEMPTS);
        if (claimed.isEmpty()) return false;
        var claim = claimed.get();
        try {
            pack(claim);
        } catch (RuntimeException failure) {
            LOG.atError().addKeyValue("event", "chat.library.archive.failed")
                    .addKeyValue("attempt", claim.attempts()).addKeyValue("error_type", failure.getClass().getName())
                    .log("Library archive could not be packed");
            archives.markFailed(claim.tenant(), claim.id(), claim.attempts(), MAX_ATTEMPTS,
                    "The archive could not be packed.");
        }
        return true;
    }

    private void pack(Claim claim) {
        var tenant = new TenantId(claim.tenant());
        var owner = new ActorId(claim.owner());
        // Ownership is resolved again here: a file deleted since the request is no longer the owner's to pack.
        var current = listed(tenant, owner, claim.requested());
        var skipped = new ArrayList<String>();
        var packed = new ByteArrayOutputStream();
        var taken = new LinkedHashSet<String>();
        int entries = 0;
        try (var zip = new ZipOutputStream(packed)) {
            for (var requested : claim.requested()) {
                var file = current.stream()
                        .filter(row -> row.source() == requested.source() && row.id().equals(requested.id()))
                        .findFirst();
                if (file.isEmpty()) continue;
                var bytes = contents.read(tenant, owner, file.get());
                if (bytes.isEmpty()) {
                    skipped.add(file.get().filename());
                    continue;
                }
                zip.putNextEntry(new ZipEntry(LibraryContents.unique(taken, file.get().filename())));
                zip.write(bytes.get());
                zip.closeEntry();
                entries++;
            }
        } catch (IOException broken) {
            throw new UncheckedIOException(broken);
        }
        // A file the owner deleted between the request and the packing is reported, not an error.
        for (var requested : claim.requested()) {
            if (current.stream().noneMatch(row -> row.source() == requested.source() && row.id().equals(requested.id()))) {
                skipped.add(requested.id().toString());
            }
        }
        // An archive of nothing is not a download: an empty ZIP still carries its end-of-directory record.
        if (entries == 0) {
            archives.markFailed(claim.tenant(), claim.id(), claim.attempts(), 0,
                    "None of the selected files is available any more.");
            return;
        }
        var staged = writes.stage(tenant, new ObjectWriteService.Specification("library-archive.zip", MEDIA_TYPE, false),
                packed.toByteArray());
        boolean adopted = false;
        try {
            adopted = Boolean.TRUE.equals(tx.execute(ignored -> {
                writes.adopt(tenant, staged);
                if (archives.markReady(claim.tenant(), claim.id(), claim.attempts(), staged.object().id(),
                        staged.object().key(), packed.size(), List.copyOf(skipped))) return true;
                // Another Worker took over after this lease lapsed; it owns the outcome.
                throw new LeaseLost();
            }));
        } catch (LeaseLost lost) {
            LOG.warn("Library archive lease lapsed before it was stored");
        } finally {
            if (!adopted) writes.discard(tenant, staged);
        }
    }

    /**
     * Releases expired archives: mark the object delete-pending, delete the key outside any transaction, then
     * release ownership and the row under this sweep's claim, as the artifact cleanup does.
     */
    public int sweepExpired() {
        int released = 0;
        var claimed = Objects.requireNonNull(tx.execute(ignored -> archives.claimExpired(20, LEASE)));
        for (var expired : claimed) {
            try {
                tx.executeWithoutResult(ignored -> storedObjects.markDeletePending(expired.tenant(), expired.object()));
                storage.delete(expired.key());
                tx.executeWithoutResult(ignored -> {
                    writes.releaseAdopted(expired.tenant(), expired.object());
                    storedObjects.remove(expired.tenant(), expired.object());
                    if (!archives.remove(expired)) throw new IllegalStateException("archive cleanup claim lapsed");
                });
                released++;
            } catch (RuntimeException failure) {
                LOG.atWarn().addKeyValue("event", "chat.library.archive.cleanup.retry")
                        .addKeyValue("error_type", failure.getClass().getName())
                        .log("Expired library archive cleanup failed; the claim lapses and the sweep retries");
            }
        }
        return released;
    }

    private List<ChatLibraryFile> listed(TenantId tenant, ActorId owner, java.util.Collection<ChatLibraryArchiveItem> requested) {
        return contents.listed(tenant, owner, requested, MAX_FILES);
    }

    private TenantId tenant(ActorId actor) {
        return tenants.findActiveTenant(actor).orElseThrow(LibraryException::unavailable);
    }

    private static final class LeaseLost extends RuntimeException {
        private LeaseLost() { super(null, null, false, false); }
    }
}
