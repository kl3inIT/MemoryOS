package io.memoryos.api.chat.contract;

import io.memoryos.chat.ChatSessionMatch;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

@Schema(name = "ChatSessionSearchPage")
public record ChatSessionSearchResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Item> items,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean hasMore) {

    @Schema(name = "ChatSessionSearchItem")
    public record Item(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ChatSessionResponse session,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"},
                    description = "Fragment of the newest matching message; U+E000/U+E001 wrap matched tokens. Null for recent sessions and title-only matches.")
            @Nullable String snippet) {
        public static Item from(ChatSessionMatch match) {
            return new Item(ChatSessionResponse.from(match.session()), match.snippet());
        }
    }
}
