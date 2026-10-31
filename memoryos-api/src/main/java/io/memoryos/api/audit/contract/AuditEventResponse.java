package io.memoryos.api.audit.contract;

import io.memoryos.audit.AuditEventClass;
import io.memoryos.audit.AuditLog;
import io.memoryos.audit.AuditOutcome;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(name = "AuditEvent", description = "One recorded change: who, what and to which resource, with the declared details of its action")
public record AuditEventResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
                                 @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant occurredAt,
                                 @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "An append-only action value such as user.deactivate")
                                 String action,
                                 @Schema(requiredMode = Schema.RequiredMode.REQUIRED) AuditEventClass eventClass,
                                 @Schema(requiredMode = Schema.RequiredMode.REQUIRED) AuditOutcome outcome,
                                 @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable UUID actorId,
                                 @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String actorLabel,
                                 @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String actorEmail,
                                 @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String resourceType,
                                 @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String resourceId,
                                 @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String resourceLabel,
                                 @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
                                 Map<String, Object> details,
                                 @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String traceId,
                                 @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String endpoint,
                                 @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String sourceIp) {
    public static AuditEventResponse from(AuditLog.Event event) {
        return new AuditEventResponse(event.id(), event.occurredAt(), event.action(), event.eventClass(), event.outcome(),
                event.actorId() == null ? null : event.actorId().value(), event.actorLabel(), event.actorEmail(), event.resourceType(), event.resourceId(),
                event.resourceLabel(), event.details(), event.traceId(), event.endpoint(), event.sourceIp());
    }
}
