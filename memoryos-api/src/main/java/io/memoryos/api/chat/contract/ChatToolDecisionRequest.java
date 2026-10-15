package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;

/** The person's answer to a tool call that waits for approval. */
@Schema(name = "ChatToolDecision")
public record ChatToolDecisionRequest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean approved) {}
