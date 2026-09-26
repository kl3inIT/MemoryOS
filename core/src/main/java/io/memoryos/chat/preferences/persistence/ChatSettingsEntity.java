package io.memoryos.chat.preferences.persistence;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/** One row per Tenant once an administrator saves Chat settings; absent means the defaults. */
@Entity
@Table(name = "chat_settings")
public class ChatSettingsEntity {
    @Id @Column(name = "tenant_id", nullable = false, updatable = false) private UUID tenantId;
    @Column(name = "deep_research_enabled", nullable = false) private boolean deepResearchEnabled = true;
    /** MEM-125: how much of the Tenant's conversations an administrative reader may see. */
    @Column(name = "chat_history_visibility", nullable = false) private String chatHistoryVisibility = "NORMAL";
    /** MEM-195: every turn answers from the Tenant's documents only. */
    @Column(name = "grounded_answers", nullable = false) private boolean groundedAnswers;
    /** MEM-195: in grounded mode, whether a person may turn Web search on for a turn. */
    @Column(name = "grounded_allow_web", nullable = false) private boolean groundedAllowWeb;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "guardrail_topics", nullable = false, columnDefinition = "jsonb")
    private List<StoredTopic> guardrailTopics = new ArrayList<>();
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "blocked_phrases", nullable = false, columnDefinition = "jsonb")
    private List<String> blockedPhrases = new ArrayList<>();
    @Column(name = "blocked_phrase_message") private @Nullable String blockedPhraseMessage;
    @Version private @Nullable Long revision;

    /** A built-in topic as stored: its key, whether it applies and the Tenant's message. */
    public record StoredTopic(String topic, boolean enabled, @Nullable String message) {}

    protected ChatSettingsEntity() {}
    public ChatSettingsEntity(UUID tenant) { tenantId = tenant; }
    public UUID tenantId() { return tenantId; }
    public boolean deepResearchEnabled() { return deepResearchEnabled; }
    public long revision() { return revision == null ? 0 : revision; }
    public void deepResearchEnabled(boolean enabled) { deepResearchEnabled = enabled; }
    public String chatHistoryVisibility() { return chatHistoryVisibility; }
    public void chatHistoryVisibility(String visibility) { chatHistoryVisibility = visibility; }
    public boolean groundedAnswers() { return groundedAnswers; }
    public boolean groundedAllowWeb() { return groundedAllowWeb; }
    public void grounded(boolean answers, boolean allowWeb) { groundedAnswers = answers; groundedAllowWeb = allowWeb; }
    public List<StoredTopic> guardrailTopics() { return List.copyOf(guardrailTopics); }
    public List<String> blockedPhrases() { return List.copyOf(blockedPhrases); }
    public @Nullable String blockedPhraseMessage() { return blockedPhraseMessage; }
    public void guardrails(List<StoredTopic> topics, List<String> phrases, @Nullable String phraseMessage) {
        guardrailTopics = new ArrayList<>(topics);
        blockedPhrases = new ArrayList<>(phrases);
        blockedPhraseMessage = phraseMessage;
    }
}
