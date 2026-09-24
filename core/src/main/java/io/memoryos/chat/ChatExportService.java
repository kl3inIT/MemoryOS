package io.memoryos.chat;

import io.memoryos.chat.export.ChatExportWriter;
import io.memoryos.library.ChatLibraryFile;
import io.memoryos.library.LibraryContents;
import io.memoryos.chat.export.persistence.JdbcChatExportRepository.Claim;
import io.memoryos.chat.export.persistence.JdbcChatExportRepository;
import io.memoryos.chat.session.persistence.JdbcChatRepository;
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
import java.util.Optional;
import java.util.Set;
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
 * Taking one's own data out of MemoryOS (MEM-153): every conversation the person has not deleted, as JSON and
 * as a page a browser can open on its own, together with the files their library lists. A Worker packs it into
 * a tracked server write, its owner downloads it until it expires, and the same capability releases the bytes —
 * the mechanics of the MEM-152 library archive, reused because an export is the same kind of job.
 *
 * <p>An export is bounded, because it is written as one object: transcripts are always included, and files are
 * taken until the budget runs out. Whatever is left out is named in the export's own index, so nobody has to
 * guess whether they got everything.
 */
@Service
public class ChatExportService {
    private static final Logger LOG = LoggerFactory.getLogger(ChatExportService.class);

    /** The storage adapter writes an object of at most 32 MiB, so the packed export stays below it. */
    public static final long MAX_TOTAL_BYTES = 30L * 1024 * 1024;
    public static final int MAX_SESSIONS = 500;
    public static final int MAX_MESSAGES_PER_SESSION = 500;
    public static final int MAX_FILES = 200;
    public static final int MAX_ATTEMPTS = 3;
    public static final int LIST_LIMIT = 5;
    static final Duration LEASE = Duration.ofMinutes(10);
    static final Duration LIFETIME = Duration.ofHours(24);
    static final String MEDIA_TYPE = "application/zip";

    private final TenantAccessResolver tenants;
    private final JdbcChatExportRepository exports;
    private final JdbcChatRepository chats;
    private final LibraryContents library;
    private final ObjectWriteService writes;
    private final ObjectStorage storage;
    private final StoredObjectRegistry storedObjects;
    private final TransactionTemplate tx;

    public ChatExportService(TenantAccessResolver tenants, JdbcChatExportRepository exports,
                             JdbcChatRepository chats, LibraryContents library,
                             ObjectWriteService writes, ObjectStorage storage,
                             StoredObjectRegistry storedObjects, PlatformTransactionManager transactionManager) {
        this.tenants = tenants; this.exports = exports; this.chats = chats; this.library = library;
        this.writes = writes; this.storage = storage; this.storedObjects = storedObjects;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public record Download(ObjectContent content, String filename) {}

    /** Records the request; one per person at a time, because an export reads everything they own. */
    @Transactional
    public ChatExport request(ActorId actor) {
        var tenant = tenants.findActiveTenant(actor).orElseThrow(ChatException::unavailable);
        return exports.insert(tenant, actor, UUID.randomUUID(), LIFETIME).orElseThrow(ChatException::conflict);
    }

    @Transactional(readOnly = true)
    public List<ChatExport> list(ActorId actor) {
        return exports.list(tenant(actor), actor, LIST_LIMIT);
    }

    @Transactional(readOnly = true)
    public ChatExport get(ActorId actor, UUID id) {
        return exports.find(tenant(actor), actor, id).orElseThrow(ChatException::unavailable);
    }

    /** The export's bytes; only its owner reads them, and only while it lives. */
    public Download open(ActorId actor, UUID id) {
        var tenant = tenant(actor);
        var key = exports.content(tenant, actor, id).orElseThrow(ChatException::unavailable);
        var name = "memoryos-export-"
                + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now())
                + ".zip";
        try {
            return new Download(storage.open(key), name);
        } catch (ObjectStorageException failure) {
            if (failure.code() == ObjectStorageFailureCode.NOT_FOUND) throw ChatException.unavailable();
            throw failure;
        }
    }

    /** Packs the oldest waiting export, if any; the Worker calls this on a fixed delay. */
    public boolean buildNext() {
        int abandoned = exports.failAbandoned(MAX_ATTEMPTS);
        if (abandoned > 0) LOG.warn("Exports failed after {} attempts: {}", MAX_ATTEMPTS, abandoned);
        var claimed = exports.claim(LEASE, MAX_ATTEMPTS);
        if (claimed.isEmpty()) return false;
        var claim = claimed.get();
        try {
            pack(claim);
        } catch (RuntimeException failure) {
            LOG.atError().addKeyValue("event", "chat.export.failed").addKeyValue("attempt", claim.attempts())
                    .addKeyValue("error_type", failure.getClass().getName()).log("Export could not be packed");
            exports.markFailed(claim.tenant(), claim.id(), claim.attempts(), MAX_ATTEMPTS,
                    "The export could not be packed.");
        }
        return true;
    }

    private void pack(Claim claim) {
        var tenant = new TenantId(claim.tenant());
        var owner = new ActorId(claim.owner());
        var skipped = new ArrayList<String>();
        var entries = new ArrayList<ChatExportWriter.Entry>();
        var taken = new LinkedHashSet<String>();
        var packed = new ByteArrayOutputStream();
        int sessions = 0;
        int packedFiles = 0;
        long budget = MAX_TOTAL_BYTES;

        try (var zip = new ZipOutputStream(packed)) {
            // Transcripts first: they are what an export is for, and they are small beside the files. They are
            // still counted, because an account with thousands of long conversations would otherwise write an
            // object the storage adapter refuses.
            for (var session : conversations(tenant, owner)) {
                var history = chats.history(session, null, MAX_MESSAGES_PER_SESSION);
                byte[] data = ChatExportWriter.json(session, history);
                byte[] page = ChatExportWriter.html(session, history);
                if (data.length + page.length > budget) {
                    skipped.add(session.title());
                    continue;
                }
                String stem = "conversations/" + slug(session.title()) + "-" + session.id();
                write(zip, taken, stem + ".json", data);
                write(zip, taken, stem + ".html", page);
                budget -= data.length + page.length;
                entries.add(new ChatExportWriter.Entry(session.title(), stem + ".html"));
                sessions++;
            }
            // Then the files, until the budget runs out; what does not fit is named rather than dropped quietly.
            for (var file : libraryFiles(tenant, owner)) {
                if (packedFiles >= MAX_FILES || file.sizeBytes() > budget) {
                    skipped.add(file.filename());
                    continue;
                }
                var bytes = read(tenant, owner, file);
                if (bytes.isEmpty()) {
                    skipped.add(file.filename());
                    continue;
                }
                write(zip, taken, "files/" + file.filename(), bytes.get());
                budget -= bytes.get().length;
                packedFiles++;
            }
            write(zip, taken, "index.html", ChatExportWriter.index(entries, List.copyOf(skipped)));
        } catch (IOException broken) {
            throw new UncheckedIOException(broken);
        }

        var staged = writes.stage(tenant,
                new ObjectWriteService.Specification("memoryos-export.zip", MEDIA_TYPE, false), packed.toByteArray());
        boolean adopted = false;
        try {
            int finalSessions = sessions;
            int finalFiles = packedFiles;
            adopted = Boolean.TRUE.equals(tx.execute(ignored -> {
                writes.adopt(tenant, staged);
                if (exports.markReady(claim.tenant(), claim.id(), claim.attempts(), staged.object().id(),
                        staged.object().key(), packed.size(), finalSessions, finalFiles, List.copyOf(skipped)))
                    return true;
                // Another Worker took over after this lease lapsed; it owns the outcome.
                throw new LeaseLost();
            }));
        } catch (LeaseLost lost) {
            LOG.warn("Export lease lapsed before it was stored");
        } finally {
            if (!adopted) writes.discard(tenant, staged);
        }
    }

    /**
     * Releases expired exports: mark the object delete-pending, delete the key outside any transaction, then
     * release ownership and the row under this sweep's claim, as the library archive sweep does.
     */
    public int sweepExpired() {
        int released = 0;
        var claimed = Objects.requireNonNull(tx.execute(ignored -> exports.claimExpired(20, LEASE)));
        for (var expired : claimed) {
            try {
                tx.executeWithoutResult(ignored -> storedObjects.markDeletePending(expired.tenant(), expired.object()));
                storage.delete(expired.key());
                tx.executeWithoutResult(ignored -> {
                    writes.releaseAdopted(expired.tenant(), expired.object());
                    storedObjects.remove(expired.tenant(), expired.object());
                    if (!exports.remove(expired)) throw new IllegalStateException("export cleanup claim lapsed");
                });
                released++;
            } catch (RuntimeException failure) {
                LOG.atWarn().addKeyValue("event", "chat.export.cleanup.retry")
                        .addKeyValue("error_type", failure.getClass().getName())
                        .log("Expired export cleanup failed; the claim lapses and the sweep retries");
            }
        }
        return released;
    }

    /**
     * What the export covers: the conversations on the sidebar and the archived ones, never a deleted or a
     * temporary conversation — one is gone as far as its owner is concerned, the other promised to leave nothing.
     */
    private List<ChatSession> conversations(TenantId tenant, ActorId owner) {
        var all = new ArrayList<ChatSession>();
        for (boolean archived : List.of(false, true)) {
            for (int offset = 0; offset < MAX_SESSIONS && all.size() < MAX_SESSIONS; offset += 100) {
                var page = chats.list(tenant, owner, archived, offset, 100);
                all.addAll(page);
                if (page.size() < 100) break;
            }
        }
        return all.size() > MAX_SESSIONS ? all.subList(0, MAX_SESSIONS) : all;
    }

    /** The files the owner's library lists right now, newest first; the library already hides what is gone. */
    private List<ChatLibraryFile> libraryFiles(TenantId tenant, ActorId owner) {
        return library.listed(tenant, owner, null, MAX_FILES);
    }

    private Optional<byte[]> read(TenantId tenant, ActorId owner, ChatLibraryFile file) {
        return library.read(tenant, owner, file);
    }

    private static void write(ZipOutputStream zip, Set<String> taken, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(LibraryContents.unique(taken, name)));
        zip.write(bytes);
        zip.closeEntry();
    }

    /**
     * A file name inside the ZIP: the title is a person's words and a ZIP entry is a path, so the accents are
     * separated off rather than replaced with dashes — a Vietnamese title still reads as itself in a file list.
     * The conversation's id follows the slug, so two conversations never collide on a name.
     */
    static String slug(String title) {
        var plain = java.text.Normalizer.normalize(title, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").replace("đ", "d").replace("Đ", "D");
        var slug = plain.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+)|(-+$)", "");
        if (slug.isEmpty()) slug = "conversation";
        return slug.length() > 60 ? slug.substring(0, 60) : slug;
    }

    private TenantId tenant(ActorId actor) {
        return tenants.findActiveTenant(actor).orElseThrow(ChatException::unavailable);
    }

    private static final class LeaseLost extends RuntimeException {
        private LeaseLost() { super(null, null, false, false); }
    }
}
