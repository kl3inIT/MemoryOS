package io.memoryos.chat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.ObjectMapper;

/**
 * Per-turn research agent tree for history. Each agent's own steps and reasoning go through a
 * {@link ChatActivityRecorder}; excess agents or report text are dropped, never failing the turn.
 */
final class ChatResearchRecorder {
    /** Compact JSON budget below the 4 MiB {@code chat_message.research_agents} column check. */
    static final int BYTE_BUDGET = 3_500_000;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final LinkedHashMap<String, AgentState> agents = new LinkedHashMap<>();
    private int cycle = -1;

    private static final class AgentState {
        final String toolCallId;
        final int cycle;
        final int tabIndex;
        final long startedNanos = System.nanoTime();
        final ChatActivityRecorder activity = new ChatActivityRecorder();
        final StringBuilder report = new StringBuilder();
        @Nullable String task;
        ChatActivity.StepStatus status = ChatActivity.StepStatus.RUNNING;
        @Nullable Long durationMs;
        boolean truncated;
        List<ChatResearchEvent.Citation> citations = List.of();

        AgentState(String toolCallId, int cycle, int tabIndex) {
            this.toolCallId = toolCallId;
            this.cycle = cycle;
            this.tabIndex = tabIndex;
        }
    }

    synchronized void accept(ChatActivityEvent event) {
        switch (event) {
            case ChatToolEvent tool when tool.parentToolCallId() != null -> {
                var agent = agents.get(tool.parentToolCallId());
                if (agent != null) agent.activity.accept(new ChatToolEvent(tool.toolCallId(), tool.toolName(), tool.stage(), tool.source(),
                        tool.search(), tool.documents(), tool.durationMs()), 0);
            }
            case ChatToolEvent tool when tool.tabIndex() != null -> agentStep(tool);
            case ChatToolEvent ignored -> { }
            case ChatReasoningDelta delta -> {
                var agent = delta.parentToolCallId() == null ? null : agents.get(delta.parentToolCallId());
                if (agent != null) agent.activity.accept(new ChatReasoningDelta(delta.text()), 0);
            }
            case ChatResearchEvent research -> research(research);
        }
    }

    private void agentStep(ChatToolEvent tool) {
        var agent = agents.get(tool.toolCallId());
        if (agent == null) {
            if (tool.stage() != ChatToolEvent.Stage.STARTED) return;
            // Agents of one orchestrator cycle start in tab order, so tab 0 opens the next cycle.
            if (tool.tabIndex() == 0 || cycle < 0) cycle++;
            if (agents.size() >= ChatResearch.MAX_AGENTS) return;
            agents.put(tool.toolCallId(), new AgentState(tool.toolCallId(), cycle, tool.tabIndex()));
            return;
        }
        if ((tool.stage() == ChatToolEvent.Stage.COMPLETED || tool.stage() == ChatToolEvent.Stage.FAILED)
                && agent.status == ChatActivity.StepStatus.RUNNING) {
            agent.status = tool.stage() == ChatToolEvent.Stage.FAILED ? ChatActivity.StepStatus.FAILED : ChatActivity.StepStatus.COMPLETED;
            agent.durationMs = tool.durationMs() != null ? tool.durationMs() : elapsed(agent);
        }
    }

    private void research(ChatResearchEvent event) {
        var agent = event.toolCallId() == null ? null : agents.get(event.toolCallId());
        if (agent == null) return;
        switch (event.kind()) {
            case AGENT_START -> agent.task = event.text();
            case REPORT_DELTA -> {
                if (agent.truncated) return;
                String text = event.text();
                int remaining = ChatResearch.MAX_REPORT - agent.report.length();
                if (text.length() > remaining) {
                    int end = remaining > 0 && Character.isHighSurrogate(text.charAt(remaining - 1)) ? remaining - 1 : remaining;
                    agent.report.append(text, 0, end).append(ChatActivity.TRUNCATED);
                    agent.truncated = true;
                } else agent.report.append(text);
            }
            case REPORT_CITATIONS -> agent.citations = event.citations();
            default -> { }
        }
    }

    /** Agents still running at the terminal outcome were interrupted; they are recorded as failed. */
    synchronized List<ChatResearch.Agent> seal() {
        var sealed = new ArrayList<ChatResearch.Agent>();
        for (var agent : agents.values()) {
            boolean running = agent.status == ChatActivity.StepStatus.RUNNING;
            sealed.add(new ChatResearch.Agent(agent.toolCallId, agent.cycle, agent.tabIndex, agent.task,
                    running ? ChatActivity.StepStatus.FAILED : agent.status, running ? Long.valueOf(elapsed(agent)) : agent.durationMs,
                    agent.report.isEmpty() ? null : agent.report.toString(), agent.citations, agent.activity.seal()));
        }
        // Reports are the bulk of the tree: the latest agents lose theirs first until the history fits.
        for (int index = sealed.size() - 1; size(sealed) > BYTE_BUDGET && index >= 0; index--) {
            var agent = sealed.get(index);
            sealed.set(index, new ChatResearch.Agent(agent.toolCallId(), agent.cycle(), agent.tabIndex(), agent.task(), agent.status(),
                    agent.durationMs(), null, agent.citations(), ChatActivity.EMPTY));
        }
        return List.copyOf(sealed);
    }

    static int size(List<ChatResearch.Agent> agents) {
        return JSON.writeValueAsString(agents).getBytes(StandardCharsets.UTF_8).length;
    }

    private static long elapsed(AgentState agent) {
        return Math.max(0, (System.nanoTime() - agent.startedNanos) / 1_000_000);
    }
}
