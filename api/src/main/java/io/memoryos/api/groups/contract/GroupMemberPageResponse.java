package io.memoryos.api.groups.contract;

import io.memoryos.iam.GroupMemberPage;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "GroupMemberPage", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record GroupMemberPageResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        List<GroupMemberResponse> items,
        @Schema(minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED)
        int page,
        @Schema(minimum = "1", maximum = "100", requiredMode = Schema.RequiredMode.REQUIRED)
        int size,
        @Schema(minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED)
        long totalItems,
        @Schema(minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED)
        long totalPages
) {
    public GroupMemberPageResponse {
        items = List.copyOf(items);
    }

    public static GroupMemberPageResponse from(GroupMemberPage page) {
        return new GroupMemberPageResponse(
                page.items().stream().map(GroupMemberResponse::from).toList(),
                page.page(),
                page.size(),
                page.totalItems(),
                page.totalPages()
        );
    }
}
