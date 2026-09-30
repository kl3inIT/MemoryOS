package io.memoryos.chat;

import io.memoryos.mcp.McpTurnService;
import java.util.Objects;
import java.util.UUID;
import java.util.List;
import java.util.HashSet;
import org.jspecify.annotations.Nullable;

/** Identity includes the operation, target, Web intent and Deep research mode; retries never append another reply. */
public record ChatCommand(Operation operation, UUID targetMessageId, UUID requestId,
                          String text, @Nullable UUID modelConfigurationId, List<UUID> fileIds, WebSearchMode webSearch, ImageMode image,
                          boolean deepResearch, List<UUID> mcpServerIds) {
    public enum Operation { SEND, EDIT, REGENERATE }
    public ChatCommand {
        webSearch = webSearch == null ? WebSearchMode.off : webSearch;
        image = image == null ? ImageMode.off : image;
        if (mcpServerIds != null && mcpServerIds.stream().anyMatch(Objects::isNull))
            throw ChatException.invalid("Invalid MCP server identity.");
        mcpServerIds = mcpServerIds == null ? List.of() : List.copyOf(mcpServerIds);
        if (mcpServerIds.size() > McpTurnService.MAX_SERVERS
                || new HashSet<>(mcpServerIds).size() != mcpServerIds.size())
            throw ChatException.invalid("Invalid MCP server selection.");
        if (fileIds != null && fileIds.stream().anyMatch(Objects::isNull))
            throw ChatException.invalid("Invalid file identity.");
        fileIds = fileIds == null ? List.of() : List.copyOf(fileIds);
        if (operation == null || targetMessageId == null || requestId == null || text == null
                || fileIds.size() > 20 || new HashSet<>(fileIds).size() != fileIds.size()
                || text.length() > 32000 || (operation != Operation.REGENERATE && text.isBlank() && fileIds.isEmpty())
                || (operation == Operation.REGENERATE && (!text.isEmpty() || !fileIds.isEmpty())))
            throw ChatException.invalid("Invalid chat command.");
    }

    /** A command on the default model with no files, Web search, image generation, Deep research or MCP servers. */
    public static Builder builder(Operation operation, UUID targetMessageId, UUID requestId, String text) {
        return new Builder(operation, targetMessageId, requestId, text);
    }

    public static final class Builder {
        private final Operation operation;
        private final UUID targetMessageId;
        private final UUID requestId;
        private final String text;
        private @Nullable UUID modelConfigurationId;
        private List<UUID> fileIds = List.of();
        private WebSearchMode webSearch = WebSearchMode.off;
        private ImageMode image = ImageMode.off;
        private boolean deepResearch;
        private List<UUID> mcpServerIds = List.of();

        private Builder(Operation operation, UUID targetMessageId, UUID requestId, String text) {
            this.operation = operation;
            this.targetMessageId = targetMessageId;
            this.requestId = requestId;
            this.text = text;
        }

        public Builder modelConfigurationId(@Nullable UUID value) { modelConfigurationId = value; return this; }
        public Builder fileIds(List<UUID> value) { fileIds = value; return this; }
        public Builder webSearch(WebSearchMode value) { webSearch = value; return this; }
        public Builder image(ImageMode value) { image = value; return this; }
        public Builder deepResearch(boolean value) { deepResearch = value; return this; }
        public Builder mcpServerIds(List<UUID> value) { mcpServerIds = value; return this; }

        public ChatCommand build() {
            return new ChatCommand(operation, targetMessageId, requestId, text, modelConfigurationId, fileIds, webSearch,
                    image, deepResearch, mcpServerIds);
        }
    }
}
