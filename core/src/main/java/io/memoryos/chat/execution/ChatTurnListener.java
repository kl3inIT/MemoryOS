package io.memoryos.chat.execution;

import io.memoryos.ai.ModelTurns;
import io.memoryos.chat.ChatActivityEvent;
import io.memoryos.chat.ChatEvidence;
import io.memoryos.chat.ChatReasoningDelta;
import io.memoryos.chat.ChatSource;
import io.memoryos.chat.ChatToolEvent;
import io.memoryos.retrieval.SearchFilters;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Turns what a provider reports during a turn into Chat activity: reasoning deltas, hosted Web search steps and the
 * pages it cites as turn evidence.
 */
public record ChatTurnListener(ChatEvidence evidence, Consumer<ChatActivityEvent> events) implements ModelTurns.Listener {
    private static final String WEB_SEARCH = "web_search";

    /** The provider-side turn view for this Chat turn. */
    public static ModelTurns.Turn turn(ChatEvidence evidence, Consumer<ChatActivityEvent> events, boolean webSearch,
                                           Runnable checkActive) {
        return new ModelTurns.Turn(new ChatTurnListener(evidence, events), webSearch, checkActive);
    }

    @Override
    public void reasoning(String delta) {
        events.accept(new ChatReasoningDelta(delta));
    }

    @Override
    public void webSearchStarted(String callId) {
        events.accept(new ChatToolEvent(call(callId), ChatToolEvent.Stage.STARTED));
    }

    @Override
    public void webSearchQueries(String callId, List<String> queries) {
        events.accept(new ChatToolEvent(call(callId), new ChatToolEvent.QueryPlan(queries, new SearchFilters(Set.of(), null, null))));
    }

    @Override
    public void webSearchFinished(String callId) {
        events.accept(ChatToolEvent.finished(call(callId), false, null));
    }

    @Override
    public void webCitation(String callId, String url, String title, String excerpt) {
        evidence.register("web:" + url, number -> new ChatSource(number, null, null, title, 0, 0, List.of(), null, null,
                new ChatSource.WebLocation(url, excerpt, Instant.now())), call(callId));
    }

    private static ChatToolEvent.Call call(String callId) {
        return new ChatToolEvent.Call(callId, WEB_SEARCH);
    }
}
