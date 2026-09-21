package io.memoryos.chat;

import io.memoryos.chat.persistence.ChatSettingsEntity;
import io.memoryos.chat.persistence.JdbcChatSessionPurgeRepository;
import io.memoryos.chat.persistence.JpaChatSettingsRepository;
import io.memoryos.iam.group.IamAuthorization;
import io.memoryos.iam.group.IamCapability;
import io.memoryos.iam.identity.ActorId;
import io.memoryos.iam.tenant.TenantAccessResolver;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Tenant Chat settings, as Onyx Chat Preferences: members read them, model managers change them. */
@Service
public class ChatSettingsService {
    /** The longest policy an administrator may set, so a mistyped number cannot mean a century. */
    public static final int MAX_RETENTION_DAYS = 3650;

    private final JpaChatSettingsRepository settings;
    private final IamAuthorization authorization;
    private final TenantAccessResolver tenants;
    private final JdbcChatSessionPurgeRepository sessions;
    private final io.memoryos.iam.audit.AuditTrail audit;

    public ChatSettingsService(JpaChatSettingsRepository settings, IamAuthorization authorization,
                               TenantAccessResolver tenants, JdbcChatSessionPurgeRepository sessions,
                               io.memoryos.iam.audit.AuditTrail audit) {
        this.settings = settings; this.authorization = authorization; this.tenants = tenants;
        this.sessions = sessions; this.audit = audit;
    }

    /** Deep research is enabled while an administrator has not saved settings, as Onyx reads an unset value. */
    public record View(boolean deepResearchEnabled, @Nullable Integer chatRetentionDays, long revision) {}

    /**
     * What a retention policy would do (MEM-153): how many conversations are older than it right now, so an
     * administrator sees the size of the change before saving it. {@code days} null asks about no policy.
     */
    public record RetentionPreview(@Nullable Integer days, long affected) {}

    @Transactional(readOnly = true)
    public View read(ActorId actor) {
        var tenant = tenants.findActiveTenant(actor).orElseThrow(ChatException::unavailable).value();
        return settings.findById(tenant).map(ChatSettingsService::view).orElse(new View(true, null, 0));
    }

    @Transactional
    public View save(ActorId actor, boolean deepResearchEnabled, long revision) {
        var entity = writable(actor, revision);
        entity.deepResearchEnabled(deepResearchEnabled);
        var saved = settings.saveAndFlush(entity);
        record(actor, saved, "deepResearchEnabled", deepResearchEnabled);
        return view(saved);
    }

    /**
     * Records how long this Tenant keeps inactive conversations (MEM-153), or clears the policy with null. A
     * worker then deletes what is older, exactly as the owner deleting it would, so this changes no route and
     * no authority — only how long a conversation lives.
     */
    @Transactional
    public View saveRetention(ActorId actor, @Nullable Integer days, long revision) {
        if (days != null && (days < 1 || days > MAX_RETENTION_DAYS))
            throw ChatException.invalid("Retention must be between 1 and " + MAX_RETENTION_DAYS + " days.");
        var entity = writable(actor, revision);
        entity.chatRetentionDays(days);
        var saved = settings.saveAndFlush(entity);
        // A policy that deletes conversations is an administrative change like any other, so it is recorded;
        // the evidence carries the number of days, never a conversation.
        record(actor, saved, "chatRetentionDays", days == null ? "none" : String.valueOf(days));
        return view(saved);
    }

    /** How many conversations a policy would delete now; administration authority, because it counts a Tenant. */
    @Transactional(readOnly = true)
    public RetentionPreview previewRetention(ActorId actor, @Nullable Integer days) {
        var tenant = authorization.require(actor, IamCapability.MODELS_MANAGE, false).tenantId().value();
        if (days == null) return new RetentionPreview(null, 0);
        if (days < 1 || days > MAX_RETENTION_DAYS)
            throw ChatException.invalid("Retention must be between 1 and " + MAX_RETENTION_DAYS + " days.");
        return new RetentionPreview(days, sessions.affectedByRetention(tenant, days));
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
        return new View(entity.deepResearchEnabled(), entity.chatRetentionDays(), entity.revision());
    }
}
