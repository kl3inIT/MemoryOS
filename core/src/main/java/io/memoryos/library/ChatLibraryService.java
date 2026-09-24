package io.memoryos.library;

import io.memoryos.library.persistence.JdbcChatLibraryRepository;
import io.memoryos.library.persistence.JdbcUserFileRepository;
import io.memoryos.shared.ActorId;
import io.memoryos.iam.tenant.TenantAccessResolver;
import io.memoryos.shared.TenantId;
import io.memoryos.objectstorage.ObjectStorage;
import io.memoryos.objectstorage.ObjectStorageException;
import io.memoryos.objectstorage.ObjectStorageFailureCode;
import io.memoryos.objectstorage.ObjectUploadException;
import io.memoryos.objectstorage.ObjectWriteService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Reads the caller's own files across uploads, generated files and generated images (MEM-142). */
@Service
public class ChatLibraryService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChatLibraryService.class);

    /** The largest object the storage adapter writes from memory; artifacts are bounded well below it. */
    private static final long MAX_COPY_BYTES = 32L * 1024 * 1024;

    private final TenantAccessResolver tenants;
    private final JdbcChatLibraryRepository library;
    private final JdbcUserFileRepository files;
    private final FileAttachments attachments;
    private final LibraryArtifacts artifacts;
    private final ObjectStorage storage;
    private final ObjectWriteService writes;
    private final ChatFileProperties policy;
    private final ChatStorageQuotaService quotas;
    private final TransactionTemplate tx;
    private final ChatFileSearchService fileSearch;

    public ChatLibraryService(TenantAccessResolver tenants, JdbcChatLibraryRepository library, JdbcUserFileRepository files,
                              FileAttachments attachments, LibraryArtifacts artifacts, ObjectStorage storage,
                              ObjectWriteService writes, ChatFileProperties policy, ChatStorageQuotaService quotas,
                              PlatformTransactionManager transactionManager, ChatFileSearchService fileSearch) {
        this.tenants = tenants; this.library = library; this.files = files;
        this.attachments = attachments; this.artifacts = artifacts;
        this.storage = storage; this.writes = writes; this.policy = policy; this.quotas = quotas;
        this.fileSearch = fileSearch;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public record Page(List<ChatLibraryFile> items, long totalCount, long totalBytes, boolean hasMore) {}

    /**
     * What to list. {@code session} narrows it to one conversation's own files (MEM-144); {@code favorites} to the
     * starred ones; {@code pending} lists the owner's uploads still uploading, processing or failed instead of the
     * READY files, so the library can show an upload's progress and let it be retried (MEM-152).
     */
    public record Listing(String query, Set<ChatLibraryFile.Source> sources, Set<ChatLibraryFile.Category> categories,
                          @Nullable UUID session, boolean favorites, boolean pending, boolean trash,
                          ChatLibraryFile.Sort sort, int offset, int limit) {
        public Listing {
            sources = Set.copyOf(sources); categories = Set.copyOf(categories);
        }
    }

    @Transactional(readOnly = true)
    public Page list(ActorId actor, Listing listing) {
        String query = listing.query();
        if (query.length() > 200 || query.indexOf('\0') >= 0) throw LibraryException.invalid("Invalid search text.");
        Paging.check(listing.offset(), listing.limit());
        var tenant = tenants.findActiveTenant(actor).orElseThrow(LibraryException::unavailable);
        var filter = new JdbcChatLibraryRepository.Filter(query.trim(), names(listing.sources()),
                names(listing.categories()), listing.session(), listing.favorites(), listing.pending(),
                listing.trash(), null);
        // One extra row answers hasMore without counting twice; the window total already covers the filter.
        var page = library.page(tenant, actor, filter, listing.sort(), listing.offset(), listing.limit() + 1);
        boolean hasMore = page.items().size() > listing.limit();
        var items = hasMore ? page.items().subList(0, listing.limit()) : page.items();
        return new Page(withUsage(tenant, items), page.totalCount(), page.totalBytes(), hasMore);
    }

    /** A file the owner renamed or starred, as the library now lists it. */
    public ChatLibraryFile update(ActorId actor, ChatLibraryFile.Source source, UUID id,
                                  @Nullable String filename, @Nullable Boolean favorite) {
        if (filename == null && favorite == null) throw LibraryException.invalid("Nothing to change.");
        var tenant = tenants.findActiveTenant(actor).orElseThrow(LibraryException::unavailable);
        String name = null;
        if (filename != null) {
            var current = one(tenant, actor, source, id).orElseThrow(LibraryException::unavailable);
            name = renamed(current.filename(), filename, source == ChatLibraryFile.Source.UPLOAD ? 255 : 200);
        }
        boolean updated = source == ChatLibraryFile.Source.UPLOAD
                ? library.update(tenant, actor, id, name, favorite)
                : artifacts.update(tenant, actor, source, id, name, favorite);
        if (!updated) throw LibraryException.unavailable();
        return one(tenant, actor, source, id).orElseThrow(LibraryException::unavailable);
    }

    /**
     * A new name for a file: trimmed, without control characters or path separators, and keeping the extension
     * the file has, because the extension is what previews, categories and the model read the type from.
     */
    static String renamed(String current, String requested, int limit) {
        String name = requested.strip();
        if (name.isEmpty() || name.codePoints().anyMatch(point -> Character.isISOControl(point) || point == '/' || point == '\\'))
            throw LibraryException.invalid("Use a file name without control characters or slashes.");
        int dot = current.lastIndexOf('.');
        String extension = dot > 0 ? current.substring(dot) : "";
        if (!extension.isEmpty() && !name.toLowerCase(java.util.Locale.ROOT).endsWith(extension.toLowerCase(java.util.Locale.ROOT)))
            name = name + extension;
        if (name.length() > limit || name.equals(extension)) throw LibraryException.invalid("Use a file name of 1 to " + limit + " characters.");
        return name;
    }

    private java.util.Optional<ChatLibraryFile> one(TenantId tenant, ActorId actor, ChatLibraryFile.Source source, UUID id) {
        // An upload is renamed while pending too, so both the READY and the pending lists are consulted.
        for (boolean pending : new boolean[] {false, true}) {
            var page = library.page(tenant, actor, new JdbcChatLibraryRepository.Filter("", Set.of(source.name()), Set.of(),
                    null, false, pending, Set.of(id)), ChatLibraryFile.Sort.NEWEST, 0, 1);
            if (!page.items().isEmpty()) return java.util.Optional.of(withUsage(tenant, page.items()).getFirst());
        }
        return java.util.Optional.empty();
    }

    public record ContentMatch(ChatLibraryFile file, List<Passage> passages) {
        public ContentMatch { passages = List.copyOf(passages); }
    }

    public record Passage(String text, int ordinal) {}

    /** The most recent uploads a content search looks through; the file search bounds its scope near this. */
    private static final int CONTENT_SEARCH_SCOPE = 4000;

    /**
     * Finds the owner's uploads by what they contain (MEM-152), through the same owner-private file search Chat's
     * `search_files` tool uses: only the caller's READY, indexed uploads are in scope, never a Source document or
     * another member's file. Generated files are not indexed, so they are found by name only.
     */
    public List<ContentMatch> searchContent(ActorId actor, String query) {
        String text = query.strip();
        if (text.isEmpty() || text.length() > 500 || text.indexOf('\0') >= 0) throw LibraryException.invalid("Invalid search text.");
        var tenant = tenants.findActiveTenant(actor).orElseThrow(LibraryException::unavailable);
        var scope = new java.util.LinkedHashSet<>(files.searchable(tenant, actor, CONTENT_SEARCH_SCOPE));
        if (scope.isEmpty()) return List.of();
        var passages = new LinkedHashMap<UUID, List<Passage>>();
        for (var hit : fileSearch.search(actor, tenant, scope, text)) {
            var list = passages.computeIfAbsent(hit.fileId(), ignored -> new ArrayList<>());
            if (list.size() < 3) list.add(new Passage(hit.passage().content(), hit.passage().ordinal()));
        }
        if (passages.isEmpty()) return List.of();
        var page = library.page(tenant, actor, new JdbcChatLibraryRepository.Filter("", Set.of("UPLOAD"), Set.of(), null,
                false, false, passages.keySet()), ChatLibraryFile.Sort.NEWEST, 0, passages.size());
        var rows = new LinkedHashMap<UUID, ChatLibraryFile>();
        withUsage(tenant, page.items()).forEach(file -> rows.put(file.id(), file));
        return passages.entrySet().stream().filter(entry -> rows.containsKey(entry.getKey()))
                .map(entry -> new ContentMatch(rows.get(entry.getKey()), entry.getValue())).toList();
    }

    /**
     * Takes bytes another capability produced into the caller's library, under the same rules a copy follows: the
     * caller's quota, a server-written object of the caller's own, the file worker's extraction. Asking twice for the same artifact returns
     * the file already made, and deleting that file lets the artifact be taken again, so a rewritten artifact
     * replaces rather than duplicates.
     */
    public UserFile publish(ActorId actor, ChatLibraryFile.Source source, UUID artifact, String filename,
            String mediaType, byte[] bytes) {
        if (source == ChatLibraryFile.Source.UPLOAD || source == ChatLibraryFile.Source.GENERATED
                || source == ChatLibraryFile.Source.IMAGE)
            throw LibraryException.invalid("Those artifacts are taken with a copy.");
        var tenant = tenants.findActiveTenant(actor).orElseThrow(LibraryException::unavailable);
        var existing = files.copy(tenant, actor, source.name(), artifact);
        if (existing.isPresent()) return existing.get().file();
        policy.validateSize(bytes.length);
        if (bytes.length > MAX_COPY_BYTES) throw LibraryException.invalid("File exceeds the configured Chat upload limit.");
        quotas.requireRoom(tenant, actor, bytes.length);
        return store(tenant, actor, source, artifact, filename, mediaType, bytes, false);
    }

    /** The file this artifact was already taken into, if the caller still has it. */
    public java.util.Optional<UserFile> published(ActorId actor, ChatLibraryFile.Source source, UUID artifact) {
        var tenant = tenants.findActiveTenant(actor).orElseThrow(LibraryException::unavailable);
        return files.copy(tenant, actor, source.name(), artifact).map(row -> row.file());
    }

    /**
     * Makes a generated file or image usable wherever an upload is (MEM-152): its bytes are copied into a new file
     * of the same owner, written through object storage's server-write lifecycle, which the file worker then
     * extracts like any other. Asking again returns the copy already made, so a double click or a retried request
     * never duplicates it. The copy is independent: deleting or purging the artifact leaves it intact, and deleting
     * the copy lets the artifact be copied again.
     */
    public UserFile copy(ActorId actor, ChatLibraryFile.Source source, UUID id) {
        if (source == ChatLibraryFile.Source.UPLOAD) throw LibraryException.invalid("An upload is already usable as it is.");
        var tenant = tenants.findActiveTenant(actor).orElseThrow(LibraryException::unavailable);
        var existing = files.copy(tenant, actor, source.name(), id);
        if (existing.isPresent()) return existing.get().file();
        var artifact = library.artifact(tenant, actor, source, id).orElseThrow(LibraryException::unavailable);
        policy.validateSize(artifact.sizeBytes());
        if (artifact.sizeBytes() > MAX_COPY_BYTES) throw LibraryException.invalid("File exceeds the configured Chat upload limit.");
        // A copy is a second stored file, so it needs room of its own.
        quotas.requireRoom(tenant, actor, artifact.sizeBytes());
        // Storage IO runs outside any transaction; ownership is checked again under the owner lock below.
        byte[] bytes = read(artifact);
        return store(tenant, actor, source, id, artifact.filename(), artifact.mediaType(), bytes, true);
    }

    /**
     * Writes a copy's bytes through the server-write lifecycle and records the copy (V127): the object is staged
     * outside any transaction, then adopted in the transaction that inserts the file under the owner lock. A copy
     * another request made meanwhile is returned instead, and an artifact deleted meanwhile, a membership that
     * changed, or any failure before the adoption commits discards the staged object at once.
     */
    private UserFile store(TenantId tenant, ActorId actor, ChatLibraryFile.Source source, UUID artifact,
            String filename, String mediaType, byte[] bytes, boolean artifactMustRemain) {
        ObjectWriteService.StagedObject staged;
        try {
            staged = writes.stage(tenant, new ObjectWriteService.Specification(filename, mediaType, false), bytes);
        } catch (ObjectStorageException failure) {
            throw ObjectUploadException.storageUnavailable(failure.code(), failure);
        }
        Stored stored = null;
        try {
            stored = Objects.requireNonNull(tx.execute(ignored -> {
                var current = write(actor);
                if (!current.equals(tenant)) return new Stored(null, false);
                var raced = files.copy(tenant, actor, source.name(), artifact);
                if (raced.isPresent()) return new Stored(raced.get().file(), false);
                if (artifactMustRemain && library.artifact(tenant, actor, source, artifact).isEmpty())
                    return new Stored(null, false);
                writes.adopt(tenant, staged);
                var made = files.createCopy(tenant, actor, staged.object(), source.name(), artifact);
                files.finalized(tenant, made, staged.object().id());
                return new Stored(files.owned(tenant, actor, made, false).orElseThrow().file(), true);
            }));
        } finally {
            if (stored == null || !stored.adopted()) discard(tenant, staged);
        }
        if (stored.file() == null) throw LibraryException.unavailable();
        return stored.file();
    }

    /**
     * Never leaves a staged copy behind. A discard that fails is not the caller's failure: the staged write reaches
     * its adoption deadline unadopted and the server-write cleanup reclaims it.
     */
    private void discard(TenantId tenant, ObjectWriteService.StagedObject staged) {
        try {
            writes.discard(tenant, staged);
        } catch (RuntimeException failure) {
            LOGGER.atWarn().addKeyValue("event", "chat.library.copy.discard_failed")
                    .addKeyValue("stored_object_id", staged.object().id().value())
                    .addKeyValue("error_type", failure.getClass().getName())
                    .log("Discarding an unadopted library copy failed; server-write cleanup reclaims it");
        }
    }

    /** What the copy transaction settled on, and whether it adopted the staged object. */
    private record Stored(@Nullable UserFile file, boolean adopted) {}

    private byte[] read(JdbcChatLibraryRepository.Artifact artifact) {
        try (var content = storage.open(artifact.key())) {
            var bytes = content.inputStream().readNBytes((int) MAX_COPY_BYTES + 1);
            if (bytes.length != artifact.sizeBytes() || bytes.length < 1) throw LibraryException.unavailable();
            return bytes;
        } catch (ObjectStorageException failure) {
            if (failure.code() == ObjectStorageFailureCode.NOT_FOUND) throw LibraryException.unavailable();
            throw ObjectUploadException.storageUnavailable(failure.code(), failure);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private TenantId write(ActorId actor) {
        var tenant = tenants.lockActiveMembership(actor).orElseThrow(LibraryException::unavailable).tenantId();
        files.lockOwner(tenant, actor);
        return tenant;
    }

    /** Only uploads can be attached, so only their ids are looked up. */
    private List<ChatLibraryFile> withUsage(TenantId tenant, List<ChatLibraryFile> items) {
        var uploads = items.stream().filter(file -> file.source() == ChatLibraryFile.Source.UPLOAD)
                .map(ChatLibraryFile::id).toList();
        if (uploads.isEmpty()) return List.copyOf(items);
        Map<UUID, List<ChatLibraryFile.Usage>> usage = new LinkedHashMap<>();
        for (var used : attachments.holders(tenant, uploads)) {
            usage.computeIfAbsent(used.fileId(), id -> new ArrayList<>())
                    .add(new ChatLibraryFile.Usage(used.kind(), used.id(), used.name()));
        }
        return items.stream().map(file -> usage.containsKey(file.id()) ? file.withUsedBy(usage.get(file.id())) : file).toList();
    }

    private static Set<String> names(Set<? extends Enum<?>> values) {
        return values.stream().map(Enum::name).collect(Collectors.toUnmodifiableSet());
    }
}
