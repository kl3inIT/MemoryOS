package io.memoryos.chat;

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
    private final io.memoryos.iam.audit.AuditTrail audit;

    public ChatSettingsService(JpaChatSettingsRepository settings, IamAuthorization authorization,
                               TenantAccessResolver tenants, io.memoryos.iam.audit.AuditTrail audit) {
        this.settings = settings; this.authorization = authorization; this.tenants = tenants; this.audit = audit;
    }

    /** Deep research is enabled while an administrator has not saved settings, as Onyx reads an unset value. */
    public record View(boolean deepResearchEnabled, long revision) {}

    @Transactional(readOnly = true)
    public View read(ActorId actor) {
        var tenant = tenants.findActiveTenant(actor).orElseThrow(ChatException::unavailable).value();
        return settings.findById(tenant).map(ChatSettingsService::view).orElse(new View(true, 0));
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
        audit.record(io.memoryos.iam.audit.AuditRecord
                .of(io.memoryos.iam.audit.AuditAction.CHAT_SETTINGS_CHANGE,
                        new io.memoryos.iam.tenant.TenantId(saved.tenantId()))
                .actor(actor).resource("SETTING", "chat", "Chat").detail(field, value).build());
    }

    private ChatSettingsEntity writable(ActorId actor, long revision) {
        var tenant = authorization.lockAndRequireExclusive(actor, IamCapability.MODELS_MANAGE).tenantId().value();
        var entity = settings.findById(tenant).orElseGet(() -> new ChatSettingsEntity(tenant));
        if (entity.revision() != revision) throw ChatException.conflict();
        return entity;
    }

    private static View view(ChatSettingsEntity entity) {
        return new View(entity.deepResearchEnabled(), entity.revision());
    }
}
