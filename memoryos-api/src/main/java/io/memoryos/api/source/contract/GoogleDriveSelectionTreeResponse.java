package io.memoryos.api.source.contract;

import io.memoryos.api.source.contract.GoogleDriveConfigurationResponse.GoogleDriveLinkOriginResponse;
import io.memoryos.api.source.contract.GoogleDriveSelectionResponse.Counts;
import io.memoryos.connector.GoogleDriveSourceService.*;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

@Schema(name = "GoogleDriveSelectionTreeResponse", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record GoogleDriveSelectionTreeResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long revision,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long discoveryRevision,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long credentialRevision,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Item> items,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String nextCursor,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Counts counts) {
    public static GoogleDriveSelectionTreeResponse from(SelectionTreePage value) {
        return new GoogleDriveSelectionTreeResponse(value.revision(), value.discoveryRevision(), value.credentialRevision(),
                value.items().stream().map(item -> new Item(item.id(), item.name(), item.mimeType(), item.kind(), item.selected(),
                        item.coveredByRoots(), item.status(), item.origins().stream().map(origin -> new GoogleDriveLinkOriginResponse(
                                origin.rootId(), origin.parentId(), origin.parentName(), origin.location())).toList(), item.expandable())).toList(),
                value.nextCursor(), Counts.from(value.counts()));
    }

    @Schema(name = "GoogleDriveSelectionTreeItemResponse", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Item(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String mimeType,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) SelectionKind kind,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean selected,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean coveredByRoots,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LinkedDocumentStatus status,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<GoogleDriveLinkOriginResponse> origins,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean expandable) {}
}
