package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.jspecify.annotations.Nullable;

@Schema(name = "ChatGuardrailsRequest")
public record ChatGuardrailsRequest(
        @NotNull @Size(max = 10) List<@NotNull @Valid ChatGuardrailTopic> topics,
        @Schema(description = "Exact phrases no question or answer may contain; at most 20 of at most 100 characters")
        @NotNull @Size(max = 20) List<@NotNull @Size(max = 100) String> blockedPhrases,
        @Schema(description = "What the person is told when a blocked phrase matches") @Size(max = 500) @Nullable String blockedPhraseMessage,
        @Min(0) long revision) {}
