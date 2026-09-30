package io.memoryos.chat.streaming;

import io.memoryos.chat.ChatCodeEvent;
import java.util.Objects;
import java.util.UUID;

/** Progress of one {@code run_python} call. */
public record CodeRun(UUID assistantMessageId, long sequence, ChatCodeEvent code) implements ChatStreamEvent {
    public static final String TYPE = "code";

    public CodeRun {
        Objects.requireNonNull(code, "code");
    }

    @Override
    public String type() {
        return TYPE;
    }
}
