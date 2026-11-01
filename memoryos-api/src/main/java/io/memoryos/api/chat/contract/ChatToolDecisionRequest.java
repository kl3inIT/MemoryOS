package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/** The person's answer to a tool call that waits for approval. */
@Schema(name = "ChatToolDecision")
public record ChatToolDecisionRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean approved,
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true,
                description = "With approved, the tool also runs without asking from now on; needs MCP_MANAGE and a tool that is not destructive")
        @Nullable Boolean alwaysAllow) {}
