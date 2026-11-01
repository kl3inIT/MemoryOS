package io.memoryos.api.source.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(name = "RenameLarkCredentialRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record RenameLarkCredentialRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank @Size(max = 120) String name) {
}
