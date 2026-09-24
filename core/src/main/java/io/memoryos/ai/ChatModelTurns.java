package io.memoryos.ai;

import java.util.List;
import org.springframework.ai.chat.model.ChatModel;

/**
 * A provider model that publishes turn activity itself: hosted Web search progress and evidence, or reasoning.
 * Clients are shared across turns, so the turn's listener and Web intent attach to a per-turn view. The conversation
 * that owns the turn translates what the listener hears into its own events and evidence.
 */
public interface ChatModelTurns {
    record Turn(Listener listener, boolean webSearch, Runnable checkActive) {}

    /** What a provider reports while it answers, in provider-neutral terms. */
    interface Listener {
        Listener NONE = new Listener() {};

        /** Reasoning text as the provider streams it; a paragraph break arrives as its own delta. */
        default void reasoning(String delta) {}

        /** A hosted Web search began; {@code callId} names it for the calls below. */
        default void webSearchStarted(String callId) {}

        /** The queries a hosted Web search ran. */
        default void webSearchQueries(String callId, List<String> queries) {}

        default void webSearchFinished(String callId) {}

        /**
         * A page the answer cites, found by the hosted search {@code callId}. The URL is untrusted: the listener
         * throws {@link IllegalArgumentException} for one it does not accept as evidence.
         */
        default void webCitation(String callId, String url, String title, String excerpt) {}
    }

    ChatModel forTurn(Turn turn);

    /** Whether this model searches the Web itself, so external Web tools are not added. */
    boolean nativeWebSearch();
}
