package io.memoryos.api.groups.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(name = "RenameGroupRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record RenameGroupRequest(
        @NotBlank(message = "Enter a group name.")
        @Size(max = 200, message = "Group name must not exceed 200 characters.")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 200)
        String name
) {
}
