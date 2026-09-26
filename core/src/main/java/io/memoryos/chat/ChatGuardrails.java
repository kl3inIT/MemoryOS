package io.memoryos.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The Tenant's sensitive-topic guardrails (MEM-195), after Amazon Q Business topic controls and blocked phrases: a
 * topic is matched by meaning from its description and example questions, a phrase by its exact words. This first cut
 * offers only the built-in topics; their wording lives here, and the Tenant chooses which apply and what the person is told.
 */
public record ChatGuardrails(List<TopicSetting> topics, List<String> blockedPhrases, String blockedPhraseMessage) {
    public static final int MAX_PHRASES = 20;
    public static final int MAX_PHRASE_LENGTH = 100;
    public static final int MAX_MESSAGE_LENGTH = 500;
    public static final String DEFAULT_PHRASE_MESSAGE = "Trợ lý không trả lời câu hỏi này.";
    public static final ChatGuardrails NONE = new ChatGuardrails(List.of(), List.of(), DEFAULT_PHRASE_MESSAGE);

    public ChatGuardrails {
        topics = List.copyOf(topics);
        blockedPhrases = List.copyOf(blockedPhrases);
        blockedPhraseMessage = blockedPhraseMessage == null || blockedPhraseMessage.isBlank()
                ? DEFAULT_PHRASE_MESSAGE : blockedPhraseMessage;
    }

    /** A built-in topic the classifier recognises; the examples are the Amazon Q "example chat messages". */
    public enum Topic {
        POLITICS("Politics",
                "Questions asking for opinions, judgements or predictions about political parties, elections, the state "
                        + "and its policies, political disputes, or territorial and sovereignty controversies.",
                List.of("Đảng nào tốt hơn?", "Bạn nghĩ gì về chính sách của nhà nước?", "Ai sẽ thắng cuộc bầu cử tới?"),
                "Trợ lý không trả lời câu hỏi về chính trị."),
        LEADERS("Leaders",
                "Questions about the private life, family, character or evaluation of national leaders, heads of state "
                        + "and historical political figures, including indirect references to them.",
                List.of("Vợ bác Hồ là ai?", "Đánh giá ông X thế nào?", "Chủ tịch nước có con không?"),
                "Trợ lý không trả lời câu hỏi về lãnh tụ và lãnh đạo."),
        RELIGION("Religion",
                "Questions asking to compare, judge or promote religions, beliefs or religious practices.",
                List.of("Tôn giáo nào đúng nhất?", "Có nên theo đạo X không?", "Đạo nào tốt hơn đạo nào?"),
                "Trợ lý không trả lời câu hỏi về tôn giáo.");

        private final String label;
        private final String description;
        private final List<String> examples;
        private final String defaultMessage;

        Topic(String label, String description, List<String> examples, String defaultMessage) {
            this.label = label; this.description = description; this.examples = examples; this.defaultMessage = defaultMessage;
        }
        public String label() { return label; }
        public String description() { return description; }
        public List<String> examples() { return examples; }
        public String defaultMessage() { return defaultMessage; }
    }

    /** One topic as the Tenant set it; an unset topic is off with its default message. */
    public record TopicSetting(Topic topic, boolean enabled, String message) {
        public TopicSetting {
            message = message == null || message.isBlank() ? topic.defaultMessage() : message;
        }
    }

    /** Every built-in topic, with the Tenant's choice where it made one. */
    public List<TopicSetting> allTopics() {
        var all = new ArrayList<TopicSetting>();
        for (var topic : Topic.values())
            all.add(topics.stream().filter(setting -> setting.topic() == topic).findFirst()
                    .orElse(new TopicSetting(topic, false, null)));
        return List.copyOf(all);
    }

    public List<TopicSetting> enabledTopics() {
        return allTopics().stream().filter(TopicSetting::enabled).toList();
    }

    public boolean active() { return !blockedPhrases.isEmpty() || !enabledTopics().isEmpty(); }

    public @Nullable TopicSetting topic(Topic topic) {
        return allTopics().stream().filter(setting -> setting.topic() == topic).findFirst().orElse(null);
    }

    /** The first blocked phrase the text contains, ignoring case, as Spring AI {@code SafeGuardAdvisor} matches words. */
    public @Nullable String blockedPhraseIn(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String phrase : blockedPhrases) if (lower.contains(phrase.toLowerCase(Locale.ROOT))) return phrase;
        return null;
    }

    public int longestPhrase() { return blockedPhrases.stream().mapToInt(String::length).max().orElse(0); }

    /** Validates an administrator's edit; phrases are trimmed, blank and duplicate ones dropped. */
    public static ChatGuardrails of(List<TopicSetting> topics, List<String> phrases, @Nullable String phraseMessage) {
        var cleaned = new ArrayList<String>();
        for (String phrase : phrases) {
            String value = phrase == null ? "" : phrase.strip();
            if (value.isEmpty() || cleaned.stream().anyMatch(existing -> existing.equalsIgnoreCase(value))) continue;
            if (value.length() > MAX_PHRASE_LENGTH) throw ChatException.invalid("A blocked phrase is too long.");
            cleaned.add(value);
        }
        if (cleaned.size() > MAX_PHRASES) throw ChatException.invalid("At most 20 blocked phrases.");
        if (phraseMessage != null && phraseMessage.length() > MAX_MESSAGE_LENGTH) throw ChatException.invalid("The message is too long.");
        for (var topic : topics)
            if (topic.message().length() > MAX_MESSAGE_LENGTH) throw ChatException.invalid("The message is too long.");
        if (topics.stream().map(TopicSetting::topic).distinct().count() != topics.size())
            throw ChatException.invalid("A topic is listed twice.");
        return new ChatGuardrails(topics, cleaned, phraseMessage);
    }
}
