package io.memoryos.api.chat.contract;

import io.memoryos.chat.ChatToolEvent;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

public record ToolEvent(@Schema(requiredMode = REQUIRED) UUID assistantMessageId,
                        @Schema(requiredMode = REQUIRED) long sequence,
                        @Schema(requiredMode = REQUIRED) String toolCallId,
                        @Schema(requiredMode = REQUIRED) String toolName,
                        @Schema(requiredMode = REQUIRED) ChatToolEvent.Stage stage,
                        @Schema(requiredMode = REQUIRED, types = {"object", "null"}) @Nullable ChatSourceResponse source,
                        @Schema(requiredMode = REQUIRED, types = {"object", "null"}) ChatToolEvent.@Nullable QueryPlan search,
                        @Schema(requiredMode = REQUIRED) List<ChatToolEvent.ReadingDocument> documents,
                        @Schema(requiredMode = REQUIRED, types = {"integer", "null"}, format = "int64") @Nullable Long durationMs,
                        @Schema(requiredMode = REQUIRED, types = {"string", "null"}) @Nullable String parentToolCallId,
                        @Schema(requiredMode = REQUIRED, types = {"integer", "null"}, format = "int32") @Nullable Integer tabIndex,
                        @Schema(requiredMode = REQUIRED, types = {"string", "null"}, allowableValues = {"AUTHORIZATION_REQUIRED", "TIMEOUT", "UNAVAILABLE"},
                                description = "Why a FAILED step failed when the person can act on it; a category only.") ChatToolEvent.@Nullable Failure failure) {}
