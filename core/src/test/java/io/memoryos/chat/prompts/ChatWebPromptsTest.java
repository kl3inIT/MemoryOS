package io.memoryos.chat.prompts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

class ChatWebPromptsTest {
    @Test void siteGuidanceMatchesSelectedProviderAndNeverAdvertisesDisabledSearch() {
        var prompt = prompt(Set.of("web_search", "open_url"), null);
        assertTrue(ChatPrompts.forInference(prompt, false, false, false).toString().contains("does not support the site:"));
        assertTrue(ChatPrompts.forInference(prompt, false, false, true).toString().contains("Use the site: operator"));
        assertFalse(ChatPrompts.forInference(prompt(Set.of("open_url"), null), false, false, false).toString().contains("site:"));
    }
    private Prompt prompt(Set<String> tools, String lastTool) {
        var messages = new ArrayList<Message>();
        messages.add(new SystemMessage("Preserve my Persona instructions."));
        messages.add(new UserMessage("Check this information."));
        if (lastTool != null) messages.add(ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse("tool-id", lastTool, "Evidence [1]"))).build());
        var callbacks = tools.stream().map(name -> {
            var callback = mock(ToolCallback.class);
            var definition = mock(ToolDefinition.class);
            when(definition.name()).thenReturn(name);
            when(callback.getToolDefinition()).thenReturn(definition);
            return callback;
        }).toList();
        return new Prompt(messages, OpenAiChatOptions.builder().toolCallbacks(callbacks).build());
    }
    @Test void agentTaskPromptIsTheFinalReminderAndDateAwarenessIsOptional() {
        var guided = ChatPrompts.forInference(prompt(Set.of(), null), false, false, true, "Always answer with the KPI month.");
        var last = guided.getInstructions().getLast().getText();
        assertTrue(last.startsWith("<system-reminder>"));
        assertTrue(last.contains("Always answer with the KPI month."));
        var now = Instant.parse("2026-09-17T00:00:00Z");
        assertTrue(ChatPrompts.resolve(ChatPrompts.DEFAULT_SYSTEM, false, now, null, true).contains("2026-09-17T00:00:00Z"));
        var unaware = ChatPrompts.resolve(ChatPrompts.DEFAULT_SYSTEM, false, now, null, false);
        assertFalse(unaware.contains("CURRENT_DATETIME"));
        assertFalse(unaware.contains("The current date is"));
    }

    @Test void webOnlyDoesNotAdvertiseInternalSearchAndPreservesOriginalPrompt() {
        var original = prompt(Set.of("web_search", "open_url"), null);
        var guided = ChatPrompts.forInference(original, false, false);
        assertTrue(guided.toString().contains("## web_search"));
        assertTrue(guided.toString().contains("## open_url"));
        assertFalse(guided.toString().contains("search_knowledge"));
        assertTrue(guided.toString().contains("Preserve my Persona instructions."));
        assertEquals(2, original.getInstructions().size());
        assertSame(original.getOptions(), guided.getOptions());
    }
    @Test void disabledWebAndNoToolsNeverAdvertiseUnavailableTools() {
        String internal = ChatPrompts.forInference(prompt(Set.of("search_knowledge"), null), false, false).toString();
        assertTrue(internal.contains("## search_knowledge"));
        assertFalse(internal.contains("web_search"));
        assertFalse(internal.contains("open_url"));
        var none = prompt(Set.of(), null);
        assertSame(none, ChatPrompts.forInference(none, false, false));
    }
    @Test void combinedGuidanceExplainsPublicVersusInternalAndFreshness() {
        String text = ChatPrompts.forInference(prompt(Set.of("search_knowledge", "web_search", "open_url"), null), false, false).toString();
        assertTrue(text.contains("team/internal information"));
        assertTrue(text.contains("rapidly changing"));
        assertTrue(text.contains("primary sources"));
        assertTrue(text.contains("specific supplied URL"));
    }
    @Test void explicitInternalDocumentRequestsRequireGroundingOnlyWhenKnowledgeSearchIsCallable() {
        String internal = ChatPrompts.forInference(prompt(Set.of("search_knowledge"), null), false, false).toString();
        assertTrue(internal.contains("MUST call search_knowledge before answering"));
        assertTrue(internal.contains("never substitute general"));
        assertTrue(internal.contains("model knowledge for the requested documents"));

        String combined = ChatPrompts.forInference(
                prompt(Set.of("search_knowledge", "web_search", "open_url"), null), false, false).toString();
        assertTrue(combined.contains("names an internal"));
        assertTrue(combined.contains("source, connector, provider, or document"));

        String webOnly = ChatPrompts.forInference(prompt(Set.of("web_search", "open_url"), null), false, false).toString();
        assertFalse(webOnly.contains("MUST call search_knowledge before answering"));
        assertFalse(webOnly.contains("never substitute general"));
    }
    @Test void reminderRequiresRecentWebResultAvailableReaderAndAnotherToolCycle() {
        var original = prompt(Set.of("web_search", "open_url"), "web_search");
        assertTrue(Objects.requireNonNull(ChatPrompts.forInference(original, true, false).getInstructions().getLast().getText()).contains("After web_search"));
        assertFalse(ChatPrompts.forInference(original, true, true).toString().contains("After web_search"));
        assertFalse(ChatPrompts.forInference(original, true, true).toString().contains("## open_url"));
        assertTrue(ChatPrompts.forInference(original, true, true).toString().contains("last cycle"));
        assertFalse(ChatPrompts.forInference(prompt(Set.of("web_search"), "web_search"), true, false).toString().contains("After web_search"));
        assertFalse(ChatPrompts.forInference(prompt(Set.of("open_url"), "open_url"), true, false).toString().contains("After web_search"));
    }
    @Test void historicalSearchBeforeANewUserQuestionDoesNotTriggerReminder() {
        var original = prompt(Set.of("open_url"), "web_search");
        var messages = new ArrayList<>(original.getInstructions());
        messages.add(new UserMessage("Different question"));
        assertFalse(ChatPrompts.forInference(new Prompt(messages, original.getOptions()), true, false).toString().contains("After web_search"));
    }

    @Test void everyCallableToolDescribesItselfUnderExactlyOneHeading() {
        String attachments = ChatPrompts.forInference(prompt(Set.of("search_files", "read_file"), null), false, false).toString();
        assertTrue(attachments.contains("## search_files and read_file"));
        assertFalse(attachments.contains("web_search"));
        assertEquals(1, headings(attachments));
        String all = ChatPrompts.forInference(prompt(Set.of("search_knowledge", "web_search", "open_url",
                "search_files", "read_file", "run_python", "generate_image", "edit_image"), null), false, false).toString();
        for (String block : List.of("## search_knowledge", "## web_search", "## open_url",
                "## search_files and read_file", "## run_python", "## generate_image", "## edit_image"))
            assertTrue(all.contains(block), block);
        assertEquals(1, headings(all));
    }

    @Test void runPythonGuidanceKeepsOnyxTextAndAppearsOnlyWithTheTool() {
        String with = ChatPrompts.forInference(prompt(Set.of("run_python"), null), false, false).toString();
        assertTrue(with.contains("## run_python"));
        assertTrue(with.contains("each call to this tool runs in a fresh, stateless sandbox"));
        assertTrue(with.contains("CPU time is limited to 30 seconds per run."));
        assertTrue(with.contains("run `recalc-xlsx` via subprocess"));
        assertEquals(1, headings(with));
        assertFalse(ChatPrompts.forInference(prompt(Set.of("read_file"), null), false, false).toString().contains("run_python"));
    }

    private static int headings(String text) {
        return text.split("# Tools", -1).length - 1;
    }
}
