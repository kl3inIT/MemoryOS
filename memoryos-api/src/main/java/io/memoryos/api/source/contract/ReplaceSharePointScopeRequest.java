package io.memoryos.api.source.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

@Schema(name = "ReplaceSharePointScopeRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record ReplaceSharePointScopeRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotNull UUID requestId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Credential revision the scope was reviewed against")
        @Positive long expectedCredentialRevision,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotNull @Valid SharePointScopeRequest scope) {
}
