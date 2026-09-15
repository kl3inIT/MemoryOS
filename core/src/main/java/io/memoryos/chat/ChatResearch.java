package io.memoryos.chat;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Deep research state of an assistant message. {@code clarification} mirrors Onyx {@code chat_message.is_clarification}:
 * the next research turn skips clarification. The plan and the research agents are kept so a reload shows them; Onyx
 * persists agents as {@code tool_call} rows and does not persist the plan.
 */
public record ChatResearch(boolean clarification, @Nullable String plan, List<Agent> agents) {
    /** Stored plan characters, below the {@code chat_message.research_plan} column check. */
    public static final int MAX_PLAN = 100_000;
    /** Stored characters of one intermediate report; Onyx asks for at most 10,000 tokens. */
    public static final int MAX_REPORT = 100_000;
    /** Onyx runs at most three agents in each of at most 20 configurable orchestrator cycles. */
    public static final int MAX_AGENTS = 64;
    public static final ChatResearch EMPTY = new ChatResearch(false, null, List.of());

    /**
     * One research agent call: its orchestrator cycle and tab, task, outcome, streamed intermediate report with the
     * agent's own citation numbers, the mapping of those numbers to the answer's sources, and its own tool steps.
     */
    public record Agent(String toolCallId, int cycle, int tabIndex, @Nullable String task, ChatActivity.StepStatus status,
                        @Nullable Long durationMs, @Nullable String report, List<ChatResearchEvent.Citation> citations,
                        ChatActivity activity) {
        public Agent {
            citations = List.copyOf(citations == null ? List.of() : citations);
            if (activity == null) activity = ChatActivity.EMPTY;
            if (!ChatToolEvent.validId(toolCallId) || cycle < 0 || tabIndex < 0 || tabIndex >= ChatToolEvent.MAX_TABS || status == null
                    || task != null && (task.isEmpty() || task.length() > ChatResearchEvent.MAX_TASK) || durationMs != null && durationMs < 0
                    || report != null && (report.isEmpty() || report.length() > MAX_REPORT + ChatActivity.TRUNCATED.length()))
                throw new IllegalArgumentException("Invalid research agent");
        }
    }

    public ChatResearch(boolean clarification, @Nullable String plan) {
        this(clarification, plan, List.of());
    }

    public ChatResearch {
        agents = List.copyOf(agents == null ? List.of() : agents);
        if (plan != null && (plan.isEmpty() || plan.length() > MAX_PLAN) || agents.size() > MAX_AGENTS)
            throw new IllegalArgumentException("Invalid research state");
    }
}
