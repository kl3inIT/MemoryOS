package io.memoryos.api.identity.contract;

import io.memoryos.iam.PrincipalMatches;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(name = "PrincipalOptions", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record PrincipalOptionsResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<PrincipalPersonResponse> people,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<PrincipalGroupResponse> groups
) {
    public static PrincipalOptionsResponse from(PrincipalMatches matches) {
        return new PrincipalOptionsResponse(
                matches.people().stream().map(PrincipalPersonResponse::from).toList(),
                matches.groups().stream().map(PrincipalGroupResponse::from).toList());
    }
}
