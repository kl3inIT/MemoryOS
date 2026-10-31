package io.memoryos.chat;

import io.memoryos.chat.session.persistence.JdbcChatRepository;
import io.memoryos.iam.IamAuthorization;
import io.memoryos.iam.IamCapability;
import io.memoryos.iam.TenantAccessResolver;
import io.memoryos.mcp.McpAppHost;
import io.memoryos.mcp.McpAppResource;
import io.memoryos.shared.ActorId;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The MCP Apps views of a conversation's answers (MEM-275), read back to the conversation's owner only. A view's own
 * requests run on the server that showed it, never one the view names, so a view cannot reach another server; the
 * MCP capability checks again that the owner can still use that server.
 */
@Service
public class ChatToolAppService {
    private final JdbcChatRepository chats;
    private final IamAuthorization authorization;
    private final TenantAccessResolver tenants;
    private final McpAppHost host;
    private final TransactionTemplate reads;

    public ChatToolAppService(JdbcChatRepository chats, IamAuthorization authorization, TenantAccessResolver tenants,
                              McpAppHost host, PlatformTransactionManager transactionManager) {
        this.chats = chats; this.authorization = authorization; this.tenants = tenants; this.host = host;
        this.reads = new TransactionTemplate(transactionManager);
        reads.setReadOnly(true);
    }

    /** The views of {@code messages}, answers of the actor's own conversation {@code sessionId}. */
    public Map<UUID, List<ChatToolApp>> forMessages(ActorId actor, UUID sessionId, Collection<UUID> messages) {
        if (messages.isEmpty()) return Map.of();
        return Objects.requireNonNull(reads.execute(_ -> {
            owned(actor, sessionId);
            return chats.toolApps(sessionId, messages);
        }));
    }

    /** The HTML of a call's view, or of another {@code ui://} view of the same server. */
    public McpAppResource view(ActorId actor, UUID sessionId, UUID messageId, String callId, String uri) {
        return host.view(actor, app(actor, sessionId, messageId, callId).serverId(), uri);
    }

    /** A tool call the view makes; a tool that asks first runs only with {@code approved}. */
    public String callTool(ActorId actor, UUID sessionId, UUID messageId, String callId, String name,
                           Map<String, Object> arguments, boolean approved) {
        return host.callTool(actor, app(actor, sessionId, messageId, callId).serverId(), name, arguments, approved);
    }

    public String readResource(ActorId actor, UUID sessionId, UUID messageId, String callId, String uri) {
        return host.readResource(actor, app(actor, sessionId, messageId, callId).serverId(), uri);
    }

    public String listResources(ActorId actor, UUID sessionId, UUID messageId, String callId, @Nullable String cursor) {
        return host.listResources(actor, app(actor, sessionId, messageId, callId).serverId(), cursor);
    }

    /** Read in its own transaction, so no database connection is held while the MCP server answers. */
    private ChatToolApp app(ActorId actor, UUID sessionId, UUID messageId, String callId) {
        return Objects.requireNonNull(reads.execute(_ -> {
            owned(actor, sessionId);
            return chats.toolApp(sessionId, messageId, callId).orElseThrow(ChatException::unavailable);
        }));
    }

    private void owned(ActorId actor, UUID sessionId) {
        authorization.require(actor, IamCapability.CHAT_READ, false);
        var tenant = tenants.findActiveTenant(actor).orElseThrow(ChatException::unavailable);
        chats.findOwned(tenant, actor, sessionId, false).orElseThrow(ChatException::unavailable);
    }
}
