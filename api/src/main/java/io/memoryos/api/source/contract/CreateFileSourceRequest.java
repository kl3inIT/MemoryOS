package io.memoryos.api.source.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(name = "CreateFileSourceRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record CreateFileSourceRequest(
        @NotBlank(message = "Enter a source name.")
        @Size(max = 120, message = "Source name must not exceed 120 characters.")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 120)
        String name
) {
}
