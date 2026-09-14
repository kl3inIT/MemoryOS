package io.memoryos.chat;

import java.util.UUID;
import java.util.List;
import java.util.HashSet;
import org.jspecify.annotations.Nullable;

/** Identity includes the operation and target; retries never append another reply. */
public record ChatCommand(Operation operation, UUID targetMessageId, UUID requestId,
                          String text, @Nullable UUID modelConfigurationId, List<UUID> fileIds, WebSearchMode webSearch, ImageMode image) {
    public ChatCommand(Operation operation, UUID targetMessageId, UUID requestId, String text, @Nullable UUID modelConfigurationId, List<UUID> fileIds, WebSearchMode webSearch) {
        this(operation, targetMessageId, requestId, text, modelConfigurationId, fileIds, webSearch, ImageMode.off);
    }
    public ChatCommand(Operation operation, UUID targetMessageId, UUID requestId, String text, @Nullable UUID modelConfigurationId, List<UUID> fileIds) {
        this(operation, targetMessageId, requestId, text, modelConfigurationId, fileIds, WebSearchMode.off);
    }
    public ChatCommand(Operation operation, UUID targetMessageId, UUID requestId, String text, @Nullable UUID modelConfigurationId) {
        this(operation, targetMessageId, requestId, text, modelConfigurationId, List.of());
    }
    public enum Operation { SEND, EDIT, REGENERATE }
    public ChatCommand {
        webSearch = webSearch == null ? WebSearchMode.off : webSearch;
        image = image == null ? ImageMode.off : image;
        if (fileIds != null && fileIds.stream().anyMatch(java.util.Objects::isNull))
            throw ChatException.invalid("Invalid file identity.");
        fileIds = fileIds == null ? List.of() : List.copyOf(fileIds);
        if (operation == null || targetMessageId == null || requestId == null || text == null
                || fileIds.size() > 20 || new HashSet<>(fileIds).size() != fileIds.size()
                || text.length() > 32000 || (operation != Operation.REGENERATE && text.isBlank() && fileIds.isEmpty())
                || (operation == Operation.REGENERATE && (!text.isEmpty() || !fileIds.isEmpty())))
            throw ChatException.invalid("Invalid chat command.");
    }
}
