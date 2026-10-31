package io.memoryos.chat.streaming;

import io.memoryos.chat.ChatToolEvent;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** What tests read from a replayed stream without caring which record carried it. */
public final class StreamEvents {
    private StreamEvents() {
    }

    /** The answer text of the events, in order. */
    public static String text(List<ChatStreamEvent> events) {
        var text = new StringBuilder();
        for (var event : events) if (event instanceof TextDelta delta) text.append(delta.text());
        return text.toString();
    }

    public static Outcome outcome(List<ChatStreamEvent> events) {
        return (Outcome) events.getLast();
    }

    public static @Nullable ChatToolEvent tool(ChatStreamEvent event) {
        return event instanceof ToolProgress progress ? progress.tool() : null;
    }
}
