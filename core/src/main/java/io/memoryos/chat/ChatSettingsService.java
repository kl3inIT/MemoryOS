package io.memoryos.chat;

import io.memoryos.shared.TenantId;

import io.memoryos.audit.AuditAction;
import io.memoryos.audit.AuditRecord;
import io.memoryos.audit.AuditTrail;
import io.memoryos.chat.preferences.persistence.ChatSettingsEntity;
import io.memoryos.chat.preferences.persistence.JpaChatSettingsRepository;
import io.memoryos.iam.IamAuthorization;
import io.memoryos.iam.IamCapability;
import io.memoryos.shared.ActorId;
import io.memoryos.iam.TenantAccessResolver;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Tenant Chat settings, as Onyx Chat Preferences: members read them, model managers change them. */
@Service
public class ChatSettingsService {
    private final JpaChatSettingsRepository settings;
    private final IamAuthorization authorization;
    private final TenantAccessResolver tenants;
    private final AuditTrail audit;

    public ChatSettingsService(JpaChatSettingsRepository settings, IamAuthorization authorization,
                               TenantAccessResolver tenants, AuditTrail audit) {
        this.settings = settings; this.authorization = authorization; this.tenants = tenants; this.audit = audit;
    }

    /**
     * Deep research is enabled while an administrator has not saved settings, as Onyx reads an unset value. Answers
     * from documents only (MEM-195) are off until an administrator turns them on.
     */
    public record View(boolean deepResearchEnabled, ChatHistoryVisibility chatHistoryVisibility,
                       boolean groundedAnswers, boolean groundedAllowWeb, long revision) {}

    /** What a turn needs from these settings; read without a capability, since the turn itself is authorized. */
    public record TurnPolicy(boolean groundedAnswers, boolean groundedAllowWeb, ChatGuardrails guardrails) {
        public static final TurnPolicy NONE = new TurnPolicy(false, false, ChatGuardrails.NONE);
    }

    /** The guardrails as a model manager edits them, with the revision the next save must carry. */
    public record GuardrailsView(ChatGuardrails guardrails, long revision) {}

    @Transactional(readOnly = true)
    public View read(ActorId actor) {
        var tenant = tenants.findActiveTenant(actor).orElseThrow(ChatException::unavailable).value();
        return settings.findById(tenant).map(ChatSettingsService::view)
                .orElse(new View(true, ChatHistoryVisibility.NORMAL, false, false, 0));
    }

    @Transactional(readOnly = true)
    public TurnPolicy turnPolicy(ActorId actor) {
        var tenant = tenants.findActiveTenant(actor).orElseThrow(ChatException::unavailable).value();
        return settings.findById(tenant)
                .map(entity -> new TurnPolicy(entity.groundedAnswers(), entity.groundedAllowWeb(), guardrails(entity)))
                .orElse(TurnPolicy.NONE);
    }

    @Transactional
    public View save(ActorId actor, boolean deepResearchEnabled, long revision) {
        var entity = writable(actor, revision);
        entity.deepResearchEnabled(deepResearchEnabled);
        var saved = settings.saveAndFlush(entity);
        record(actor, saved, "deepResearchEnabled", deepResearchEnabled);
        return view(saved);
    }

    /** MEM-195: answers from the organization's documents only, and whether a person may still turn Web search on. */
    @Transactional
    public View saveGrounded(ActorId actor, boolean groundedAnswers, boolean groundedAllowWeb, long revision) {
        var entity = writable(actor, revision);
        entity.grounded(groundedAnswers, groundedAllowWeb);
        var saved = settings.saveAndFlush(entity);
        audit.record(AuditRecord.of(AuditAction.CHAT_SETTINGS_CHANGE, new TenantId(saved.tenantId()))
                .actor(actor).resource("SETTING", "chat", "Chat")
                .detail("groundedAnswers", groundedAnswers).detail("groundedAllowWeb", groundedAllowWeb).build());
        return view(saved);
    }

    /** The phrase list is itself sensitive, so only model managers read the guardrails. */
    @Transactional(readOnly = true)
    public GuardrailsView guardrails(ActorId actor) {
        var tenant = authorization.require(actor, IamCapability.MODELS_MANAGE, false).tenantId().value();
        return settings.findById(tenant).map(entity -> new GuardrailsView(guardrails(entity), entity.revision()))
                .orElse(new GuardrailsView(ChatGuardrails.NONE, 0));
    }

    @Transactional
    public GuardrailsView saveGuardrails(ActorId actor, List<ChatGuardrails.TopicSetting> topics, List<String> phrases,
                                        @Nullable String phraseMessage, long revision) {
        var validated = ChatGuardrails.of(topics, phrases, phraseMessage);
        var entity = writable(actor, revision);
        var stored = new ArrayList<ChatSettingsEntity.StoredTopic>();
        for (var topic : validated.topics())
            stored.add(new ChatSettingsEntity.StoredTopic(topic.topic().name(), topic.enabled(), topic.message()));
        entity.guardrails(stored, validated.blockedPhrases(), phraseMessage == null || phraseMessage.isBlank() ? null : phraseMessage);
        var saved = settings.saveAndFlush(entity);
        // The phrases themselves stay out of the audit stream: they may name what the organization keeps confidential.
        audit.record(AuditRecord.of(AuditAction.CHAT_SETTINGS_CHANGE, new TenantId(saved.tenantId()))
                .actor(actor).resource("SETTING", "chat", "Chat")
                .detail("guardrailTopics", validated.enabledTopics().stream().map(topic -> topic.topic().name()).toList())
                .detail("blockedPhrases", validated.blockedPhrases().size()).build());
        return new GuardrailsView(guardrails(saved), saved.revision());
    }

    /** One audit line per administrative change to these settings; the Tenant comes from the row itself. */
    private void record(ActorId actor, ChatSettingsEntity saved, String field, Object value) {
        audit.record(AuditRecord
                .of(AuditAction.CHAT_SETTINGS_CHANGE,
                        new TenantId(saved.tenantId()))
                .actor(actor).resource("SETTING", "chat", "Chat").detail(field, value).build());
    }

    private ChatSettingsEntity writable(ActorId actor, long revision) {
        var tenant = authorization.lockAndRequireExclusive(actor, IamCapability.MODELS_MANAGE).tenantId().value();
        var entity = settings.findById(tenant).orElseGet(() -> new ChatSettingsEntity(tenant));
        if (entity.revision() != revision) throw ChatException.conflict();
        return entity;
    }

    /** Who in the organization may read other people's conversations, and how much of them (MEM-125). */
    @Transactional
    public View saveHistoryVisibility(ActorId actor, ChatHistoryVisibility visibility, long revision) {
        var entity = writable(actor, revision);
        entity.chatHistoryVisibility(visibility.name());
        var saved = settings.saveAndFlush(entity);
        record(actor, saved, "chatHistoryVisibility", visibility.name());
        return view(saved);
    }

    /** The Tenant's setting, read without any capability: the history reads themselves are what is guarded. */
    @Transactional(readOnly = true)
    public ChatHistoryVisibility historyVisibility(TenantId tenant) {
        return settings.findById(tenant.value())
                .map(entity -> ChatHistoryVisibility.valueOf(entity.chatHistoryVisibility()))
                .orElse(ChatHistoryVisibility.NORMAL);
    }

    private static View view(ChatSettingsEntity entity) {
        return new View(entity.deepResearchEnabled(),
                ChatHistoryVisibility.valueOf(entity.chatHistoryVisibility()),
                entity.groundedAnswers(), entity.groundedAllowWeb(), entity.revision());
    }

    /** A stored topic this version no longer knows is ignored rather than failing every turn. */
    private static ChatGuardrails guardrails(ChatSettingsEntity entity) {
        var topics = new ArrayList<ChatGuardrails.TopicSetting>();
        for (var stored : entity.guardrailTopics()) {
            for (var topic : ChatGuardrails.Topic.values())
                if (topic.name().equals(stored.topic())) topics.add(new ChatGuardrails.TopicSetting(topic, stored.enabled(), stored.message()));
        }
        return new ChatGuardrails(topics, entity.blockedPhrases(), entity.blockedPhraseMessage());
    }
}
