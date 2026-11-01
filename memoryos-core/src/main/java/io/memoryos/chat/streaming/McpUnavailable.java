package io.memoryos.chat.streaming;

import io.memoryos.chat.ChatActivity;
import java.util.List;
import java.util.UUID;

/**
 * The reply's apps the person must connect first (MEM-273), sent once before the model runs so the Connect card shows
 * while the answer streams; the same list is stored with the reply's activity.
 */
public record McpUnavailable(UUID assistantMessageId, long sequence, List<ChatActivity.UnavailableApp> apps)
        implements ChatStreamEvent {
    public static final String TYPE = "mcp_unavailable";

    public McpUnavailable {
        apps = List.copyOf(apps);
    }

    @Override
    public String type() {
        return TYPE;
    }
}
