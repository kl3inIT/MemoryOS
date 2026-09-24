package io.memoryos.chat.preferences;

import io.memoryos.chat.ChatException;
import io.memoryos.chat.persistence.JdbcChatPreferencesRepository;
import io.memoryos.chat.persistence.JdbcChatSessionPurgeRepository;
import io.memoryos.shared.ActorId;
import io.memoryos.iam.TenantAccessResolver;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * How long a person keeps their own conversations. ChatGPT's "Delete conversations after" and Onyx's
 * {@code maximum_chat_retention_days} are the same number seen from two sides; here it is the owner's, because
 * it deletes nothing but their own history and needs no authority beyond being the person whose history it is.
 *
 * <p>Deleting is not undoable, so the count of what a number would delete right now is read before it is
 * saved: the person decides against a figure, not against a promise.
 */
@Service
public class ChatRetentionService {
    /** The longest policy anyone may set, so a mistyped number cannot mean a century. */
    public static final int MAX_RETENTION_DAYS = 3650;

    private final JdbcChatPreferencesRepository preferences;
    private final JdbcChatSessionPurgeRepository sessions;
    private final TenantAccessResolver tenants;

    public ChatRetentionService(JdbcChatPreferencesRepository preferences,
                                JdbcChatSessionPurgeRepository sessions, TenantAccessResolver tenants) {
        this.preferences = preferences; this.sessions = sessions; this.tenants = tenants;
    }

    /** The person's policy; {@code days} null is no policy, and conversations are kept until they delete them. */
    public record Policy(@Nullable Integer days) {}

    /** What a policy would do now: how many of this person's conversations are already older than it. */
    public record Preview(@Nullable Integer days, long affected) {}

    @Transactional(readOnly = true)
    public Policy read(ActorId actor) {
        var tenant = tenants.findActiveTenant(actor).orElseThrow(ChatException::unavailable).value();
        return new Policy(preferences.retentionDays(tenant, actor.value()).orElse(null));
    }

    @Transactional
    public Policy save(ActorId actor, @Nullable Integer days) {
        var tenant = tenants.findActiveTenant(actor).orElseThrow(ChatException::unavailable).value();
        preferences.retentionDays(tenant, actor.value(), validated(days));
        return new Policy(days);
    }

    @Transactional(readOnly = true)
    public Preview preview(ActorId actor, @Nullable Integer days) {
        var tenant = tenants.findActiveTenant(actor).orElseThrow(ChatException::unavailable).value();
        if (days == null) return new Preview(null, 0);
        return new Preview(days, sessions.affectedByRetention(tenant, actor.value(), validated(days)));
    }

    private static @Nullable Integer validated(@Nullable Integer days) {
        if (days != null && (days < 1 || days > MAX_RETENTION_DAYS))
            throw ChatException.invalid("Retention must be between 1 and " + MAX_RETENTION_DAYS + " days.");
        return days;
    }
}
