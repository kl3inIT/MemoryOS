package io.memoryos.chat.session;

import io.memoryos.ai.ReasoningEffort;
import io.memoryos.chat.ChatException;
import io.memoryos.chat.ChatBranch;
import io.memoryos.chat.ChatMessage;
import io.memoryos.chat.ChatSession;
import io.memoryos.chat.ChatSessionMatch;
import io.memoryos.chat.ChatSessionService;
import io.memoryos.chat.session.persistence.JdbcChatRepository;
import io.memoryos.chat.session.persistence.JdbcChatSearchRepository;
import io.memoryos.shared.ActorId;
import io.memoryos.iam.tenant.TenantAccessResolver;
import io.memoryos.shared.TenantId;
import io.memoryos.iam.group.IamAuthorization;
import io.memoryos.iam.group.IamCapability;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefaultChatSessionService implements ChatSessionService {
    private final TenantAccessResolver tenants;
    private final IamAuthorization authorization;
    private final JdbcChatRepository chats;
    private final JdbcChatSearchRepository search;

    public DefaultChatSessionService(TenantAccessResolver tenants, IamAuthorization authorization, JdbcChatRepository chats,
                                     JdbcChatSearchRepository search) {
        this.tenants = tenants;
        this.authorization = authorization;
        this.chats = chats;
        this.search = search;
    }

    @Override
    @Transactional
    public ChatSession create(ActorId actor, String title) {
        return create(actor, title, false);
    }

    @Override
    @Transactional
    public ChatSession create(ActorId actor, String title, boolean temporary) {
        if (title == null || title.isBlank() || title.length() > 200) {
            throw ChatException.invalid("Title must contain 1 to 200 characters.");
        }
        authorization.require(actor, IamCapability.CHAT_WRITE, false);
        var tenant = tenants.lockActiveMembership(actor).orElseThrow(ChatException::unavailable).tenantId();
        var personaId = chats.defaultPersona(tenant).orElseThrow(ChatException::unavailable);
        return chats.create(tenant, actor, personaId, title.strip(), temporary);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatSession> list(ActorId actor, boolean archived, int offset, int limit) {
        page(offset, limit);
        return chats.list(tenant(actor), actor, archived, offset, limit);
    }

    @Override
    @Transactional
    public ChatSession archive(ActorId actor, UUID sessionId, boolean archived) {
        var tenant = tenants.lockActiveMembership(actor).orElseThrow(ChatException::unavailable).tenantId();
        if (!chats.archive(tenant, actor, sessionId, archived)) throw ChatException.unavailable();
        return chats.findOwned(tenant, actor, sessionId, false).orElseThrow(ChatException::unavailable);
    }

    @Override
    @Transactional
    public int archiveAll(ActorId actor) {
        var tenant = tenants.lockActiveMembership(actor).orElseThrow(ChatException::unavailable).tenantId();
        return chats.archiveAll(tenant, actor, ARCHIVE_ALL_LIMIT);
    }

    @Override
    @Transactional(readOnly = true, timeout = 10)
    public List<ChatSessionMatch> search(ActorId actor, String query, int offset, int limit) {
        page(offset, limit);
        if (query == null || query.length() > 200 || query.indexOf('\0') >= 0 || limit > 50)
            throw ChatException.invalid("Search query must be at most 200 characters and limit at most 50.");
        var tenant = tenant(actor);
        return query.isBlank()
                ? chats.list(tenant, actor, false, offset, limit + 1).stream().map(session -> new ChatSessionMatch(session, null)).toList()
                : search.search(tenant, actor, query.strip(), offset, limit + 1);
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

    /** Every read of the actor's own conversations requires CHAT_READ; rename, branch selection and deletion need ownership only. */
    private TenantId tenant(ActorId actor) {
        authorization.require(actor, IamCapability.CHAT_READ, false);
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
    @Transactional
    public ChatSession pinReasoningEffort(ActorId actor, UUID sessionId,
                                          @Nullable ReasoningEffort effort) {
        var tenant = tenants.lockActiveMembership(actor).orElseThrow(ChatException::unavailable).tenantId();
        chats.lockOwner(tenant, actor);
        chats.findOwned(tenant, actor, sessionId, true).orElseThrow(ChatException::unavailable);
        chats.saveReasoningEffort(tenant, actor, sessionId, effort);
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

    /** As many as Delete all chats takes in one batch: a command answers, it does not run unbounded. */
    static final int ARCHIVE_ALL_LIMIT = 1000;

    private static void page(int offset, int limit) {
        if (offset < 0 || offset > 10000 || limit < 1 || limit > 100) {
            throw ChatException.invalid("Page offset must be 0 to 10000 and limit 1 to 100.");
        }
    }
}
