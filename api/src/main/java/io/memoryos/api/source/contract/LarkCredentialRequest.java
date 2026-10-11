package io.memoryos.api.source.contract;

import io.memoryos.connector.LarkGateway;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.NonNull;

/** Creates a credential or replaces its App Secret. The secret is write-only: no response ever returns it. */
@Schema(name = "LarkCredentialRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record LarkCredentialRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank @Size(max = 120) String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Lark international or Feishu")
        @NotNull LarkGateway.Region region,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "App ID of the custom app, cli_…")
        @NotBlank @Size(max = 64) String appId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "App Secret of the custom app")
        @NotBlank @Size(max = 256) String appSecret) {

    @Override public @NonNull String toString() { return "LarkCredentialRequest[redacted]"; }
}
