package io.memoryos.chat;

import java.util.List;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * The text a grounded turn stores and streams when it declines (MEM-195). The browser renders the refusal from the
 * stored reason; this text is what history, exports and shared transcripts read.
 */
final class ChatRefusals {
    private ChatRefusals() {}

    /** What the person reads when the guardrail check could not run: which topics are restricted, and why no answer. */
    static String unchecked(@Nullable String uiLanguage, List<ChatGuardrails.TopicSetting> topics) {
        boolean english = "en".equals(uiLanguage);
        String names = topics.stream().map(setting -> switch (setting.topic()) {
            case POLITICS -> english ? "politics" : "chính trị";
            case LEADERS -> english ? "leaders" : "lãnh tụ và lãnh đạo";
            case RELIGION -> english ? "religion" : "tôn giáo";
        }).collect(Collectors.joining(", "));
        return english
                ? "The organization restricts these topics: " + names + ". The assistant could not tell whether this "
                        + "question is about them, so it has not answered. Try again."
                : "Tổ chức giới hạn các chủ đề: " + names + ". Trợ lý chưa xác định được câu hỏi này có thuộc các chủ đề "
                        + "đó không nên chưa trả lời. Hãy thử lại.";
    }

    static String notInDocuments(@Nullable String uiLanguage) {
        return "en".equals(uiLanguage)
                ? "The organization's documents do not answer this question."
                : "Tài liệu của tổ chức chưa có thông tin để trả lời câu hỏi này.";
    }
}
