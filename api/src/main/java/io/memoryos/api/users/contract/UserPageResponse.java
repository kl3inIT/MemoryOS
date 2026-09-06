package io.memoryos.api.users.contract;

import io.memoryos.iam.UserPage;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "UserPage", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record UserPageResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        List<UserListItemResponse> items,
        @Schema(minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED)
        int page,
        @Schema(minimum = "1", maximum = "100", requiredMode = Schema.RequiredMode.REQUIRED)
        int size,
        @Schema(minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED)
        long totalItems,
        @Schema(minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED)
        long totalPages,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        UserCountsResponse counts
) {

    public static UserPageResponse from(UserPage page) {
        return new UserPageResponse(
                page.items().stream().map(UserListItemResponse::from).toList(),
                page.page(),
                page.size(),
                page.totalItems(),
                page.totalPages(),
                UserCountsResponse.from(page.counts())
        );
    }
}
