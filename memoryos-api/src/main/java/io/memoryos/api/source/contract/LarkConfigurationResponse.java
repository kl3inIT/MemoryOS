package io.memoryos.api.source.contract;

import io.memoryos.connector.LarkSourceService.Configuration;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(name = "LarkConfigurationResponse", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record LarkConfigurationResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID sourceId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID credentialId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String credentialName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String credentialStatus,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long credentialRevision,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long scopeRevision,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long rootCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int syncIntervalMinutes,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long scheduleRevision,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean syncPaused,
        @Nullable Instant lastSyncedAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean pendingWork,
        @Nullable String errorCode,
        @Nullable SourceOperationResponse pendingSelectionOperation) {

    public static LarkConfigurationResponse from(Configuration value) {
        return new LarkConfigurationResponse(value.sourceId().value(), value.credentialId().value(),
                value.credentialName(), value.credentialStatus(), value.credentialRevision(), value.scopeRevision(),
                value.rootCount(), value.syncIntervalMinutes(), value.scheduleRevision(), value.syncPaused(),
                value.lastSyncedAt(), value.pendingWork(), value.errorCode(),
                value.pendingSelectionOperation() == null
                        ? null : SourceOperationResponse.from(value.pendingSelectionOperation()));
    }
}
