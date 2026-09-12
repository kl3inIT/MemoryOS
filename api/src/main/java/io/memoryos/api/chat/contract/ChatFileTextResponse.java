package io.memoryos.api.chat.contract;

import io.memoryos.chat.ChatFileService;

public record ChatFileTextResponse(String text, int offset, int nextOffset, int totalCharacters) {
    public static ChatFileTextResponse from(ChatFileService.FileText text) {
        return new ChatFileTextResponse(text.text(), text.offset(), text.offset() + text.text().codePointCount(0, text.text().length()), text.totalCharacters());
    }
}
