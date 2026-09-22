package io.memoryos.chat.preferences;

import io.memoryos.chat.ChatException;
import io.memoryos.chat.persistence.JdbcChatPreferencesRepository;
import io.memoryos.chat.persistence.JdbcChatSessionPurgeRepository;
import io.memoryos.iam.identity.ActorId;
import io.memoryos.iam.tenant.TenantAccessResolver;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * How long a person keeps their own conversations. ChatGPT records the same number as a workspace retention
 * policy an administrator sets, and Onyx as {@code maximum_chat_retention_days}; here it belongs to the owner,
 * because it deletes nothing but their own history and needs no authority beyond being the person whose
 * history it is. Archived conversations are counted too, as archiving in ChatGPT does not change a retention
 * period.
 *
 * <p>Deleting is not undoable, so the count of what a number would delete right now is read before it is
 * saved: the person decides against a figure, not against a promise.
 */
@Service
public class ChatRetentionService {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(ChatRetentionService.class);
    /** The longest policy anyone may set, so a mistyped number cannot mean a century. */
    public static final int MAX_RETENTION_DAYS = 3650;
    /**
     * Conversations one statement deletes while saving. The confirmation named a number, so the save keeps
     * sweeping batches until the backlog is gone rather than leaving part of that number to the worker; the
     * round cap bounds one request, and the hourly worker finishes anything past it.
     */
    private static final int SAVE_BATCH = 500;
    private static final int SAVE_ROUNDS = 20;

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

    /**
     * Saves the policy and immediately deletes what it already covers, because the confirmation the person
     * answered said the overdue conversations go now. The hourly worker keeps applying it afterwards.
     */
    @Transactional
    public Policy save(ActorId actor, @Nullable Integer days) {
        var tenant = tenants.findActiveTenant(actor).orElseThrow(ChatException::unavailable).value();
        preferences.retentionDays(tenant, actor.value(), validated(days));
        if (days == null) return new Policy(null);
        int deleted = 0;
        for (int round = 0; round < SAVE_ROUNDS; round++) {
            int batch = sessions.applyRetention(tenant, actor.value(), days, SAVE_BATCH);
            deleted += batch;
            if (batch < SAVE_BATCH) break;
        }
        if (deleted > 0) {
            LOGGER.atInfo().addKeyValue("event", "chat.retention.applied").addKeyValue("tenant_id", tenant)
                    .addKeyValue("days", days).addKeyValue("deleted", deleted)
                    .log("Deleted conversations past the owner's retention policy");
        }
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
