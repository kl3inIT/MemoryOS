package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/** An intermediate report citation number and the merged turn source it refers to. */
public record ResearchCitation(@Schema(requiredMode = REQUIRED) int marker, @Schema(requiredMode = REQUIRED) int citationId) {}
