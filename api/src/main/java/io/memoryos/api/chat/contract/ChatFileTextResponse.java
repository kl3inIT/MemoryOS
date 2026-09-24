package io.memoryos.api.chat.contract;

import io.memoryos.library.UserFileService;

public record ChatFileTextResponse(String text, int offset, int nextOffset, int totalCharacters) {
    public static ChatFileTextResponse from(UserFileService.FileText text) {
        return new ChatFileTextResponse(text.text(), text.offset(), text.offset() + text.text().codePointCount(0, text.text().length()), text.totalCharacters());
    }
}
