package io.memoryos.api.source.contract;

import io.memoryos.connector.GoogleDriveSourceService.*;
import io.memoryos.api.source.contract.GoogleDriveConfigurationResponse.GoogleDriveLinkOriginResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

@Schema(name="GoogleDriveSelectionResponse",additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
public record GoogleDriveSelectionResponse(
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) long revision,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) long discoveryRevision,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) long credentialRevision,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) List<Item> items,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED,nullable=true) @Nullable String nextCursor,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Counts counts) {
    public static GoogleDriveSelectionResponse from(SelectionPage value) {
        return new GoogleDriveSelectionResponse(value.revision(),value.discoveryRevision(),value.credentialRevision(),
                value.items().stream().map(item -> new Item(item.id(),item.name(),item.mimeType(),item.kind(),item.selected(),
                        item.coveredByRoots(),item.status(),item.origins().stream().map(origin -> new GoogleDriveLinkOriginResponse(
                                origin.rootId(),origin.parentId(),origin.parentName(),origin.location())).toList())).toList(),
                value.nextCursor(),Counts.from(value.counts()));
    }
    @Schema(name="GoogleDriveSelectionItemResponse",additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record Item(
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String id,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String name,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String mimeType,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) SelectionKind kind,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) boolean selected,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) boolean coveredByRoots,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) LinkedDocumentStatus status,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) List<GoogleDriveLinkOriginResponse> origins) {}
    @Schema(name="GoogleDriveSelectionCountsResponse",additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record Counts(
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) long folders,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) long files,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) long linkedDocuments,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) long approvedLinkedDocuments) {
        public static Counts from(SelectionCounts value) {
            return new Counts(value.folders(),value.files(),value.linkedDocuments(),value.approvedLinkedDocuments());
        }
    }
    @Schema(name="GoogleDriveSelectionDraftResponse",additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record Draft(
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) long revision,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) long discoveryRevision,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) long credentialRevision,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) List<String> links,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) List<String> linkedDocumentIds) {
        public static Draft from(SelectionDraft value) {
            return new Draft(value.revision(),value.discoveryRevision(),value.credentialRevision(),value.links(),value.linkedDocumentIds());
        }
    }
    @Schema(name="GoogleDriveSelectionPolicyResponse",additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record Policy(
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) int maxExplicitRootsPerSource,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) int maxRequestBytes,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) int maxLinkedDocuments) {
        public static Policy from(SelectionPolicy value) {
            return new Policy(value.maxExplicitRootsPerSource(),value.maxRequestBytes(),value.maxLinkedDocuments());
        }
    }
}
