package io.memoryos.chat;

import io.memoryos.audit.AuditAction;
import io.memoryos.audit.AuditRecord;
import io.memoryos.audit.AuditTrail;
import io.memoryos.chat.persistence.ChatSettingsEntity;
import io.memoryos.chat.persistence.JpaChatSettingsRepository;
import io.memoryos.iam.group.IamAuthorization;
import io.memoryos.iam.group.IamCapability;
import io.memoryos.iam.identity.ActorId;
import io.memoryos.iam.tenant.TenantAccessResolver;
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

    /** Deep research is enabled while an administrator has not saved settings, as Onyx reads an unset value. */
    public record View(boolean deepResearchEnabled, io.memoryos.chat.history.ChatHistoryVisibility chatHistoryVisibility,
                       long revision) {}

    @Transactional(readOnly = true)
    public View read(ActorId actor) {
        var tenant = tenants.findActiveTenant(actor).orElseThrow(ChatException::unavailable).value();
        return settings.findById(tenant).map(ChatSettingsService::view)
                .orElse(new View(true, io.memoryos.chat.history.ChatHistoryVisibility.NORMAL, 0));
    }

    @Transactional
    public View save(ActorId actor, boolean deepResearchEnabled, long revision) {
        var entity = writable(actor, revision);
        entity.deepResearchEnabled(deepResearchEnabled);
        var saved = settings.saveAndFlush(entity);
        record(actor, saved, "deepResearchEnabled", deepResearchEnabled);
        return view(saved);
    }

    /** One audit line per administrative change to these settings; the Tenant comes from the row itself. */
    private void record(ActorId actor, ChatSettingsEntity saved, String field, Object value) {
        audit.record(AuditRecord
                .of(AuditAction.CHAT_SETTINGS_CHANGE,
                        saved.tenantId())
                .actor(actor.value()).resource("SETTING", "chat", "Chat").detail(field, value).build());
    }

    private ChatSettingsEntity writable(ActorId actor, long revision) {
        var tenant = authorization.lockAndRequireExclusive(actor, IamCapability.MODELS_MANAGE).tenantId().value();
        var entity = settings.findById(tenant).orElseGet(() -> new ChatSettingsEntity(tenant));
        if (entity.revision() != revision) throw ChatException.conflict();
        return entity;
    }

    /** Who in the organization may read other people's conversations, and how much of them (MEM-125). */
    @Transactional
    public View saveHistoryVisibility(ActorId actor, io.memoryos.chat.history.ChatHistoryVisibility visibility, long revision) {
        var entity = writable(actor, revision);
        entity.chatHistoryVisibility(visibility.name());
        var saved = settings.saveAndFlush(entity);
        record(actor, saved, "chatHistoryVisibility", visibility.name());
        return view(saved);
    }

    /** The Tenant's setting, read without any capability: the history reads themselves are what is guarded. */
    @Transactional(readOnly = true)
    public io.memoryos.chat.history.ChatHistoryVisibility historyVisibility(io.memoryos.iam.tenant.TenantId tenant) {
        return settings.findById(tenant.value())
                .map(entity -> io.memoryos.chat.history.ChatHistoryVisibility.valueOf(entity.chatHistoryVisibility()))
                .orElse(io.memoryos.chat.history.ChatHistoryVisibility.NORMAL);
    }

    private static View view(ChatSettingsEntity entity) {
        return new View(entity.deepResearchEnabled(),
                io.memoryos.chat.history.ChatHistoryVisibility.valueOf(entity.chatHistoryVisibility()), entity.revision());
    }
}
