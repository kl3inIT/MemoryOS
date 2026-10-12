package io.memoryos.chat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.text.Normalizer;
import java.util.Locale;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The Tenant's sensitive-topic guardrails (MEM-195), after Amazon Q Business topic controls and blocked phrases: a
 * topic is matched by meaning from its description and example questions, a phrase by its exact words. Since MEM-208
 * the topics are the Tenant's own; the three built-in ones are seed data it may change or delete.
 */
public record ChatGuardrails(List<Topic> topics, List<String> blockedPhrases, String blockedPhraseMessage) {
    public static final int MAX_PHRASES = 20;
    public static final int MAX_PHRASE_LENGTH = 100;
    public static final int MAX_MESSAGE_LENGTH = 500;
    /** Amazon Q Business: a topic name of at most 36 characters, a description of at most 350, five examples. */
    public static final int MAX_TOPICS = 30;
    public static final int MAX_NAME_LENGTH = 36;
    public static final int MAX_DESCRIPTION_LENGTH = 350;
    public static final int MAX_EXAMPLES = 5;
    public static final int MAX_EXAMPLE_LENGTH = 200;
    public static final String DEFAULT_PHRASE_MESSAGE = "Trợ lý không trả lời câu hỏi này.";
    public static final String DEFAULT_TOPIC_MESSAGE = "Trợ lý không trả lời câu hỏi về chủ đề này.";

    /**
     * The seed every Tenant starts from, off until it turns a topic on. The ids are fixed so V137 and this list name
     * the same topics; {@code GuardrailTopicsMigrationTest} holds the two together.
     */
    public static final List<Topic> BUILT_IN = List.of(
            new Topic(UUID.fromString("0f5b6f2a-7c1d-4e8a-9b3c-000000000001"), "Chính trị",
                    "Câu hỏi xin ý kiến, đánh giá hoặc dự đoán về đảng phái, bầu cử, nhà nước và chính sách của nhà nước, "
                            + "tranh cãi chính trị, hoặc tranh chấp lãnh thổ và chủ quyền.",
                    List.of("Đảng nào tốt hơn?", "Bạn nghĩ gì về chính sách của nhà nước?", "Ai sẽ thắng cuộc bầu cử tới?"),
                    "Trợ lý không trả lời câu hỏi về chính trị.", false),
            new Topic(UUID.fromString("0f5b6f2a-7c1d-4e8a-9b3c-000000000002"), "Lãnh tụ và lãnh đạo",
                    "Câu hỏi về đời tư, gia đình, tính cách hoặc đánh giá các lãnh tụ, nguyên thủ quốc gia, lãnh đạo nhà "
                            + "nước và nhân vật chính trị trong lịch sử, kể cả khi nhắc đến gián tiếp.",
                    List.of("Vợ bác Hồ là ai?", "Đánh giá ông X thế nào?", "Chủ tịch nước có con không?"),
                    "Trợ lý không trả lời câu hỏi về lãnh tụ và lãnh đạo.", false),
            new Topic(UUID.fromString("0f5b6f2a-7c1d-4e8a-9b3c-000000000003"), "Tôn giáo",
                    "Câu hỏi so sánh, phán xét hoặc cổ vũ các tôn giáo, tín ngưỡng hay nghi lễ tôn giáo.",
                    List.of("Tôn giáo nào đúng nhất?", "Có nên theo đạo X không?", "Đạo nào tốt hơn đạo nào?"),
                    "Trợ lý không trả lời câu hỏi về tôn giáo.", false));
    /** No topic and no phrase: what a turn without any settings service applies. */
    public static final ChatGuardrails NONE = new ChatGuardrails(List.of(), List.of(), DEFAULT_PHRASE_MESSAGE);
    /** A Tenant that never saved its guardrails: the seed topics, all off, and no phrase. */
    public static final ChatGuardrails DEFAULT = new ChatGuardrails(BUILT_IN, List.of(), DEFAULT_PHRASE_MESSAGE);

    public ChatGuardrails {
        topics = List.copyOf(topics);
        blockedPhrases = List.copyOf(blockedPhrases);
        blockedPhraseMessage = blockedPhraseMessage == null || blockedPhraseMessage.isBlank()
                ? DEFAULT_PHRASE_MESSAGE : blockedPhraseMessage;
    }

    /**
     * A topic the Tenant blocks by meaning: the description is what the model classifies by, the examples are the
     * Amazon Q "example chat messages", and the message is what the person is told.
     */
    public record Topic(UUID id, String name, String description, List<String> examples, String message, boolean enabled) {
        public Topic {
            examples = List.copyOf(examples);
            message = message == null || message.isBlank() ? DEFAULT_TOPIC_MESSAGE : message;
        }
    }

    public List<Topic> enabledTopics() {
        return topics.stream().filter(Topic::enabled).toList();
    }

    public boolean active() { return !blockedPhrases.isEmpty() || !enabledTopics().isEmpty(); }

    /** The first blocked phrase the text contains, ignoring case, as Spring AI {@code SafeGuardAdvisor} matches words. */
    public @Nullable String blockedPhraseIn(String text) {
        String folded = fold(text);
        for (String phrase : blockedPhrases) if (folded.contains(fold(phrase))) return phrase;
        return null;
    }

    /** Vietnamese accents arrive both precomposed and decomposed; compare both sides in NFC, ignoring case. */
    static String fold(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    public int longestPhrase() { return blockedPhrases.stream().mapToInt(String::length).max().orElse(0); }

    /**
     * Validates an administrator's edit: phrases are trimmed, blank and duplicate ones dropped; a topic is trimmed, its
     * description folded onto one line (it reaches a system prompt) and blank examples dropped.
     */
    public static ChatGuardrails of(List<Topic> topics, List<String> phrases, @Nullable String phraseMessage) {
        var cleaned = new ArrayList<String>();
        for (String phrase : phrases) {
            String value = phrase == null ? "" : Normalizer.normalize(phrase.strip(), Normalizer.Form.NFC);
            if (value.isEmpty() || cleaned.stream().anyMatch(existing -> fold(existing).equals(fold(value)))) continue;
            if (value.length() > MAX_PHRASE_LENGTH) throw ChatException.invalid("A blocked phrase is too long.");
            cleaned.add(value);
        }
        if (cleaned.size() > MAX_PHRASES) throw ChatException.invalid("At most 20 blocked phrases.");
        if (phraseMessage != null && phraseMessage.length() > MAX_MESSAGE_LENGTH) throw ChatException.invalid("The message is too long.");
        if (topics.size() > MAX_TOPICS) throw ChatException.invalid("At most 30 topics.");
        var accepted = new ArrayList<Topic>();
        var ids = new HashSet<UUID>();
        var names = new HashSet<String>();
        for (var topic : topics) {
            String name = line(topic.name());
            String description = line(topic.description());
            if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) throw ChatException.invalid("A topic name is empty or too long.");
            if (description.isEmpty() || description.length() > MAX_DESCRIPTION_LENGTH)
                throw ChatException.invalid("A topic description is empty or too long.");
            var examples = topic.examples().stream().map(ChatGuardrails::line).filter(example -> !example.isEmpty()).toList();
            if (examples.size() > MAX_EXAMPLES || examples.stream().anyMatch(example -> example.length() > MAX_EXAMPLE_LENGTH))
                throw ChatException.invalid("A topic has too many or too long examples.");
            String message = topic.message().strip();
            if (message.length() > MAX_MESSAGE_LENGTH) throw ChatException.invalid("The message is too long.");
            var id = topic.id();
            if (!ids.add(id) || !names.add(fold(name))) throw ChatException.invalid("A topic is listed twice.");
            accepted.add(new Topic(id, name, description, examples, message, topic.enabled()));
        }
        return new ChatGuardrails(accepted, cleaned, phraseMessage);
    }

    /** Trimmed, in NFC, with every run of whitespace, line breaks included, as one space. */
    private static String line(@Nullable String text) {
        return text == null ? "" : Normalizer.normalize(text, Normalizer.Form.NFC).replaceAll("\\s+", " ").strip();
    }
}
