package io.memoryos.api.source.contract;

import io.memoryos.connector.GoogleDriveSourceService.ScopeMode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

@Schema(name = "CreateGoogleDriveSourceRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record CreateGoogleDriveSourceRequest(
        @NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID requestId,
        @NotBlank @Size(max = 120) @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID credentialId,
        @NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "GENERAL includes the connected account's My Drive tree; SPECIFIC includes selected links.") ScopeMode scopeMode,
        @NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Empty for GENERAL; distinct non-overlapping links bounded by the selection policy for SPECIFIC.")
        List<@NotBlank @Size(max = 2048) String> links) {}
