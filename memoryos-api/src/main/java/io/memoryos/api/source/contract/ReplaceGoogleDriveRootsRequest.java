package io.memoryos.api.source.contract;

import io.memoryos.connector.GoogleDriveSourceService.ScopeMode;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

@Schema(name = "ReplaceGoogleDriveRootsRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record ReplaceGoogleDriveRootsRequest(
        @NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID requestId,
        @NotNull @PositiveOrZero @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Long discoveryRevision,
        @NotNull @Positive @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Long credentialRevision,
        @NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "GENERAL includes the connected account's My Drive tree; SPECIFIC includes selected links.") ScopeMode scopeMode,
        @NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Empty for GENERAL; distinct non-overlapping links bounded by the selection policy for SPECIFIC.")
        List<@NotBlank @Size(max = 2048) String> links,
        @NotNull @Size(max = 500) @ArraySchema(maxItems = 500, uniqueItems = true,
                arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                        description = "Distinct discovered document IDs explicitly approved for ingestion. Empty for GENERAL."))
        List<@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,256}") String> linkedDocumentIds) {}
