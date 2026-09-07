package io.memoryos.api.groups.contract;

import io.memoryos.iam.IamCapability;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

@Schema(name = "ReplaceGroupCapabilitiesRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record ReplaceGroupCapabilitiesRequest(
        @NotNull(message = "Capabilities are required.")
        @Size(max = 7, message = "Capabilities exceed the server registry.")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 7)
        List<@NotNull IamCapability> capabilities
) {
    public ReplaceGroupCapabilitiesRequest {
        capabilities = capabilities == null ? null : List.copyOf(capabilities);
    }
}
