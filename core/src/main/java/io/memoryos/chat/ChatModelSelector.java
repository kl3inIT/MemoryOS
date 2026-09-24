package io.memoryos.chat;

import io.memoryos.ai.ChatModelResolver;
import io.memoryos.ai.ModelFlow;
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
    private final ChatModelResolver resolver;

    public ChatModelSelector(ChatModelAccess access, ChatModelResolver resolver) {
        this.access = access;
        this.resolver = resolver;
    }

    public ChatModelResolver.Resolved resolve(ActorId actor, UUID session, @Nullable UUID requested) {
        return resolver.acquire(access.select(actor, session, requested));
    }

    /** The Tenant model for this flow, or the conversation model when the flow has none that is usable. */
    public ChatModelResolver.Resolved resolveFlow(ActorId actor, UUID session, ModelFlow flow) {
        return resolver.acquire(access.selectFlow(actor, session, flow));
    }
}
