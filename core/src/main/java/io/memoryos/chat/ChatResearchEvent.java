package io.memoryos.chat;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Deep research progress, as the Onyx packets {@code DeepResearchPlanDelta}, {@code TopLevelBranching},
 * {@code ResearchAgentStart}, {@code IntermediateReportDelta} and {@code IntermediateReportCitedDocs}.
 * An intermediate report streams with the agent's own citation numbers; its citations event maps each of them to the
 * turn source it was merged into, so the turn's sources stay one sequence.
 */
public record ChatResearchEvent(Kind kind, @Nullable String toolCallId, @Nullable Integer tabIndex, @Nullable String text,
        @Nullable Integer branches, List<Citation> citations) implements ChatActivityEvent {
    /** Bounded like a tool argument summary; Onyx asks for a 1-2 sentence task. */
    public static final int MAX_TASK = 4000;

    public enum Kind { PLAN_DELTA, BRANCHING, AGENT_START, REPORT_DELTA, REPORT_CITATIONS }

    /** {@code marker} is the agent's citation number, {@code citationId} the merged turn source. */
    public record Citation(int marker, int citationId) {
        public Citation {
            if (marker < 1 || citationId < 1) throw new IllegalArgumentException("Invalid research citation");
        }
    }

    public static ChatResearchEvent plan(String text) {
        return new ChatResearchEvent(Kind.PLAN_DELTA, null, null, text, null, List.of());
    }

    public static ChatResearchEvent branching(int branches) {
        return new ChatResearchEvent(Kind.BRANCHING, null, null, null, branches, List.of());
    }

    public static ChatResearchEvent agent(String toolCallId, int tabIndex, String task) {
        return new ChatResearchEvent(Kind.AGENT_START, toolCallId, tabIndex, task, null, List.of());
    }

    public static ChatResearchEvent report(String toolCallId, String text) {
        return new ChatResearchEvent(Kind.REPORT_DELTA, toolCallId, null, text, null, List.of());
    }

    public static ChatResearchEvent citations(String toolCallId, List<Citation> citations) {
        return new ChatResearchEvent(Kind.REPORT_CITATIONS, toolCallId, null, null, null, citations);
    }

    public ChatResearchEvent {
        citations = List.copyOf(citations);
        boolean agentScoped = kind == Kind.AGENT_START || kind == Kind.REPORT_DELTA || kind == Kind.REPORT_CITATIONS;
        boolean textual = kind == Kind.PLAN_DELTA || kind == Kind.AGENT_START || kind == Kind.REPORT_DELTA;
        if (kind == null || agentScoped != (toolCallId != null) || toolCallId != null && !ChatToolEvent.validId(toolCallId)
                || (kind == Kind.AGENT_START) != (tabIndex != null) || tabIndex != null && (tabIndex < 0 || tabIndex >= ChatToolEvent.MAX_TABS)
                || textual != (text != null) || text != null && (text.isEmpty() || text.length() > (kind == Kind.AGENT_START ? MAX_TASK : ChatActivity.MAX_REASONING))
                || (kind == Kind.BRANCHING) != (branches != null) || branches != null && (branches < 2 || branches > ChatToolEvent.MAX_TABS)
                || kind != Kind.REPORT_CITATIONS && !citations.isEmpty())
            throw new IllegalArgumentException("Invalid research event");
    }
}
