package io.memoryos.api.identity.contract;

import io.memoryos.iam.PrincipalGroup;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(name = "PrincipalGroup", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record PrincipalGroupResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name
) {
    public static PrincipalGroupResponse from(PrincipalGroup group) {
        return new PrincipalGroupResponse(group.id().value(), group.name());
    }
}
