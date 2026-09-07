package io.memoryos.api.source.contract;

import io.memoryos.connector.GoogleDriveSourceService.ScopeMode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

@Schema(name = "ReplaceGoogleDriveRootsRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record ReplaceGoogleDriveRootsRequest(
        @NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "GENERAL includes the connected account's My Drive tree; SPECIFIC includes selected links.") ScopeMode scopeMode,
        @NotNull @Size(max = 20) @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Empty for GENERAL; 1–20 distinct, non-overlapping file or folder links for SPECIFIC.")
        List<@NotBlank @Size(max = 2048) String> links) {}
