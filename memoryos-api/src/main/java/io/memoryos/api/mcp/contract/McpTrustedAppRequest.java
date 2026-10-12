package io.memoryos.api.mcp.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

@Schema(description = "An app of the Tenant's own, admitted by the URL of its client metadata document")
public record McpTrustedAppRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank @Size(max = 80) String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Domains of the document's URL, 1 to 10")
        @NotNull @Size(min = 1, max = 10) List<@NotBlank @Size(max = 253) String> clientIdHosts,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Other domains the document lists, such as its callback or logo; may be empty")
        @NotNull @Size(max = 20) List<@NotBlank @Size(max = 253) String> documentHosts) {
}
