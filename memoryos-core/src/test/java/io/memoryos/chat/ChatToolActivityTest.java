package io.memoryos.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** MEM-275: a completed step shows its MCP Apps view only when the answer keeps it. */
class ChatToolActivityTest {
    private static final UUID SERVER = UUID.randomUUID();
    private static final String FULL = "x".repeat(ChatToolApp.FIELD_BYTES);

    @Test
    void aStepShowsTheViewTheAnswerKeeps() {
        var events = new ArrayList<ChatToolEvent>();
        var transcript = new ChatToolTranscript();
        var activity = new ChatToolActivity(events::add, transcript);

        var call = activity.begin("call_1", "mcp_canva_create-design");
        activity.app(SERVER, "ui://canva/create-design", "{}", "{\"content\":[]}");
        activity.end(call, false, 5);

        assertNotNull(events.getLast().app());
        assertEquals(List.of(events.getLast().app()), transcript.apps());
    }

    @Test
    void pastTheAnswersViewBoundAStepShowsNoView() {
        var events = new ArrayList<ChatToolEvent>();
        var transcript = new ChatToolTranscript();
        for (int kept = 0; kept * 2L * ChatToolApp.FIELD_BYTES < ChatToolTranscript.APP_MESSAGE_BYTES; kept++)
            transcript.app(new ChatToolApp("kept_" + kept, SERVER, "ui://canva/create-design", FULL, FULL));
        var activity = new ChatToolActivity(events::add, transcript);

        var call = activity.begin("call_9", "mcp_canva_create-design");
        activity.app(SERVER, "ui://canva/create-design", "{}", "{\"content\":[]}");
        activity.end(call, false, 5);

        assertEquals(ChatToolEvent.Stage.COMPLETED, events.getLast().stage());
        assertNull(events.getLast().app());
    }
}
