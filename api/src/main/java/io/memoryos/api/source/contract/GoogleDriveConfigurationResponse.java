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
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Selected roots for SPECIFIC; empty for GENERAL, whose My Drive root is server-managed.")
        List<GoogleDriveRootResponse> roots,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable Instant lastSyncedAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean pendingWork,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String errorCode) {
    public static GoogleDriveConfigurationResponse from(Configuration configuration) {
        return new GoogleDriveConfigurationResponse(configuration.sourceId().value(), configuration.credentialId().value(), configuration.accountEmail(),
                configuration.credentialStatus(), configuration.credentialRevision(), configuration.oauthClientConfigured(), configuration.revision(),
                configuration.syncIntervalMinutes(), configuration.scheduleRevision(), configuration.scopeMode(),
                configuration.roots().stream().map(root -> new GoogleDriveRootResponse(root.id(), root.name(), root.mimeType())).toList(),
                configuration.lastSyncedAt(), configuration.pendingWork(), configuration.errorCode());
    }
    @Schema(name = "GoogleDriveRootResponse", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record GoogleDriveRootResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String mimeType) {}
}
