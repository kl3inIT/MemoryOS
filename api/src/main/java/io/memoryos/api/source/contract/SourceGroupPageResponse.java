package io.memoryos.api.source.contract;

import io.memoryos.iam.GroupIdentityPage;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "SourceGroupPage", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record SourceGroupPageResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        List<SourceGroupResponse> items,
        @Schema(minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED)
        int page,
        @Schema(minimum = "1", maximum = "100", requiredMode = Schema.RequiredMode.REQUIRED)
        int size,
        @Schema(minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED)
        long totalItems,
        @Schema(minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED)
        long totalPages
) {
    public SourceGroupPageResponse {
        items = List.copyOf(items);
    }

    public static SourceGroupPageResponse from(GroupIdentityPage page) {
        return new SourceGroupPageResponse(
                page.items().stream().map(SourceGroupResponse::from).toList(),
                page.page(),
                page.size(),
                page.totalItems(),
                page.totalPages()
        );
    }
}
