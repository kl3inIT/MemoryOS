package io.memoryos.api.source.contract;

import io.memoryos.connector.LarkSourceService.RootKind;
import io.memoryos.connector.LarkSourceService.RootView;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/** A root in scope: {@code token} is the Wiki space ID or the Drive folder token. */
@Schema(name = "LarkRootResponse", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record LarkRootResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) RootKind kind,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String token,
        @Schema(description = "The name Lark gave the space or the folder when it was verified") @Nullable String displayName) {

    public static LarkRootResponse from(RootView value) {
        return new LarkRootResponse(value.kind(), value.token(), value.displayName());
    }
}
