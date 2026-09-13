package io.memoryos.api.source.contract;

import io.memoryos.connector.GoogleDriveAclService;
import io.memoryos.connector.GoogleDriveAclSnapshot;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

@Schema(name = "GoogleDriveAclPage", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record GoogleDriveAclPageResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Item> items,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String nextCursor,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long totalItems) {
    public static GoogleDriveAclPageResponse from(GoogleDriveAclService.Page page) {
        return new GoogleDriveAclPageResponse(page.items().stream().map(Item::from).toList(), page.nextCursor(), page.totalItems());
    }

    @Schema(name = "GoogleDriveAclItem", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Item(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String fileId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) GoogleDriveAclSnapshot.@Nullable Status status,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) GoogleDriveAclSnapshot.@Nullable ContextStatus contextStatus,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable Long revision,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable Integer permissionCount,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable Instant lastSuccessAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable Instant lastAttemptAt) {
        static Item from(GoogleDriveAclService.Item item) {
            return new Item(item.fileId(), item.name(), item.status(), item.contextStatus(), item.revision(),
                    item.permissionCount(), item.lastSuccessAt(), item.lastAttemptAt());
        }
    }
}
