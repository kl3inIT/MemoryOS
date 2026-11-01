package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

@Schema(name = "ChatRetentionPreview")
public record ChatRetentionPreviewResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"integer", "null"}) @Nullable Integer days,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "How many of your conversations this policy would delete now") long affected) {}
