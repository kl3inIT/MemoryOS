package io.memoryos.chat.streaming;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.memoryos.chat.AssistantPatch;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AssistantChangeJsonTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void anEventCarriesOnlyTheFieldsTheCallChangedAndAClearedFieldAsEmpty() throws Exception {
        var event = new AssistantChange(UUID.randomUUID(), 3, "call-1", new AssistantPatch(null,
                "Chuyên viên nhân sự", null, null, null, "", List.of("Nghỉ phép năm bao nhiêu ngày?"), null, null, null,
                null, null, null, true));

        var change = json.readTree(json.writeValueAsString(event)).get("change");

        assertEquals(List.of("title", "greeting", "starterPrompts", "grounded"), List.copyOf(change.propertyNames()));
        assertEquals("", change.get("greeting").asString());
        assertEquals(event, json.readValue(json.writeValueAsString(event), AssistantChange.class));
    }
}
