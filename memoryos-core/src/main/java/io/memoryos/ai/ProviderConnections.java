package io.memoryos.ai;

import io.memoryos.audit.AuditAction;
import io.memoryos.audit.AuditRecord;
import io.memoryos.audit.AuditTrail;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.util.Collection;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The administration every per-Tenant provider connection shares — image generation, Web search and voice: the
 * endpoint check, the revision check before a credential changes, whether a connection can be used, choosing the one
 * connection that serves a function, and the audit record of the change. Each connection keeps its own records, its
 * own errors and what it validates beyond this; only the identical steps live here.
 */
@Component
public class ProviderConnections {
    private final ProviderCredentials credentials;
    private final AuditTrail audit;

    public ProviderConnections(ProviderCredentials credentials, AuditTrail audit) {
        this.credentials = credentials;
        this.audit = audit;
    }

    /**
     * A connection's own endpoint: refused when the provider needs one and none is given, and validated as every
     * provider endpoint is when one is.
     */
    public static void checkEndpoint(String endpoint, boolean required, Supplier<? extends RuntimeException> missing) {
        if (required && endpoint.isBlank()) throw missing.get();
        if (!endpoint.isEmpty()) ModelCatalogService.validateEndpoint(endpoint);
    }

    /**
     * The credential to store after a save, once the save is known to name the revision it was made from. A stale
     * revision is refused with the connection's own conflict before anything is encrypted.
     */
    public @Nullable String reconfigure(UUID tenant, UUID connection, long stored, long expected,
            @Nullable String previous, ProviderCredentials.@Nullable Change change,
            Supplier<? extends RuntimeException> conflict) {
        if (stored != expected) throw conflict.get();
        return credentials.update(tenant, connection, previous, change);
    }

    /** A connection whose provider needs a key is usable only once one is stored. */
    public boolean usable(boolean requiresKey, @Nullable String credential) {
        return !requiresKey || credentials.configured(credential);
    }

    public boolean configured(@Nullable String credential) {
        return credentials.configured(credential);
    }

    /** The plaintext key of a stored connection, empty when it has none. */
    public String key(UUID tenant, @Nullable UUID connection, @Nullable String credential) {
        if (credential == null || connection == null) return "";
        return credentials.resolve(tenant, connection, credential);
    }

    /**
     * Makes {@code chosen} the only connection serving a function, or none when it is null. The old winner is cleared
     * and flushed first, because the partial unique index on the active flag would refuse two winners at once.
     */
    public static <E> void selectOnly(Collection<E> all, @Nullable E chosen, BiConsumer<E, Boolean> select,
            Runnable flush) {
        for (var connection : all) select.accept(connection, false);
        flush.run();
        if (chosen != null) select.accept(chosen, true);
    }

    /** One change to a connection, in the audit stream. {@code credential} is present only when a save named one. */
    public void audit(AuditAction action, String resource, UUID tenant, ActorId actor, @Nullable String provider,
            String change, @Nullable String credential) {
        var record = AuditRecord.of(action, new TenantId(tenant)).actor(actor).resource(resource, provider, provider)
                .detail("change", change);
        if (credential != null) record.detail("credentialChange", credential);
        audit.record(record.build());
    }

    /** What a save did to the credential, as the audit stream names it. */
    public static String credentialChange(ProviderCredentials.@Nullable Change change) {
        return change == null || change.action() == null ? ProviderCredentials.Action.KEEP.name()
                : change.action().name();
    }
}
