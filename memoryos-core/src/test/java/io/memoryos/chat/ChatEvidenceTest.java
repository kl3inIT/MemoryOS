package io.memoryos.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChatEvidenceTest {
    @Test
    void citationsHaveNoCountCapOnlyAByteBound() {
        var evidence = new ChatEvidence();
        var published = new ArrayList<ChatToolEvent>();
        evidence.publishTo(published::add);
        for (int index = 1; index <= 40; index++) {
            var source = evidence.file(UUID.randomUUID(), "File " + index, "text/plain");
            assertNotNull(source, "Citation " + index + " must be registered");
            assertEquals(index, source.citationId());
        }
        assertEquals(40, evidence.snapshot().size());
        assertEquals(40, published.size());
        // A long title estimates to about 6 KiB; the turn stops registering at the byte bound, not at a count.
        String title = "t".repeat(1024);
        ChatSource last = null;
        for (int index = 0; index < 400; index++) {
            var source = evidence.file(UUID.randomUUID(), title, "text/plain");
            if (source == null) break;
            last = source;
        }
        assertNotNull(last);
        assertNull(evidence.file(UUID.randomUUID(), title, "text/plain"));
        assertEquals(last.citationId(), evidence.snapshot().size());
        assertEquals(List.copyOf(evidence.snapshot()).getLast(), last);
    }
}
