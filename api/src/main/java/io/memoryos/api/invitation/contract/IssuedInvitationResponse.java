package io.memoryos.api.invitation.contract;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "IssuedInvitation", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record IssuedInvitationResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        InvitationResponse invitation,
        @Schema(
                pattern = "^/invite/",
                description = "Relative same-origin capability URL returned only from create or rotate.",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        String invitationUrl,
        @Schema(
                description = "Observable delivery result for this issue or rotation operation.",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        InvitationDeliveryResponse delivery
) {
}
