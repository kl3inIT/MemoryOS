package io.memoryos.api.chat.contract;

import io.memoryos.chat.ChatActivity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/** The reply's apps the person must connect first, sent before the model runs (MEM-273). */
public record McpUnavailableEvent(@Schema(requiredMode = REQUIRED) UUID assistantMessageId,
                                  @Schema(requiredMode = REQUIRED) long sequence,
                                  @Schema(requiredMode = REQUIRED) List<ChatActivity.UnavailableApp> apps) {}
