package io.memoryos.chat;

import io.memoryos.iam.identity.ActorId;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Authenticated session operations; message execution is owned by the Chat capability. */
public interface ChatSessionService {
    ChatSession create(ActorId actor, String title);
    /** {@code archived} lists the conversations the owner archived instead of the ones on the sidebar. */
    List<ChatSession> list(ActorId actor, boolean archived, int offset, int limit);

    /** Archives or unarchives one conversation the caller owns; idempotent. */
    ChatSession archive(ActorId actor, UUID sessionId, boolean archived);

    /** Archives the caller's conversations in one bounded command; answers how many were archived. */
    int archiveAll(ActorId actor);
    /** Fetch one extra result for hasMore without a separate unbounded count query. */
    List<ChatSessionMatch> search(ActorId actor, String query, int offset, int limit);
    ChatSession get(ActorId actor, UUID sessionId);
    ChatSession rename(ActorId actor, UUID sessionId, String title);
    /** Pins how much this conversation's model should think, or clears the choice with a null level. */
    ChatSession pinReasoningEffort(ActorId actor, UUID sessionId, io.memoryos.chat.preferences.@Nullable ReasoningEffort effort);
    List<ChatBranch> branches(ActorId actor, UUID sessionId);
    void selectBranch(ActorId actor, UUID sessionId, UUID messageId, @Nullable UUID expectedChildId);
    List<ChatMessage> history(ActorId actor, UUID sessionId, @Nullable UUID after, int limit);
}
