package io.memoryos.chat;

import io.memoryos.iam.ActorId;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Authenticated session operations; message execution is owned by the Chat capability. */
public interface ChatSessionService {
    ChatSession create(ActorId actor, String title);
    List<ChatSession> list(ActorId actor, ChatSessionStatus status, int offset, int limit);
    /** Fetch one extra result for hasMore without a separate unbounded count query. */
    List<ChatSession> search(ActorId actor, String query, int offset, int limit);
    ChatSession get(ActorId actor, UUID sessionId);
    ChatSession rename(ActorId actor, UUID sessionId, String title);
    /** Idempotent; does not change the conversation's activity time. */
    ChatSession archive(ActorId actor, UUID sessionId, boolean archived);
    List<ChatBranch> branches(ActorId actor, UUID sessionId);
    void selectBranch(ActorId actor, UUID sessionId, UUID messageId, @Nullable UUID expectedChildId);
    List<ChatMessage> history(ActorId actor, UUID sessionId, @Nullable UUID after, int limit);
}
