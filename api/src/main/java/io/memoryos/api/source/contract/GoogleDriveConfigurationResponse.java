package io.memoryos.api.source.contract;

import io.memoryos.connector.GoogleDriveSourceService.Configuration;
import io.memoryos.connector.GoogleDriveSourceService.ScopeMode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(name = "GoogleDriveConfigurationResponse", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record GoogleDriveConfigurationResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID sourceId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID credentialId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String accountEmail,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String credentialStatus,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long credentialRevision,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean oauthClientConfigured,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long revision,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "2147483647") int syncIntervalMinutes,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1") long scheduleRevision,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ScopeMode scopeMode,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) GoogleDriveSelectionResponse.Counts counts,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0") long discoveryRevision,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable Instant discoveredAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<GoogleDriveDiscoveryErrorResponse> discoveryErrors,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable Instant lastSyncedAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean pendingWork,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String errorCode,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable SourceOperationResponse pendingSelectionOperation) {
    public static GoogleDriveConfigurationResponse from(Configuration configuration) {
        return new GoogleDriveConfigurationResponse(configuration.sourceId().value(), configuration.credentialId().value(), configuration.accountEmail(),
                configuration.credentialStatus(), configuration.credentialRevision(), configuration.oauthClientConfigured(), configuration.revision(),
                configuration.syncIntervalMinutes(), configuration.scheduleRevision(), configuration.scopeMode(),
                GoogleDriveSelectionResponse.Counts.from(configuration.counts()),
                configuration.discoveryRevision(), configuration.discoveredAt(),
                configuration.discoveryErrors().stream().map(error ->
                        new GoogleDriveDiscoveryErrorResponse(error.fileId(), error.fileName(), error.code())).toList(),
                configuration.lastSyncedAt(), configuration.pendingWork(), configuration.errorCode(),
                configuration.pendingSelectionOperation() == null ? null : SourceOperationResponse.from(configuration.pendingSelectionOperation()));
    }

    @Schema(name = "GoogleDriveLinkOriginResponse", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record GoogleDriveLinkOriginResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String rootId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String parentId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String parentName,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String location) {}

    @Schema(name = "GoogleDriveDiscoveryErrorResponse", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record GoogleDriveDiscoveryErrorResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String fileId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String fileName,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String code) {}
}
