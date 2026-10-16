package io.memoryos.api.source.contract;

import io.memoryos.connector.LarkSourceService.Root;
import io.memoryos.connector.LarkSourceService.RootKind;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** One thing to read: a Wiki space by its ID, or a Drive folder by its link or token. */
@Schema(name = "LarkRootRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record LarkRootRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotNull RootKind kind,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "A Wiki space ID, or the link or token of a Drive folder")
        @NotBlank @Size(max = 2048) String value) {

    public Root toRoot() {
        return new Root(kind, value);
    }
}
