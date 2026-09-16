package io.memoryos.chat.research;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResearchPromptsTest {
    @Test
    void orchestratorPromptIsFilledCompletelyAndInsertedValuesStayLiteral() {
        String prompt = ResearchPrompts.fill(ResearchPrompts.ORCHESTRATOR_PROMPT, Map.of(
                "current_datetime", "Tuesday September 15, 2026", "current_cycle_count", "1", "max_cycles", "8",
                "research_plan", "1. Revenue {current_datetime}", "internal_search_research_task_guidance",
                ResearchPrompts.INTERNAL_SEARCH_RESEARCH_TASK_GUIDANCE));
        assertTrue(prompt.contains("You have currently used 1 of 8 max research cycles."));
        assertTrue(prompt.endsWith("# Research Plan\n1. Revenue {current_datetime}"), "A plan is never re-read as a template");
        assertTrue(prompt.contains("in the argument to the research_agent. If necessary, clarify"));
        // Python line continuations join sentences without a line break.
        assertTrue(prompt.contains("high level research tasks. This delegates"));
    }

    @Test
    void missingValuesFailAndOnyxToolNamesBecomeMemoryOsToolNames() {
        assertThrows(IllegalArgumentException.class, () -> ResearchPrompts.fill(ResearchPrompts.FINAL_REPORT_PROMPT, Map.of()));
        String agent = ResearchPrompts.fill(ResearchPrompts.RESEARCH_AGENT_PROMPT, Map.of("available_tools", "search_knowledge, web_search, open_url",
                "current_datetime", "today", "current_cycle_count", "2", "max_research_cycles", "8", "optional_internal_search_tool_description", "\n\n" + ResearchPrompts.INTERNAL_SEARCH_GUIDANCE,
                "optional_web_search_tool_description", ResearchPrompts.WEB_SEARCH_TOOL_DESCRIPTION,
                "optional_open_url_tool_description", ResearchPrompts.OPEN_URLS_TOOL_DESCRIPTION));
        assertTrue(agent.contains("You are on cycle 2 of 8."));
        assertTrue(agent.contains("## search_knowledge\nUse the `search_knowledge` tool"));
        assertTrue(agent.contains("## open_url\nUse the `open_url` tool"));
        assertFalse(agent.contains("internal_search"));
        assertFalse(agent.contains("open_urls"));
        String reminder = ResearchPrompts.fill(ResearchPrompts.USER_ORCHESTRATOR_PROMPT, Map.of());
        assertTrue(reminder.contains("Call the think_tool between every call to the research_agent and before calling generate_report."));
    }

    @Test
    void propertiesDefaultToOnyxLimits() {
        var limits = new ResearchProperties(8, 4, 3, Duration.ofMinutes(30), 1024, 8, Duration.ofMinutes(12), Duration.ofMinutes(30),
                1000, 10000, 20000, 50000, 5);
        assertEquals(4, limits.orchestratorCycles(true));
        assertEquals(8, limits.orchestratorCycles(false));
        assertThrows(IllegalArgumentException.class, () -> new ResearchProperties(8, 4, 4, Duration.ofMinutes(30), 1024, 8,
                Duration.ofMinutes(12), Duration.ofMinutes(30), 1000, 10000, 20000, 50000, 5));
    }
}
