package io.memoryos.api.invitation.contract;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "CreateInvitationRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record CreateInvitationRequest(
        @Schema(
                format = "email",
                maxLength = 254,
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        String email
) {
}
