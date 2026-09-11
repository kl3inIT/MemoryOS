package io.memoryos.chat.application;

import io.memoryos.chat.ChatException;
import io.memoryos.chat.ChatBranch;
import io.memoryos.chat.ChatMessage;
import io.memoryos.chat.ChatSession;
import io.memoryos.chat.ChatSessionService;
import io.memoryos.chat.persistence.JdbcChatRepository;
import io.memoryos.iam.ActorId;
import io.memoryos.iam.TenantAccessResolver;
import io.memoryos.iam.TenantId;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefaultChatSessionService implements ChatSessionService {
    private final TenantAccessResolver tenants;
    private final JdbcChatRepository chats;
    private final PersonaProperties persona;

    public DefaultChatSessionService(TenantAccessResolver tenants, JdbcChatRepository chats, PersonaProperties persona) {
        this.tenants = tenants;
        this.chats = chats;
        this.persona = persona;
    }

    @Override
    @Transactional
    public ChatSession create(ActorId actor, String title) {
        if (title == null || title.isBlank() || title.length() > 200) {
            throw ChatException.invalid("Title must contain 1 to 200 characters.");
        }
        var tenant = tenants.lockActiveMembership(actor).orElseThrow(ChatException::unavailable).tenantId();
        var personaId = chats.provisionPersona(tenant, persona.getName(), persona.getInstructions(), persona.getModel());
        return chats.create(tenant, actor, personaId, title.strip());
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatSession> list(ActorId actor, int offset, int limit) {
        page(offset, limit);
        return chats.list(tenant(actor), actor, offset, limit);
    }

    @Override
    @Transactional(readOnly = true)
    public ChatSession get(ActorId actor, UUID sessionId) {
        return chats.findOwned(tenant(actor), actor, sessionId, false).orElseThrow(ChatException::unavailable);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatMessage> history(ActorId actor, UUID sessionId, @Nullable UUID after, int limit) {
        page(0, limit);
        var session = chats.findOwned(tenant(actor), actor, sessionId, false).orElseThrow(ChatException::unavailable);
        // The repository validates that the cursor belongs to the selected branch.
        return chats.history(session, after, limit);
    }

    private TenantId tenant(ActorId actor) {
        return tenants.findActiveTenant(actor).orElseThrow(ChatException::unavailable);
    }

    @Override
    @Transactional
    public ChatSession rename(ActorId actor, UUID sessionId, String title) {
        if (title == null || title.isBlank() || title.length() > 200) throw ChatException.invalid("Title must contain 1 to 200 characters.");
        var tenant = tenants.lockActiveMembership(actor).orElseThrow(ChatException::unavailable).tenantId();
        chats.lockOwner(tenant, actor);
        chats.findOwned(tenant, actor, sessionId, true).orElseThrow(ChatException::unavailable);
        chats.rename(sessionId, title.strip());
        return chats.findOwned(tenant, actor, sessionId, false).orElseThrow();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatBranch> branches(ActorId actor, UUID sessionId) {
        chats.findOwned(tenant(actor), actor, sessionId, false).orElseThrow(ChatException::unavailable);
        return chats.branches(sessionId);
    }

    @Override
    @Transactional
    public void selectBranch(ActorId actor, UUID sessionId, UUID messageId, @Nullable UUID expectedChildId) {
        var tenant = tenants.lockActiveMembership(actor).orElseThrow(ChatException::unavailable).tenantId();
        chats.lockOwner(tenant, actor);
        var session = chats.findOwned(tenant, actor, sessionId, true).orElseThrow(ChatException::unavailable);
        if (chats.hasActiveReply(sessionId)) throw ChatException.conflict();
        var target = chats.message(sessionId, messageId).orElseThrow(ChatException::unavailable);
        if (target.parentMessageId() == null) throw ChatException.invalid("Select a message version.");
        var parent = chats.message(sessionId, target.parentMessageId()).orElseThrow(ChatException::unavailable);
        if (!chats.onSelectedBranch(session, parent.id()) || !java.util.Objects.equals(parent.latestChildMessageId(), expectedChildId))
            throw ChatException.conflict();
        chats.selectChild(sessionId, parent.id(), target.id());
    }

    private static void page(int offset, int limit) {
        if (offset < 0 || offset > 10000 || limit < 1 || limit > 100) {
            throw ChatException.invalid("Page offset must be 0 to 10000 and limit 1 to 100.");
        }
    }
}
