package io.memoryos.api.users.contract;

import io.memoryos.iam.UserCounts;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "UserCounts", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record UserCountsResponse(
        @Schema(minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED)
        long active,
        @Schema(minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED)
        long inactive,
        @Schema(minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED)
        long invited
) {

    public static UserCountsResponse from(UserCounts counts) {
        return new UserCountsResponse(counts.active(), counts.inactive(), counts.invited());
    }
}
