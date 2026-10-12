package io.memoryos.api.invitation.contract;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "InvitationPage", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record InvitationPageResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        List<InvitationResponse> items,
        @Schema(minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED)
        int page,
        @Schema(minimum = "1", maximum = "100", requiredMode = Schema.RequiredMode.REQUIRED)
        int size,
        @Schema(minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED)
        long totalItems,
        @Schema(minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED)
        long totalPages
) {
}
