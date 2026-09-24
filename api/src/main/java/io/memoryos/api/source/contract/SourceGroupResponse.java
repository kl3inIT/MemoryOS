package io.memoryos.api.source.contract;

import io.memoryos.iam.GroupIdentity;
import io.memoryos.iam.GroupSystemKey;

import java.util.UUID;

import org.jspecify.annotations.Nullable;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "SourceGroup", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record SourceGroupResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, enumAsRef = true)
        @Nullable GroupSystemKey systemKey
) {
    public static SourceGroupResponse from(GroupIdentity group) {
        return new SourceGroupResponse(
                group.id().value(),
                group.name(),
                group.systemKey()
        );
    }
}
