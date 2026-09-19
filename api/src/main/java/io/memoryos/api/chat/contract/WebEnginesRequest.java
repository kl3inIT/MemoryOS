package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/** The endpoint being configured and, optionally, the key typed in the form; a blank key uses the saved one. */
public record WebEnginesRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank @Size(max = 2048) String endpoint,
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, types = {"string", "null"}) @Size(max = 8192) @Nullable String key) {
    @Override public String toString() { return "WebEnginesRequest[redacted]"; }
}
