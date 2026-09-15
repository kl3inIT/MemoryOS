package io.memoryos.chat;

import com.embabel.agent.api.tool.Tool;
import com.embabel.agent.api.tool.callback.AfterToolCallContext;
import com.embabel.agent.api.tool.callback.BeforeToolCallContext;
import com.embabel.agent.api.tool.callback.ToolCallInspector;
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
    private volatile ChatToolEvent.@Nullable Call current;
    private volatile boolean failed;

    public ChatToolActivity(Consumer<? super ChatToolEvent> events) {
        this.events = events;
    }

    @Override public void beforeToolCall(BeforeToolCallContext context) {
        var call = call(context.getToolCall());
        current = call;
        failed = false;
        events.accept(new ChatToolEvent(call, ChatToolEvent.Stage.STARTED));
    }

    @Override public void afterToolCall(AfterToolCallContext context) {
        var active = current;
        var call = active != null && active.name().equals(context.getToolCall().getName()) ? active : call(context.getToolCall());
        current = null;
        events.accept(ChatToolEvent.finished(call, failed || context.getResult() instanceof Tool.Result.Error, context.getDurationMs()));
    }

    /** The call in progress, so tool evidence and progress share its identity. */
    public ChatToolEvent.@Nullable Call current() {
        return current;
    }

    /** A tool that reports its own failure as a model-visible message still records the step as failed. */
    public void fail() {
        failed = true;
    }

    private static ChatToolEvent.Call call(com.embabel.chat.ToolCall call) {
        String id = call.getId() == null || call.getId().isBlank() || call.getId().length() > 256 ? "call-" + UUID.randomUUID() : call.getId();
        String name = call.getName() == null || !call.getName().matches("[A-Za-z0-9_.-]{1,64}") ? "tool" : call.getName();
        return new ChatToolEvent.Call(id, name);
    }
}
