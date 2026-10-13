package io.memoryos.api.chat.contract;

import io.memoryos.chat.ChatMemory;
import io.swagger.v3.oas.annotations.media.Schema;

/** What Chat remembers about the current member (MEM-250). */
@Schema(name = "ChatMemory")
public record ChatMemoryResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The memory document; empty when nothing is remembered") String content,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Whether Chat reads and writes the memory") boolean enabled,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Send it back when saving the text, so a newer change is not overwritten") long revision,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The longest memory accepted, in characters") int maxLength
) {
    public static ChatMemoryResponse from(ChatMemory memory) {
        return new ChatMemoryResponse(memory.content(), memory.enabled(), memory.revision(), ChatMemory.MAX_CONTENT);
    }
}
