package io.memoryos.chat.application;

import io.memoryos.chat.ChatException;
import io.memoryos.chat.ChatMessage;
import io.memoryos.chat.ChatSource;
import io.memoryos.chat.persistence.JdbcChatRepository;
import io.memoryos.chat.execution.ChatTurnSetup;
import io.memoryos.chat.execution.ChatModelBinding;
import io.memoryos.iam.ActorId;
import io.memoryos.iam.TenantAccessResolver;
import io.memoryos.iam.TenantId;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authorized turn transactions; SQL remains in the concrete Chat repository.
 */
@Service
public class ChatTurnPersistence {
    private final TenantAccessResolver tenants;
    private final JdbcChatRepository chats;
    private final PersonaProperties persona;

    public ChatTurnPersistence(TenantAccessResolver tenants, JdbcChatRepository chats, PersonaProperties persona) {
        this.tenants = tenants;
        this.chats = chats;
        this.persona = persona;
    }

    @Transactional
    public Reservation reserve(ActorId actor, UUID sessionId, UUID parentId, UUID requestId,
                               String text, Duration timeout, int contextTokenLimit) {
        return reserve(actor, sessionId, parentId, requestId, text, timeout, contextTokenLimit, null);
    }

    public record ModelSelection(@Nullable UUID requestedId, UUID selectedId, @Nullable String fallbackReason, ChatModelBinding binding) {}

    @Transactional
    public Reservation reserve(ActorId actor, UUID sessionId, UUID parentId, UUID requestId,
                               String text, Duration timeout, int contextTokenLimit, @Nullable ModelSelection selection) {
        if (parentId == null || requestId == null || text == null || text.isBlank() || text.length() > 32000
                || timeout == null || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(30)) > 0) {
            throw ChatException.invalid("Invalid chat message or deadline.");
        }
        var tenant = tenants.lockActiveMembership(actor).orElseThrow(ChatException::unavailable).tenantId();
        var session = chats.findOwned(tenant, actor, sessionId, true).orElseThrow(ChatException::unavailable);
        var previous = chats.previousRequest(sessionId, requestId);
        if (previous.isPresent()) {
            var user = previous.orElseThrow();
            if (!Objects.equals(user.parentMessageId(), parentId) || !user.content().equals(text)
                    || !Objects.equals(user.requestedModelId(), selection == null ? null : selection.requestedId()))
                throw ChatException.conflict();
            return new Reservation(user.userMessageId(), user.assistantMessageId(), false, user.selectedModelId(), user.fallbackReason());
        }
        if (chats.hasActiveReply(sessionId)) throw ChatException.conflict();
        var parent = chats.message(sessionId, parentId).orElseThrow(ChatException::unavailable);
        if (parent.role() == ChatMessage.Role.USER || parent.latestChildMessageId() != null
                || !chats.onSelectedBranch(session, parentId)) throw ChatException.conflict();
        if (chats.messageCount(sessionId) > 9998) throw ChatException.invalid("Chat session message limit reached.");
        // Builtin Persona is configuration-backed until the editor consumer is implemented.
        chats.provisionPersona(tenant, persona.getName(), persona.getInstructions(), persona.getModel());
        if (selection == null) ChatTurnSetup.validateQuestion(chats.persona(sessionId).instructions(), text, contextTokenLimit);
        else ChatTurnSetup.validateQuestion(chats.persona(sessionId).instructions(), text, contextTokenLimit, selection.binding());
        UUID user = UUID.randomUUID();
        UUID assistant = UUID.randomUUID();
        chats.insertPair(sessionId, parentId, requestId, user, assistant, text, timeout);
        if (selection != null) chats.saveModelSelection(sessionId, user, assistant, selection.requestedId(), selection.selectedId(), selection.fallbackReason());
        return new Reservation(user, assistant, true, selection == null ? null : selection.selectedId(), selection == null ? null : selection.fallbackReason());
    }

    @Transactional
    public boolean finish(UUID sessionId, UUID assistantId, ChatMessage.Status status, String partialContent) {
        if (status == null || status == ChatMessage.Status.RUNNING || partialContent == null || partialContent.length() > 1000000) {
            throw ChatException.invalid("Invalid terminal outcome.");
        }
        return finish(sessionId, assistantId, status, partialContent, null, null, null, null, null);
    }

    public record Reservation(UUID userMessageId, UUID assistantMessageId, boolean created,
                              @Nullable UUID modelConfigurationId, @Nullable String fallbackReason) {
        public Reservation(UUID userMessageId, UUID assistantMessageId, boolean created) {
            this(userMessageId, assistantMessageId, created, null, null);
        }
    }

    @Transactional(readOnly = true)
    public Optional<Reservation> existing(ActorId actor, UUID session, UUID parent, UUID request, String text) {
        return existing(actor, session, parent, request, text, null);
    }

    @Transactional(readOnly = true)
    public Optional<Reservation> existing(ActorId actor, UUID session, UUID parent, UUID request, String text, @Nullable UUID requestedModelId) {
        var tenant = tenants.findActiveTenant(actor).orElseThrow(ChatException::unavailable);
        chats.findOwned(tenant, actor, session, false).orElseThrow(ChatException::unavailable);
        return chats.previousRequest(session, request).map(previous -> {
            if (!Objects.equals(previous.parentMessageId(), parent) || !previous.content().equals(text)
                    || !Objects.equals(previous.requestedModelId(), requestedModelId))
                throw ChatException.conflict();
            return new Reservation(previous.userMessageId(), previous.assistantMessageId(), false, previous.selectedModelId(), previous.fallbackReason());
        });
    }

    @Transactional(readOnly = true)
    public TurnContext loadContext(ActorId actor, UUID sessionId, Reservation reservation) {
        var tenant = tenants.findActiveTenant(actor).orElseThrow(ChatException::unavailable);
        chats.findOwned(tenant, actor, sessionId, false).orElseThrow(ChatException::unavailable);
        var persona = chats.persona(sessionId);
        return new TurnContext(actor, tenant, persona.model(), persona.instructions(), chats.context(sessionId, reservation.userMessageId(), 200),
                chats.control(reservation.assistantMessageId()).deadline());
    }

    public record TurnContext(ActorId actor, TenantId tenant, String model, String instructions,
                              List<ChatMessage> newestFirst, Instant deadline) {
    }

    @Transactional
    public ChatMessage.Status authorizeReply(ActorId actor, UUID session, UUID assistant) {
        var tenant = tenants.lockActiveMembership(actor).orElseThrow(ChatException::unavailable).tenantId();
        chats.findOwned(tenant, actor, session, true).orElseThrow(ChatException::unavailable);
        var message = chats.message(session, assistant).orElseThrow(ChatException::unavailable);
        if (message.role() != ChatMessage.Role.ASSISTANT)
            throw ChatException.invalid("Only an assistant reply can be stopped.");
        return message.status();
    }

    @Transactional(readOnly = true)
    public JdbcChatRepository.Control control(UUID assistant) {
        return chats.control(assistant);
    }

    @Transactional
    public boolean finish(UUID session, UUID assistant, ChatMessage.Status status, String partial,
                          @Nullable String failure, @Nullable String model, @Nullable Long input, @Nullable Long output, @Nullable Double cost) {
        if (status == null || status == ChatMessage.Status.RUNNING || partial == null || partial.length() > 1000000)
            throw ChatException.invalid("Invalid terminal outcome.");
        return chats.finish(session, assistant, status, partial, failure, model, input, output, cost);
    }

    public record TerminalOutcome(ChatMessage.Status status, @Nullable String failureCode) {}

    @Transactional
    public TerminalOutcome finishAndRead(UUID session, UUID assistant, ChatMessage.Status status, String partial,
                          @Nullable String failure, @Nullable String model, @Nullable Long input, @Nullable Long output,
                          @Nullable Double cost, List<ChatSource> sources) {
        if (status == null || status == ChatMessage.Status.RUNNING || partial == null || partial.length() > 1000000 || sources.size() > 24)
            throw ChatException.invalid("Invalid terminal outcome.");
        chats.finish(session, assistant, status, partial, failure, model, input, output, cost, sources);
        var saved = chats.control(assistant);
        return new TerminalOutcome(saved.status(), saved.failureCode());
    }

    @Transactional
    public int expireRuns() {
        return chats.expireRuns();
    }
}
