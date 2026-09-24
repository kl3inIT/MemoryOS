package io.memoryos.chat;

import io.memoryos.chat.application.ChatStorageProperties;
import io.memoryos.chat.persistence.JdbcChatLibraryRepository;
import io.memoryos.shared.ActorId;
import io.memoryos.iam.tenant.TenantAccessResolver;
import io.memoryos.shared.TenantId;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * How much of their file library a person may hold. The used bytes are the library's own sum, never a listing
 * of object storage, so what the page shows and what the limit reads are one number. The maximum is a
 * deployment setting ({@link ChatStorageProperties}) rather than a Tenant record: only the person whose library
 * it is ever sees it, on their own storage page, and no administrator raises or lowers it per person.
 */
@Service
@EnableConfigurationProperties(ChatStorageProperties.class)
public class ChatStorageQuotaService {
    private final TenantAccessResolver tenants;
    private final ChatStorageProperties storage;
    private final JdbcChatLibraryRepository library;

    public ChatStorageQuotaService(TenantAccessResolver tenants, ChatStorageProperties storage,
                                   JdbcChatLibraryRepository library) {
        this.tenants = tenants; this.storage = storage; this.library = library;
    }

    /** What the caller's library holds and what it may hold; {@code limitBytes} absent means no limit. */
    public record Usage(long usedBytes, long fileCount, @Nullable Long limitBytes,
                        Map<ChatLibraryFile.Category, Long> byCategory) {}

    @Transactional(readOnly = true)
    public Usage usage(ActorId actor) {
        var tenant = tenants.findActiveTenant(actor).orElseThrow(ChatException::unavailable);
        var used = library.usage(tenant, actor);
        return new Usage(used.totalBytes(), used.fileCount(), storage.libraryLimit().orElse(null),
                used.byCategory());
    }

    /**
     * Refuses a write that would take the person past the limit, before any byte is stored. The used bytes are
     * read at the moment of the check: two writes racing may both be admitted, and the next one is refused,
     * because a quota bounds a library rather than fencing a transaction.
     */
    @Transactional(readOnly = true)
    public void requireRoom(TenantId tenant, ActorId actor, long additionalBytes) {
        var limit = storage.libraryLimit();
        if (limit.isEmpty()) return;
        long used = library.usage(tenant, actor).totalBytes();
        if (used + additionalBytes > limit.get()) throw ChatException.storageFull(used, limit.get());
    }

    /** Whether there is room, for a write that must not fail the turn it belongs to. */
    @Transactional(readOnly = true)
    public boolean hasRoom(TenantId tenant, ActorId actor, long additionalBytes) {
        try {
            requireRoom(tenant, actor, additionalBytes);
            return true;
        } catch (ChatException full) {
            return false;
        }
    }
}
