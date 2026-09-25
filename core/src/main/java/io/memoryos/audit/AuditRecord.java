package io.memoryos.audit;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import org.jspecify.annotations.Nullable;

/**
 * One event on its way to the stream.
 *
 * <p>Built through {@link #of} so a call site reads as a sentence, and so the
 * declared fields of the action are the only thing its details can carry.
 */
public record AuditRecord(
        AuditAction action,
        AuditOutcome outcome,
        TenantId tenant,
        @Nullable ActorId actor,
        @Nullable String actorLabel,
        @Nullable String resourceType,
        @Nullable String resourceId,
        @Nullable String resourceLabel,
        Map<String, @Nullable Object> details) {

    /** Bounded like the columns that store them, so a long name is cut here rather than failing the write. */
    static final int LABEL_MAX = 320;
    static final int RESOURCE_ID_MAX = 200;

    public AuditRecord {
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(tenant, "tenant must not be null");
        // A declared field may be absent for this event (a member with no e-mail); nulls are dropped when stored.
        details = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    public static Builder of(AuditAction action, TenantId tenant) {
        return new Builder(action, tenant);
    }

    public static final class Builder {
        private final AuditAction action;
        private final TenantId tenant;
        private final Map<String, @Nullable Object> details = new LinkedHashMap<>();
        private AuditOutcome outcome = AuditOutcome.SUCCESS;
        private @Nullable ActorId actor;
        private @Nullable String actorLabel;
        private @Nullable String resourceType;
        private @Nullable String resourceId;
        private @Nullable String resourceLabel;

        private Builder(AuditAction action, TenantId tenant) {
            this.action = Objects.requireNonNull(action, "action must not be null");
            this.tenant = Objects.requireNonNull(tenant, "tenant must not be null");
        }

        /** Who did it; their name and e-mail at this moment are looked up and kept with the record. */
        public Builder actor(@Nullable ActorId actor) {
            return actor(actor, null);
        }

        /** Who did it, named by the caller: for someone not yet in the profile table, such as a refused sign-in. */
        public Builder actor(@Nullable ActorId actor, @Nullable String label) {
            this.actor = actor;
            this.actorLabel = cut(label, LABEL_MAX);
            return this;
        }

        /** What it was done to: a kind such as {@code GROUP} or {@code SOURCE}, its id and its name at the time. */
        public Builder resource(String type, @Nullable Object id, @Nullable String label) {
            this.resourceType = type;
            this.resourceId = id == null ? null : cut(String.valueOf(id), RESOURCE_ID_MAX);
            this.resourceLabel = cut(label, LABEL_MAX);
            return this;
        }

        public Builder outcome(AuditOutcome outcome) {
            this.outcome = Objects.requireNonNull(outcome, "outcome must not be null");
            return this;
        }

        /** One declared field of this action. A field the action did not declare is a programming error. */
        public Builder detail(String field, @Nullable Object value) {
            if (!action.fields().contains(field))
                throw new IllegalArgumentException(action.value() + " does not declare the detail " + field);
            details.put(field, value);
            return this;
        }

        public AuditRecord build() {
            return new AuditRecord(action, outcome, tenant, actor, actorLabel, resourceType, resourceId, resourceLabel,
                    details);
        }

        private static @Nullable String cut(@Nullable String value, int max) {
            if (value == null) return null;
            String trimmed = value.strip();
            if (trimmed.isEmpty()) return null;
            return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
        }
    }
}
