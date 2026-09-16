package io.memoryos.api.source.contract;

import io.memoryos.connector.SharePointSourceService.Configuration;
import io.memoryos.connector.SharePointSourceService.ScopeMode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(name = "SharePointConfigurationResponse", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record SharePointConfigurationResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID sourceId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID credentialId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String credentialName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String credentialStatus,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long credentialRevision,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long scopeRevision,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ScopeMode scopeMode,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long rootCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> excludedSites,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> excludedPaths,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean includeDocuments,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean includePages,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int syncIntervalMinutes,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int pruneIntervalHours,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long scheduleRevision,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean syncPaused,
        @Nullable String tenantHost,
        @Nullable Instant lastSyncedAt,
        @Nullable Instant lastPrunedAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean pendingWork,
        @Nullable String errorCode,
        @Nullable SourceOperationResponse pendingSelectionOperation) {

    public static SharePointConfigurationResponse from(Configuration value) {
        return new SharePointConfigurationResponse(value.sourceId().value(), value.credentialId().value(),
                value.credentialName(), value.credentialStatus(), value.credentialRevision(), value.scopeRevision(),
                value.scopeMode(), value.rootCount(), value.excludedSites(), value.excludedPaths(),
                value.includeDocuments(), value.includePages(), value.syncIntervalMinutes(),
                value.pruneIntervalHours(), value.scheduleRevision(), value.syncPaused(), value.tenantHost(),
                value.lastSyncedAt(), value.lastPrunedAt(), value.pendingWork(), value.errorCode(),
                value.pendingSelectionOperation() == null
                        ? null : SourceOperationResponse.from(value.pendingSelectionOperation()));
    }
}
