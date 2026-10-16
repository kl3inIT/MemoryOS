package io.memoryos.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** MEM-287: what an answer keeps of its tool calls for later turns. */
class ChatToolTranscriptTest {
    @Test
    void sandboxAndPublicResultsAreKeptAndKnowledgeResultsAreNot() {
        var transcript = new ChatToolTranscript();
        transcript.record("call_1", "bash", "{\"command\":\"ls\"}", "clean.xlsx");
        transcript.record("call_2", "search_knowledge", "{\"queries\":[\"policy\"]}", "[1] Confidential policy text");
        transcript.record("call_3", "read_file", null, "Attached file text");
        var calls = transcript.calls();
        assertEquals("clean.xlsx", calls.get(0).result());
        assertNull(calls.get(1).result());
        assertEquals(ChatToolTranscript.NOT_KEPT, calls.get(1).shownResult());
        assertEquals("{}", calls.get(2).arguments());
    }

    @Test
    void onceOrganizationContentIsInNoLaterResultIsKept() {
        var transcript = new ChatToolTranscript();
        transcript.record("call_1", "run_python", "{}", "before");
        transcript.record("call_2", "search_knowledge", "{\"queries\":[\"salaries\"]}", "[1] Salary table");
        transcript.record("call_3", "run_python", "{\"code\":\"print(rows)\"}", "Salary table rows");
        var calls = transcript.calls();
        assertEquals("before", calls.get(0).result());
        assertNull(calls.get(2).result());
        assertEquals("{\"code\":\"print(rows)\"}", calls.get(2).arguments());
    }

    @Test
    void anMcpToolBringsOrganizationContentInAndKeepsNoArguments() {
        var transcript = new ChatToolTranscript();
        transcript.record("call_1", "lark_docs_read", "{\"doc\":\"Q3 plan\"}", "Q3 plan text");
        transcript.record("call_2", "bash", "{\"command\":\"cat notes.txt\"}", "Q3 plan text");
        var calls = transcript.calls();
        assertTrue(calls.get(0).arguments().startsWith("{\"note\":"), calls.get(0).arguments());
        assertNull(calls.get(0).result());
        assertNull(calls.get(1).result());
    }

    @Test
    void aMemoryUpdateKeepsNeitherItsTextNorItsResult() {
        var transcript = new ChatToolTranscript();
        transcript.record("call_1", "update_memory", "{\"memory\":\"My salary is 50M\"}", "Saved.");
        transcript.record("call_2", "bash", "{\"command\":\"ls\"}", "clean.xlsx");
        var calls = transcript.calls();
        assertTrue(calls.get(0).arguments().startsWith("{\"note\":"), calls.get(0).arguments());
        assertNull(calls.get(0).result());
        assertEquals("clean.xlsx", calls.get(1).result());
    }

    @Test
    void aLongResultIsCutWithinItsBoundOnACharacterBoundary() {
        var transcript = new ChatToolTranscript();
        transcript.record("call_1", "bash", "{}", "ệ".repeat(40_000));
        String result = transcript.calls().getFirst().result();
        assertTrue(result.getBytes(StandardCharsets.UTF_8).length <= ChatToolTranscript.FIELD_BYTES);
        assertTrue(result.endsWith("[cut: the rest of this result is not kept]"));
        assertTrue(result.startsWith("ệệệ"));
    }

    @Test
    void argumentsTooLargeAreReplacedByValidJsonNeverCut() {
        var transcript = new ChatToolTranscript();
        transcript.record("call_1", "edit_file", "{\"file_text\":\"" + "a".repeat(70_000) + "\"}", "Created.");
        String arguments = transcript.calls().getFirst().arguments();
        assertTrue(arguments.startsWith("{\"note\":") && arguments.endsWith("}"), arguments);
    }

    @Test
    void pastTheMessageBoundTheRemainingResultsAreNotKept() {
        var transcript = new ChatToolTranscript();
        for (int i = 0; i < 6; i++) transcript.record("call_" + i, "bash", "{}", "b".repeat(60_000));
        var calls = transcript.calls();
        assertEquals(6, calls.size());
        assertEquals(4, calls.stream().filter(call -> call.result() != null).count());
    }
}
