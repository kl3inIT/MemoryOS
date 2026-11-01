package io.memoryos.api.source.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(name = "RenameSharePointCredentialRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record RenameSharePointCredentialRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank @Size(max = 120) String name) {
}
