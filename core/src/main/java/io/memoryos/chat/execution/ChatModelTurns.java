package io.memoryos.chat.execution;

import io.memoryos.chat.ChatActivityEvent;
import io.memoryos.chat.ChatEvidence;
import java.util.function.Consumer;
import org.springframework.ai.chat.model.ChatModel;

/**
 * A provider model that publishes turn activity itself: hosted Web search progress and evidence, or reasoning.
 * Clients are shared across turns, so turn-owned evidence, events and Web intent attach to a per-turn view.
 */
public interface ChatModelTurns {
    record Turn(ChatEvidence evidence, Consumer<ChatActivityEvent> events, boolean webSearch, boolean webRequired, Runnable checkActive) {}

    ChatModel forTurn(Turn turn);

    /** Whether this model searches the Web itself, so external Web tools are not added. */
    boolean nativeWebSearch();
}
