package io.memoryos.chat;

import io.memoryos.chat.application.ChatFileProperties;
import io.memoryos.chat.persistence.JdbcChatLibraryRepository;
import io.memoryos.chat.persistence.JdbcChatRepository;
import io.memoryos.chat.persistence.JdbcUserFileRepository;
import io.memoryos.iam.identity.ActorId;
import io.memoryos.iam.tenant.TenantAccessResolver;
import io.memoryos.iam.tenant.TenantId;
import io.memoryos.objectstorage.ContentSha256;
import io.memoryos.objectstorage.ObjectStorage;
import io.memoryos.objectstorage.ObjectStorageException;
import io.memoryos.objectstorage.ObjectUploadException;
import io.memoryos.objectstorage.ObjectUploadPurpose;
import io.memoryos.objectstorage.ObjectUploadService;
import io.memoryos.objectstorage.ObjectUploadSpecification;
import io.memoryos.objectstorage.VerifiedObject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Reads the caller's own files across uploads, generated files and generated images (MEM-142). */
@Service
public class ChatLibraryService {
    /** The largest object the storage adapter writes from memory; artifacts are bounded well below it. */
    private static final long MAX_COPY_BYTES = 32L * 1024 * 1024;

    private final TenantAccessResolver tenants;
    private final JdbcChatLibraryRepository library;
    private final JdbcUserFileRepository files;
    private final JdbcChatRepository chats;
    private final ObjectStorage storage;
    private final ObjectUploadService uploads;
    private final ChatFileProperties policy;
    private final ChatStorageQuotaService quotas;
    private final TransactionTemplate tx;
    private final ChatFileSearchService fileSearch;

    public ChatLibraryService(TenantAccessResolver tenants, JdbcChatLibraryRepository library, JdbcUserFileRepository files,
                              JdbcChatRepository chats, ObjectStorage storage, ObjectUploadService uploads,
                              ChatFileProperties policy, ChatStorageQuotaService quotas,
                              PlatformTransactionManager transactionManager, ChatFileSearchService fileSearch) {
        this.tenants = tenants; this.library = library; this.files = files; this.chats = chats;
        this.storage = storage; this.uploads = uploads; this.policy = policy; this.quotas = quotas;
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
        if (query.length() > 200 || query.indexOf('\0') >= 0) throw ChatException.invalid("Invalid search text.");
        ChatPersonaService.page(listing.offset(), listing.limit());
        var tenant = tenants.findActiveTenant(actor).orElseThrow(ChatException::unavailable);
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
        if (filename == null && favorite == null) throw ChatException.invalid("Nothing to change.");
        var tenant = tenants.findActiveTenant(actor).orElseThrow(ChatException::unavailable);
        String name = null;
        if (filename != null) {
            var current = one(tenant, actor, source, id).orElseThrow(ChatException::unavailable);
            name = renamed(current.filename(), filename, source == ChatLibraryFile.Source.UPLOAD ? 255 : 200);
        }
        if (!library.update(tenant, actor, source, id, name, favorite)) throw ChatException.unavailable();
        return one(tenant, actor, source, id).orElseThrow(ChatException::unavailable);
    }

    /**
     * A new name for a file: trimmed, without control characters or path separators, and keeping the extension
     * the file has, because the extension is what previews, categories and the model read the type from.
     */
    static String renamed(String current, String requested, int limit) {
        String name = requested.strip();
        if (name.isEmpty() || name.codePoints().anyMatch(point -> Character.isISOControl(point) || point == '/' || point == '\\'))
            throw ChatException.invalid("Use a file name without control characters or slashes.");
        int dot = current.lastIndexOf('.');
        String extension = dot > 0 ? current.substring(dot) : "";
        if (!extension.isEmpty() && !name.toLowerCase(java.util.Locale.ROOT).endsWith(extension.toLowerCase(java.util.Locale.ROOT)))
            name = name + extension;
        if (name.length() > limit || name.equals(extension)) throw ChatException.invalid("Use a file name of 1 to " + limit + " characters.");
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
        if (text.isEmpty() || text.length() > 500 || text.indexOf('\0') >= 0) throw ChatException.invalid("Invalid search text.");
        var tenant = tenants.findActiveTenant(actor).orElseThrow(ChatException::unavailable);
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
     * Makes a generated file or image usable wherever an upload is (MEM-152): its bytes are copied, inside object
     * storage's own lifecycle, into a new upload of the same owner, which the file worker then extracts like any
     * other. Asking again returns the copy already made, so a double click or a retried request never duplicates
     * it. The copy is independent: deleting or purging the artifact leaves it intact, and deleting the copy lets
     * the artifact be copied again.
     */
    public UserFile copy(ActorId actor, ChatLibraryFile.Source source, UUID id) {
        if (source == ChatLibraryFile.Source.UPLOAD) throw ChatException.invalid("An upload is already usable as it is.");
        var tenant = tenants.findActiveTenant(actor).orElseThrow(ChatException::unavailable);
        var existing = files.copy(tenant, actor, source.name(), id);
        if (existing.isPresent()) return existing.get().file();
        var artifact = library.artifact(tenant, actor, source, id).orElseThrow(ChatException::unavailable);
        policy.validateSize(artifact.sizeBytes());
        if (artifact.sizeBytes() > MAX_COPY_BYTES) throw ChatException.invalid("File exceeds the configured Chat upload limit.");
        // A copy is a second stored file, so it needs room of its own.
        quotas.requireRoom(tenant, actor, artifact.sizeBytes());
        // Storage IO runs outside any transaction; ownership is checked again under the owner lock below.
        byte[] bytes = read(artifact);
        var spec = new ObjectUploadSpecification(artifact.filename(), artifact.mediaType(), bytes.length,
                checksum(bytes), ObjectUploadPurpose.CHAT_FILE);
        VerifiedObject verified = uploads.write(tenant, spec, bytes);
        // An artifact deleted meanwhile answers empty, so the discard commits instead of rolling back with a throw.
        var copied = Objects.requireNonNull(tx.execute(ignored -> {
            var current = write(actor);
            if (!current.equals(tenant)) {
                uploads.discard(tenant, verified.uploadId(), verified.token());
                return java.util.Optional.<UserFile>empty();
            }
            var raced = files.copy(tenant, actor, source.name(), id);
            if (raced.isPresent()) {
                uploads.discard(tenant, verified.uploadId(), verified.token());
                return java.util.Optional.of(raced.get().file());
            }
            if (library.artifact(tenant, actor, source, id).isEmpty()) {
                uploads.discard(tenant, verified.uploadId(), verified.token());
                return java.util.Optional.<UserFile>empty();
            }
            var copy = files.createCopy(tenant, actor, verified.uploadId(), spec, source.name(), id);
            uploads.adopt(tenant, verified.uploadId(), verified.token());
            files.finalized(tenant, copy);
            return java.util.Optional.of(files.owned(tenant, actor, copy, false).orElseThrow().file());
        }));
        return copied.orElseThrow(ChatException::unavailable);
    }

    private byte[] read(JdbcChatLibraryRepository.Artifact artifact) {
        try (var content = storage.open(artifact.key())) {
            var bytes = content.inputStream().readNBytes((int) MAX_COPY_BYTES + 1);
            if (bytes.length != artifact.sizeBytes() || bytes.length < 1) throw ChatException.unavailable();
            return bytes;
        } catch (ObjectStorageException failure) {
            if (failure.code() == io.memoryos.objectstorage.ObjectStorageFailureCode.NOT_FOUND) throw ChatException.unavailable();
            throw ObjectUploadException.storageUnavailable(failure.code(), failure);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static ContentSha256 checksum(byte[] bytes) {
        try {
            return new ContentSha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private TenantId write(ActorId actor) {
        var tenant = tenants.lockActiveMembership(actor).orElseThrow(ChatException::unavailable).tenantId();
        chats.lockOwner(tenant, actor);
        return tenant;
    }

    /** Only uploads can be attached, so only their ids are looked up. */
    private List<ChatLibraryFile> withUsage(TenantId tenant, List<ChatLibraryFile> items) {
        var uploads = items.stream().filter(file -> file.source() == ChatLibraryFile.Source.UPLOAD)
                .map(ChatLibraryFile::id).toList();
        if (uploads.isEmpty()) return List.copyOf(items);
        Map<UUID, List<ChatLibraryFile.Usage>> usage = new LinkedHashMap<>();
        for (var used : files.usage(tenant, uploads)) {
            usage.computeIfAbsent(used.fileId(), id -> new ArrayList<>())
                    .add(new ChatLibraryFile.Usage(ChatLibraryFile.Usage.Kind.valueOf(used.kind().name()), used.id(), used.name()));
        }
        return items.stream().map(file -> usage.containsKey(file.id()) ? file.withUsedBy(usage.get(file.id())) : file).toList();
    }

    private static Set<String> names(Set<? extends Enum<?>> values) {
        return values.stream().map(Enum::name).collect(Collectors.toUnmodifiableSet());
    }
}
