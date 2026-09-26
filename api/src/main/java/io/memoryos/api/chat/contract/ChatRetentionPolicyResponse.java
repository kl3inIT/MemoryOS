package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

@Schema(name = "ChatRetentionPolicy")
public record ChatRetentionPolicyResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"integer", "null"},
        description = "Days of inactivity after which one of your conversations is deleted; null keeps them until you delete them")
        @Nullable Integer days) {}
