package io.memoryos.chat.streaming;

import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A tool call that waits for the person (MEM-253), then the same call with its {@code decision}. Unlike
 * {@link ToolProgress} it carries the call's arguments, bounded, because the person decides on them; the stream is
 * read only by those who may read the reply. {@code toolTitle} is the tool's display name, when it gives one.
 */
public record ToolApproval(UUID assistantMessageId, long sequence, String toolCallId, String serverName,
                           String toolName, @Nullable String toolTitle, String arguments, @Nullable Decision decision, boolean alwaysAllowable)
        implements ChatStreamEvent {
    public static final String TYPE = "tool_approval";
    /** The arguments shown in the card; a longer value is cut, the call itself is unchanged. */
    public static final int MAX_ARGUMENTS = 4000;

    public enum Decision { APPROVED, DECLINED, NO_DECISION }

    public ToolApproval {
        Objects.requireNonNull(toolCallId, "toolCallId");
        Objects.requireNonNull(serverName, "serverName");
        Objects.requireNonNull(toolName, "toolName");
        arguments = arguments == null ? "" : arguments.length() > MAX_ARGUMENTS ? arguments.substring(0, MAX_ARGUMENTS) : arguments;
    }

    @Override
    public String type() {
        return TYPE;
    }
}
