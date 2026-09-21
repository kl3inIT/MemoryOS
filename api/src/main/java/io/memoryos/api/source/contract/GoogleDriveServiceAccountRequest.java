package io.memoryos.api.source.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(name = "GoogleDriveServiceAccountRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record GoogleDriveServiceAccountRequest(
        @NotBlank @Size(max = 120) @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @NotBlank @Size(max = 16384) @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "The JSON key downloaded for the service account") String serviceAccountKeyJson,
        @NotBlank @Size(max = 320) @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "A Google Workspace administrator the service account acts as") String adminEmail) {
    @Override public String toString() { return "GoogleDriveServiceAccountRequest[redacted]"; }
}
