package io.memoryos.chat;

import com.embabel.agent.api.tool.Tool;
import com.embabel.agent.api.tool.callback.AfterToolCallContext;
import com.embabel.agent.api.tool.callback.BeforeToolCallContext;
import com.embabel.agent.api.tool.callback.ToolCallInspector;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * One runner-wide inspector: every tool call publishes STARTED and a terminal stage under its provider call ID.
 * It tracks one call in progress, which relies on the default sequential Embabel tool loop; a parallel tool loop
 * would need per-call state before it could be enabled for Chat.
 */
public final class ChatToolActivity implements ToolCallInspector {
    private final Consumer<? super ChatToolEvent> events;
    private final @Nullable ChatToolTranscript transcript;
    private volatile ChatToolEvent.@Nullable Call current;
    private volatile boolean failed;
    private volatile ChatToolEvent.@Nullable Failure failure;
    private volatile Set<String> quiet = Set.of();

    public ChatToolActivity(Consumer<? super ChatToolEvent> events) {
        this(events, null);
    }

    /** Also keeps each call with its arguments and result in {@code transcript}, for later turns (MEM-287). */
    public ChatToolActivity(Consumer<? super ChatToolEvent> events, @Nullable ChatToolTranscript transcript) {
        this.events = events;
        this.transcript = transcript;
    }

    /**
     * Tools whose calls are no step of their own: an MCP server's opening tool (MEM-272) only makes its tools
     * available, and the calls of those tools are the steps.
     */
    public void quiet(Set<String> names) {
        quiet = Set.copyOf(names);
    }

    @Override public void beforeToolCall(BeforeToolCallContext context) {
        if (quiet.contains(context.getToolCall().getName())) return;
        begin(context.getToolCall().getId(), context.getToolCall().getName());
    }

    /** A caller that runs tools itself (deep research) opens the step, so tool evidence and progress share its identity. */
    public ChatToolEvent.Call begin(@Nullable String id, @Nullable String name) {
        var call = call(id, name);
        current = call;
        failed = false;
        failure = null;
        events.accept(ChatToolEvent.started(call));
        return call;
    }

    /** Closes a step opened by {@link #begin}. */
    public void end(ChatToolEvent.Call call, boolean error, long durationMs) {
        current = null;
        events.accept(ChatToolEvent.finished(call, failed || error, durationMs, failure));
    }

    @Override public void afterToolCall(AfterToolCallContext context) {
        if (quiet.contains(context.getToolCall().getName())) return;
        var active = current;
        var call = active != null && active.name().equals(context.getToolCall().getName()) ? active
                : call(context.getToolCall().getId(), context.getToolCall().getName());
        current = null;
        if (transcript != null)
            transcript.record(call.id(), call.name(), context.getToolCall().getArguments(), context.getResultAsString());
        events.accept(ChatToolEvent.finished(call, failed || context.getResult() instanceof Tool.Result.Error, context.getDurationMs(), failure));
    }

    /** The call in progress, so tool evidence and progress share its identity. */
    public ChatToolEvent.@Nullable Call current() {
        return current;
    }

    /** A tool that reports its own failure as a model-visible message still records the step as failed. */
    public void fail() {
        failed = true;
    }

    /** As {@link #fail()}, with the category the person can act on. */
    public void fail(ChatToolEvent.Failure reason) {
        failed = true;
        failure = reason;
    }

    /** Provider call identity normalized to the event bounds. */
    public static ChatToolEvent.Call call(@Nullable String callId, @Nullable String callName) {
        String id = callId == null || callId.isBlank() || callId.length() > 256 ? "call-" + UUID.randomUUID() : callId;
        String name = callName == null || !callName.matches("[A-Za-z0-9_.-]{1,64}") ? "tool" : callName;
        return new ChatToolEvent.Call(id, name);
    }
}
