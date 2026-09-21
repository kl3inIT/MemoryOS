package io.memoryos.chat;

import io.memoryos.chat.persistence.JdbcChatLibraryRepository;
import io.memoryos.chat.persistence.JdbcUserFileRepository;
import io.memoryos.iam.identity.ActorId;
import io.memoryos.iam.tenant.TenantAccessResolver;
import io.memoryos.iam.tenant.TenantId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads the caller's own files across uploads, generated files and generated images (MEM-142). */
@Service
public class ChatLibraryService {
    private final TenantAccessResolver tenants;
    private final JdbcChatLibraryRepository library;
    private final JdbcUserFileRepository files;

    public ChatLibraryService(TenantAccessResolver tenants, JdbcChatLibraryRepository library, JdbcUserFileRepository files) {
        this.tenants = tenants; this.library = library; this.files = files;
    }

    public record Page(List<ChatLibraryFile> items, long totalCount, long totalBytes, boolean hasMore) {}

    /** {@code session} narrows the list to one conversation's own files (MEM-144); null lists everything. */
    @Transactional(readOnly = true)
    public Page list(ActorId actor, String query, Set<ChatLibraryFile.Source> sources,
                     Set<ChatLibraryFile.Category> categories, @Nullable UUID session,
                     ChatLibraryFile.Sort sort, int offset, int limit) {
        if (query.length() > 200 || query.indexOf('\0') >= 0) throw ChatException.invalid("Invalid search text.");
        ChatPersonaService.page(offset, limit);
        var tenant = tenants.findActiveTenant(actor).orElseThrow(ChatException::unavailable);
        // One extra row answers hasMore without counting twice; the window total already covers the filter.
        var page = library.page(tenant, actor, query.trim(), names(sources), names(categories), session, sort, offset, limit + 1);
        boolean hasMore = page.items().size() > limit;
        var items = hasMore ? page.items().subList(0, limit) : page.items();
        return new Page(withUsage(tenant, items), page.totalCount(), page.totalBytes(), hasMore);
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
