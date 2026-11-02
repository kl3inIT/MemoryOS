package io.memoryos.chat.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ChatFollowUpsTest {
    @Test
    void stripsListMarkersAndQuotes() {
        assertEquals(List.of("Mẫu đơn ở đâu?", "Nghỉ quá số ngày thì sao?", "Ai duyệt khi quản lý vắng?"),
                ChatFollowUps.parse("1. \"Mẫu đơn ở đâu?\"\n- **Nghỉ quá số ngày thì sao?**\r\nQ3: “Ai duyệt khi quản lý vắng?”"));
    }

    @Test
    void dropsBlanksAndDuplicatesAndKeepsThree() {
        assertEquals(List.of("A?", "B?", "C?"), ChatFollowUps.parse("A?\n\n  \na?\n2) B?\n• C?\nD?"));
    }

    @Test
    void capsLength() {
        var parsed = ChatFollowUps.parse("x".repeat(400));
        assertEquals(1, parsed.size());
        assertEquals(ChatFollowUps.MAX_LENGTH, parsed.getFirst().codePointCount(0, parsed.getFirst().length()));
        assertTrue(parsed.getFirst().endsWith("…"));
    }

    @Test
    void emptyOutputIsNoSuggestion() {
        assertEquals(List.of(), ChatFollowUps.parse("  \n\"\"\n-"));
    }
}
