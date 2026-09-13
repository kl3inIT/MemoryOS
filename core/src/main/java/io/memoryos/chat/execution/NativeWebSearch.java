package io.memoryos.chat.execution;

import io.memoryos.chat.ChatEvidence;
import io.memoryos.chat.ChatSearchEvent;
import java.util.function.Consumer;
import org.springframework.ai.chat.model.ChatModel;

/**
 * A provider model that can search the Web itself. Clients are shared across turns, so turn-owned
 * evidence, events and required intent attach to a per-turn view. External Web tools are not added.
 */
public interface NativeWebSearch {
    record Turn(ChatEvidence evidence, Consumer<ChatSearchEvent> events, boolean required, Runnable checkActive) {}

    ChatModel forTurn(Turn turn);
}
