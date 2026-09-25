package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

public record CodeEvent(@Schema(requiredMode = REQUIRED) UUID assistantMessageId,
                        @Schema(requiredMode = REQUIRED) long sequence,
                        @Schema(requiredMode = REQUIRED) String toolCallId,
                        @Schema(requiredMode = REQUIRED) io.memoryos.chat.ChatCodeEvent.Stage stage,
                        @Schema(requiredMode = REQUIRED, types = {"string", "null"}) @Nullable String code,
                        @Schema(requiredMode = REQUIRED, types = {"string", "null"}) @Nullable String output,
                        @Schema(requiredMode = REQUIRED) List<io.memoryos.chat.ChatCodeEvent.GeneratedFile> files,
                        @Schema(requiredMode = REQUIRED, types = {"string", "null"}, allowableValues = {"stdout", "stderr"}) @Nullable String stream) {}
