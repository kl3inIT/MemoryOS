package io.memoryos.api.source.contract;

import io.memoryos.connector.SourceAccess;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(name = "CreateLarkSourceRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record CreateLarkSourceRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Identifies the request so a retry recovers its receipt")
        @NotNull UUID requestId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank @Size(max = 120) String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotNull UUID credentialId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotNull @Valid LarkScopeRequest scope,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotNull SourceAccess access,
        @Size(max = 100) @Nullable List<UUID> groupIds) {
}
