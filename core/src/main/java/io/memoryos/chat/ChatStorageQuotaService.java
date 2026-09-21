package io.memoryos.chat;

import io.memoryos.chat.persistence.JdbcChatLibraryRepository;
import io.memoryos.chat.persistence.JdbcChatStorageQuotaRepository;
import io.memoryos.iam.group.IamAuthorization;
import io.memoryos.iam.group.IamCapability;
import io.memoryos.iam.identity.ActorId;
import io.memoryos.iam.tenant.TenantAccessResolver;
import io.memoryos.iam.tenant.TenantId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * How much of their file library a person may hold (MEM-152). The used bytes are the library's own sum, never a
 * listing of object storage, so what the page shows and what the limit reads are one number. A Tenant limit is
 * administered by a model manager, as every other Tenant-wide Chat limit is, and may be raised for one person.
 * No limit recorded means no limit, which is how Chat behaved before this.
 */
@Service
public class ChatStorageQuotaService {
    /** Enough rows to administer a Tenant's exceptions without paging; a Tenant with more needs its own page. */
    public static final int OVERRIDE_LIMIT = 200;

    private final TenantAccessResolver tenants;
    private final IamAuthorization authorization;
    private final JdbcChatStorageQuotaRepository quotas;
    private final JdbcChatLibraryRepository library;

    public ChatStorageQuotaService(TenantAccessResolver tenants, IamAuthorization authorization,
                                   JdbcChatStorageQuotaRepository quotas, JdbcChatLibraryRepository library) {
        this.tenants = tenants; this.authorization = authorization; this.quotas = quotas; this.library = library;
    }

    /** What the caller's library holds and what it may hold; {@code limitBytes} absent means no limit. */
    public record Usage(long usedBytes, long fileCount, @Nullable Long limitBytes,
                        Map<ChatLibraryFile.Category, Long> byCategory) {}

    public record Administration(@Nullable Long tenantLimitBytes, List<Person> people) {
        public record Person(UUID actorId, @Nullable String name, long maxBytes) {}
    }

    @Transactional(readOnly = true)
    public Usage usage(ActorId actor) {
        var tenant = tenants.findActiveTenant(actor).orElseThrow(ChatException::unavailable);
        var used = library.usage(tenant, actor);
        return new Usage(used.totalBytes(), used.fileCount(), quotas.limit(tenant, actor).orElse(null),
                used.byCategory());
    }

    /**
     * Refuses a write that would take the person past their limit, before any byte is stored. The used bytes are
     * read at the moment of the check: two writes racing may both be admitted, and the next one is refused,
     * because a quota bounds a library rather than fencing a transaction.
     */
    @Transactional(readOnly = true)
    public void requireRoom(TenantId tenant, ActorId actor, long additionalBytes) {
        var limit = quotas.limit(tenant, actor);
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

    @Transactional(readOnly = true)
    public Administration read(ActorId actor) {
        var tenant = authorization.require(actor, IamCapability.MODELS_MANAGE, false).tenantId();
        return new Administration(quotas.tenantLimit(tenant).orElse(null),
                quotas.overrides(tenant, OVERRIDE_LIMIT).stream()
                        .map(row -> new Administration.Person(row.actor(), row.name(), row.maxBytes())).toList());
    }

    @Transactional
    public Administration setTenantLimit(ActorId actor, @Nullable Long maxBytes) {
        var tenant = authorization.lockAndRequireExclusive(actor, IamCapability.MODELS_MANAGE).tenantId();
        quotas.tenantLimit(tenant, bounded(maxBytes));
        return read(actor);
    }

    @Transactional
    public Administration setPersonLimit(ActorId actor, UUID person, @Nullable Long maxBytes) {
        var tenant = authorization.lockAndRequireExclusive(actor, IamCapability.MODELS_MANAGE).tenantId();
        if (!quotas.override(tenant, new ActorId(person), bounded(maxBytes))) throw ChatException.unavailable();
        return read(actor);
    }

    /** A limit of nothing is not a limit, and a limit beyond what storage takes is not one either. */
    private static @Nullable Long bounded(@Nullable Long maxBytes) {
        if (maxBytes == null) return null;
        if (maxBytes < 1 || maxBytes > 1024L * 1024 * 1024 * 1024) {
            throw ChatException.invalid("A storage limit must be between 1 byte and 1 TiB.");
        }
        return maxBytes;
    }
}
