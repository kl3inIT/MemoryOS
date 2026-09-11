package io.memoryos.chat;

import io.memoryos.chat.persistence.ChatFeedbackEntity;
import io.memoryos.chat.persistence.ChatSharingEntity;
import io.memoryos.chat.persistence.JdbcChatRepository;
import io.memoryos.chat.persistence.JpaChatSharingRepository;
import io.memoryos.chat.persistence.JpaChatFeedbackRepository;
import io.memoryos.iam.ActorId;
import io.memoryos.iam.TenantAccessResolver;
import io.memoryos.iam.TenantId;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChatCollaborationService {
    private final TenantAccessResolver tenants;
    private final JdbcChatRepository chats;
    private final JpaChatSharingRepository shares;
    private final JpaChatFeedbackRepository feedbacks;
    public ChatCollaborationService(TenantAccessResolver tenants, JdbcChatRepository chats, JpaChatSharingRepository shares, JpaChatFeedbackRepository feedbacks) {
        this.tenants = tenants; this.chats = chats; this.shares = shares; this.feedbacks = feedbacks;
    }
    public record Sharing(boolean enabled, long revision) {}
    public record SharedSession(UUID id, String title, UUID rootMessageId) {}
    public record Feedback(UUID assistantMessageId, @Nullable Boolean positive, String comment, String reason) {}

    @Transactional(readOnly = true)
    public Sharing sharing(ActorId actor, UUID session) {
        var tenant = tenant(actor);
        chats.findOwned(tenant, actor, session, false).orElseThrow(ChatException::unavailable);
        return shares.findByTenantIdAndSessionId(tenant.value(), session).map(s -> new Sharing(s.enabled(), s.revision())).orElse(new Sharing(false, 0));
    }
    @Transactional
    public Sharing share(ActorId actor, UUID session, boolean enabled, long revision) {
        var tenant = ownedWrite(actor, session);
        var entity = shares.findByTenantIdAndSessionId(tenant.value(), session)
                .orElseGet(() -> shares.saveAndFlush(new ChatSharingEntity(tenant.value(), session)));
        if (entity.revision() != revision) throw ChatException.conflict();
        entity.setEnabled(enabled); shares.flush();
        return new Sharing(entity.enabled(), entity.revision());
    }
    @Transactional(readOnly = true)
    public SharedSession shared(ActorId actor, UUID session) {
        var found = chats.shared(tenant(actor), session).orElseThrow(ChatException::unavailable);
        return new SharedSession(found.id(), found.title(), found.rootMessageId());
    }
    @Transactional(readOnly = true)
    public List<ChatMessage> sharedHistory(ActorId actor, UUID session, @Nullable UUID after, int limit) {
        ChatPersonaService.page(0, limit);
        var found = chats.shared(tenant(actor), session).orElseThrow(ChatException::unavailable);
        return chats.history(found, after, limit).stream().filter(m -> m.status() != ChatMessage.Status.RUNNING).toList();
    }
    @Transactional(readOnly = true)
    public List<Feedback> feedback(ActorId actor, UUID session, List<UUID> messageIds) {
        if (messageIds == null || messageIds.size() > 100) throw ChatException.invalid("Select at most 100 outputs.");
        var tenant = tenant(actor);
        chats.findOwned(tenant, actor, session, false).orElseThrow(ChatException::unavailable);
        if (messageIds.isEmpty()) return List.of();
        return feedbacks.findByTenantIdAndActorIdAndSessionIdAndAssistantIdIn(tenant.value(), actor.value(), session, messageIds)
                .stream().map(ChatCollaborationService::view).toList();
    }
    @Transactional
    public Feedback feedback(ActorId actor, UUID session, UUID assistant, @Nullable Boolean positive, String comment, String reason) {
        var tenant = ownedWrite(actor, session); output(session, assistant);
        ChatPersonaService.text(comment, 4000, false); ChatPersonaService.text(reason, 100, false);
        if (positive == null && comment.isBlank()) throw ChatException.invalid("Provide a rating or comment.");
        var entity = feedbacks.findByTenantIdAndActorIdAndAssistantId(tenant.value(), actor.value(), assistant)
                .orElseGet(() -> new ChatFeedbackEntity(tenant.value(), actor.value(), session, assistant));
        entity.update(positive, comment, reason); return view(feedbacks.saveAndFlush(entity));
    }
    @Transactional
    public void removeFeedback(ActorId actor, UUID session, UUID assistant) {
        var tenant = ownedWrite(actor, session); output(session, assistant);
        feedbacks.findByTenantIdAndActorIdAndAssistantId(tenant.value(), actor.value(), assistant).ifPresent(feedbacks::delete);
    }
    private void output(UUID session, UUID assistant) {
        var message = chats.message(session, assistant).orElseThrow(ChatException::unavailable);
        if (message.role() != ChatMessage.Role.ASSISTANT || message.status() == ChatMessage.Status.RUNNING || message.content() == null || message.content().isBlank())
            throw ChatException.invalid("Feedback requires a saved assistant output.");
    }
    private TenantId tenant(ActorId actor) { return tenants.findActiveTenant(actor).orElseThrow(ChatException::unavailable); }
    private TenantId ownedWrite(ActorId actor, UUID session) {
        var tenant = tenants.lockActiveMembership(actor).orElseThrow(ChatException::unavailable).tenantId();
        chats.lockOwner(tenant, actor);
        chats.findOwned(tenant, actor, session, true).orElseThrow(ChatException::unavailable); return tenant;
    }
    private static Feedback view(ChatFeedbackEntity f) { return new Feedback(f.assistantId(), f.positive(), f.comment(), f.reason()); }
}
