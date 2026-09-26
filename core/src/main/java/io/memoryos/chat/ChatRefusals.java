package io.memoryos.chat;

import org.jspecify.annotations.Nullable;

/**
 * The text a grounded turn stores and streams when it declines (MEM-195). The browser renders the refusal from the
 * stored reason; this text is what history, exports and shared transcripts read.
 */
final class ChatRefusals {
    private ChatRefusals() {}

    static String notInDocuments(@Nullable String uiLanguage) {
        return "en".equals(uiLanguage)
                ? "The organization's documents do not answer this question."
                : "Tài liệu của tổ chức chưa có thông tin để trả lời câu hỏi này.";
    }
}
