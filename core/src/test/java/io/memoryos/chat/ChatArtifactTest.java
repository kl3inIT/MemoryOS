package io.memoryos.chat;

import static org.junit.jupiter.api.Assertions.*;

import io.memoryos.chat.tools.ArtifactTool;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ChatArtifactTest {
    @Test
    void nativeToolSchemaBindsNamedArgumentsAndCollectsRealResults() {
        var collector = new ChatArtifacts();
        var nativeTool = com.embabel.agent.api.tool.Tool.fromInstance(new ArtifactTool(collector, () -> {})).getFirst();
        assertEquals("render_gui", nativeTool.getDefinition().getName());
        var arguments = new tools.jackson.databind.ObjectMapper().writeValueAsString(java.util.Map.of("title", "Revenue", "spec", SPEC));
        nativeTool.call(arguments);
        assertEquals(1, collector.seal().size());
        assertEquals("Revenue", collector.seal().getFirst().title());
    }

    private static final String SPEC = """
            {"root":{"component":"Card","props":{"title":"Summary"},"children":[{"component":"Metric","props":{"label":"Revenue","value":"42"}}]}}
            """;

    @Test
    void nativeToolAcceptsBoundedReadOnlyDataAndSealsAgainstLateCallbacks() {
        var collector = new ChatArtifacts();
        var checks = new AtomicInteger();
        var tool = new ArtifactTool(collector, checks::incrementAndGet);
        assertTrue(tool.render_gui("Summary", SPEC).startsWith("Read-only artifact accepted"));
        assertTrue(tool.render_gui("Summary", SPEC).startsWith("Read-only artifact accepted"));
        assertTrue(tool.render_gui("Summary", SPEC).startsWith("Read-only artifact accepted"));
        assertTrue(tool.render_gui("Summary", SPEC).startsWith("The artifact limit"));
        var saved = collector.seal();
        assertEquals(3, saved.size());
        assertEquals(3, saved.stream().map(ChatArtifact::id).distinct().count());
        assertFalse(collector.add(new ChatArtifact(UUID.randomUUID(), "Late", SPEC)));
        assertEquals(saved, collector.seal());
        assertEquals(8, checks.get());
    }

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

    @Test
    void invalidInputDoesNotLeakItsContentOrKillTheAnswer() {
        var collector = new ChatArtifacts();
        var tool = new ArtifactTool(collector, () -> {});
        assertTrue(tool.render_gui("Secret", "private-invalid-payload").startsWith("Invalid read-only UI"));
        assertTrue(collector.seal().isEmpty());
    }
}
