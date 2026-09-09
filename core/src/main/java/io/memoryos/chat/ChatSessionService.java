package io.memoryos.chat;

import io.memoryos.iam.ActorId;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Authenticated session operations; message execution is owned by the Chat capability. */
public interface ChatSessionService {
    ChatSession create(ActorId actor, String title);
    List<ChatSession> list(ActorId actor, int offset, int limit);
    ChatSession get(ActorId actor, UUID sessionId);
    List<ChatMessage> history(ActorId actor, UUID sessionId, @Nullable UUID after, int limit);
}
