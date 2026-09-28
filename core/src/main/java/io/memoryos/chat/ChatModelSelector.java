package io.memoryos.chat;

import io.memoryos.ai.ModelResolver;
import io.memoryos.ai.ModelFlow;
import io.memoryos.chat.session.ChatTurnPersistence;
import io.memoryos.shared.ActorId;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The model client a conversation's turn or naming call runs on. The selection is made and committed in its own
 * transaction; the native client is acquired after it, outside any database transaction.
 */
@Component
public class ChatModelSelector {
    private final ChatModelAccess access;
    private final ModelResolver resolver;

    public ChatModelSelector(ChatModelAccess access, ModelResolver resolver) {
        this.access = access;
        this.resolver = resolver;
    }

    /** The model a send runs on, selected with the session agent the send already read. */
    public ModelResolver.Resolved resolve(ActorId actor, UUID session, @Nullable UUID requested,
                                          ChatTurnPersistence.SessionAgent agent) {
        return resolver.acquire(access.select(actor, session, requested, agent.persona(), agent.modelsManage()));
    }

    /** The Tenant model for this flow, or the conversation model when the flow has none that is usable. */
    public ModelResolver.Resolved resolveFlow(ActorId actor, UUID session, ModelFlow flow) {
        return resolver.acquire(access.selectFlow(actor, session, flow));
    }
}
