package io.memoryos.api.source.contract;

import io.memoryos.connector.SourceAccess;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(name = "UpdateSourceAccessRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record UpdateSourceAccessRequest(
        @NotNull
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        SourceAccess access
) {}
