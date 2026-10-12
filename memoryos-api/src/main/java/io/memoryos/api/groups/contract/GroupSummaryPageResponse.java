package io.memoryos.api.groups.contract;

import io.memoryos.iam.GroupPage;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "GroupPage", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record GroupSummaryPageResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        List<GroupSummaryResponse> items,
        @Schema(minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED)
        int page,
        @Schema(minimum = "1", maximum = "100", requiredMode = Schema.RequiredMode.REQUIRED)
        int size,
        @Schema(minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED)
        long totalItems,
        @Schema(minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED)
        long totalPages
) {
    public GroupSummaryPageResponse {
        items = List.copyOf(items);
    }

    public static GroupSummaryPageResponse from(GroupPage page) {
        return new GroupSummaryPageResponse(
                page.items().stream().map(GroupSummaryResponse::from).toList(),
                page.page(),
                page.size(),
                page.totalItems(),
                page.totalPages()
        );
    }
}
