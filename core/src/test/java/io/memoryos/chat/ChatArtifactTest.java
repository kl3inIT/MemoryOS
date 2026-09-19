package io.memoryos.chat;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChatArtifactTest {
    private static final String SPEC = """
            {"root":{"component":"Card","props":{"title":"Summary"},"children":[{"component":"Metric","props":{"label":"Revenue","value":"42"}}]}}
            """;

    @Test
    void rejectsActivePropsUnknownComponentsAndResourceAbuse() {
        for (var spec : new String[] {
                "{\"root\":{\"component\":\"iframe\"}}",
                "{\"root\":{\"component\":\"Text\",\"props\":{\"dangerouslySetInnerHTML\":\"bad\"}}}",
                "{\"root\":{\"component\":\"Card\",\"props\":{\"href\":\"javascript:alert(1)\"}}}",
                "{\"root\":{\"component\":\"Text\",\"props\":{\"text\":false}}}",
                "{\"root\":{\"component\":\"Row\",\"children\":[{\"component\":\"Card\"}]}}",
                "{\"root\":{\"component\":\"Text\",\"props\":{\"text\":\"" + "x".repeat(2049) + "\"}}}",
                "{\"root\":{\"component\":\"Card\",\"children\":[" + "{\"component\":\"Text\"},".repeat(24) + "{\"component\":\"Text\"}]}}",
                SPEC.repeat(1000), "[]", "null", "invalid"
        }) assertThrows(RuntimeException.class, () -> new ChatArtifact(UUID.randomUUID(), "Test", spec));
        String deep = "{\"component\":\"Text\"}";
        for (int i = 0; i < 10; i++) deep = "{\"component\":\"Card\",\"children\":[" + deep + "]}";
        final String tooDeep = "{\"root\":" + deep + "}";
        assertThrows(IllegalArgumentException.class, () -> new ChatArtifact(UUID.randomUUID(), "Test", tooDeep));
    }

}
