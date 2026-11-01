package io.memoryos.api.channel.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/** The administrator's own name for a group chat; blank or null clears it. */
@Schema(name = "LabelChannelGroupRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record LabelChannelGroupRequest(
        @Schema(types = {"string", "null"}, maxLength = 100, description = "The name; blank or null clears it")
        @Size(max = 100) @Nullable String label) {}
