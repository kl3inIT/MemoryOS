package io.memoryos.api.source.contract;

import io.memoryos.connector.SourceAccess;
import io.memoryos.connector.SourcePermissions;
import io.memoryos.connector.SourceSummary;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "SourceSummary", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record SourceSummaryResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String type,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        SourceAccess access,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String status,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        boolean pendingWork,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        long documentCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        @Nullable Instant lastSucceededAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        @Nullable String errorCode,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                description = "Actor who may attach this source to the groups they manage.")
        @Nullable UUID managerActorId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                description = "Profile name of the responsible manager.")
        @Nullable String managerName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        Permissions permissions
) {
    /** Affordance hints projected from the Source write guards; mutations keep their own checks. */
    @Schema(name = "SourcePermissions", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Permissions(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean edit,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean delete,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean publish,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean manageConfiguration,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean removeItems
    ) {
        static Permissions from(SourcePermissions permissions) {
            return new Permissions(permissions.edit(), permissions.delete(), permissions.publish(),
                    permissions.manageConfiguration(), permissions.removeItems());
        }
    }

    public static SourceSummaryResponse from(SourceSummary source) {
        return new SourceSummaryResponse(
                source.id().value(),
                source.name(),
                source.type().name(),
                source.access(),
                source.status().name(),
                source.pendingWork(),
                source.documentCount(),
                source.lastSucceededAt(),
                source.errorCode(),
                source.managerActorId() == null ? null : source.managerActorId().value(),
                source.managerName(),
                Permissions.from(source.permissions())
        );
    }
}
