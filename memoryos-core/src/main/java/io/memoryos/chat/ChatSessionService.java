package io.memoryos.chat;

import io.memoryos.ai.ReasoningEffort;
import io.memoryos.shared.ActorId;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Authenticated session operations; message execution is owned by the Chat capability. */
public interface ChatSessionService {
    ChatSession create(ActorId actor, String title);

    /**
     * A temporary conversation (MEM-153) is listed nowhere, cannot be shared or put in a Project, and deletes
     * itself with its uploads a while after its last message.
     */
    ChatSession create(ActorId actor, String title, boolean temporary);

    /** A conversation held in a channel: kept like any other, and listed in the web's sidebar and search by none. */
    ChatSession createInChannel(ActorId actor, String title);
    /** {@code archived} lists the conversations the owner archived instead of the ones on the sidebar. */
    default List<ChatSession> list(ActorId actor, boolean archived, int offset, int limit) {
        return list(actor, archived, null, offset, limit);
    }

    /** A {@code personaId} keeps the caller's topics of one assistant, whether or not it can still use it. */
    List<ChatSession> list(ActorId actor, boolean archived, @Nullable UUID personaId, int offset, int limit);

    /** Archives or unarchives one conversation the caller owns; idempotent. */
    ChatSession archive(ActorId actor, UUID sessionId, boolean archived);

    /** Archives the caller's conversations in one bounded command; answers how many were archived. */
    int archiveAll(ActorId actor);
    /** Fetch one extra result for hasMore without a separate unbounded count query. */
    default List<ChatSessionMatch> search(ActorId actor, String query, int offset, int limit) {
        return search(actor, query, null, offset, limit);
    }

    /** A {@code personaId} keeps the caller's topics of one assistant. */
    List<ChatSessionMatch> search(ActorId actor, String query, @Nullable UUID personaId, int offset, int limit);
    ChatSession get(ActorId actor, UUID sessionId);
    ChatSession rename(ActorId actor, UUID sessionId, String title);
    /** Pins how much this conversation's model should think, or clears the choice with a null level. */
    ChatSession pinReasoningEffort(ActorId actor, UUID sessionId, @Nullable ReasoningEffort effort);
    List<ChatBranch> branches(ActorId actor, UUID sessionId);
    void selectBranch(ActorId actor, UUID sessionId, UUID messageId, @Nullable UUID expectedChildId);
    List<ChatMessage> history(ActorId actor, UUID sessionId, @Nullable UUID after, int limit);

    /**
     * The last message of the selected branch, which the next question continues; the root of a conversation
     * nobody has written in. A caller that holds no transcript, such as a channel, asks for it before it sends.
     */
    UUID leaf(ActorId actor, UUID sessionId);
}
