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

    public ChatSettingsService(JpaChatSettingsRepository settings, IamAuthorization authorization, TenantAccessResolver tenants) {
        this.settings = settings; this.authorization = authorization; this.tenants = tenants;
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
        var tenant = authorization.lockAndRequireExclusive(actor, IamCapability.MODELS_MANAGE).tenantId().value();
        var entity = settings.findById(tenant).orElseGet(() -> new ChatSettingsEntity(tenant));
        if (entity.revision() != revision) throw ChatException.conflict();
        entity.deepResearchEnabled(deepResearchEnabled);
        return view(settings.saveAndFlush(entity));
    }

    private static View view(ChatSettingsEntity entity) {
        return new View(entity.deepResearchEnabled(), entity.revision());
    }
}
