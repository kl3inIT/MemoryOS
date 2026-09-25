package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

@Schema(name = "BranchChatSessionRequest")
public record BranchChatSessionRequest(
        @Schema(description = "What to call the branch; the server names it after its origin when absent")
        @Size(max = 200) @Nullable String title) {}
