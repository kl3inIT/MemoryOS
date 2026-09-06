package io.memoryos.api.users.contract;

import io.memoryos.iam.GroupIdentity;
import io.memoryos.iam.GroupSystemKey;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

@Schema(name = "UserGroup", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record UserGroupResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, enumAsRef = true)
        @Nullable GroupSystemKey systemKey
) {

    public static UserGroupResponse from(GroupIdentity group) {
        return new UserGroupResponse(group.id().value(), group.name(), group.systemKey());
    }
}
