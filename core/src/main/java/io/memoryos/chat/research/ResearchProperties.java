package io.memoryos.chat.research;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Deep research limits. Defaults are the Onyx {@code 160f9b143} values: {@code dr_loop.py} (orchestrator cycles, forced report,
 * final report tokens, minimum context, user messages) and {@code research_agent.py} (agent cycles, forced report, timeout,
 * intermediate report tokens). A turn has no total deadline; these phases bound research themselves.
 */
@ConfigurationProperties("memoryos.chat.research")
public record ResearchProperties(@DefaultValue("8") int orchestratorCycles, @DefaultValue("4") int reasoningOrchestratorCycles,
                                 @DefaultValue("3") int parallelAgents, @DefaultValue("30m") Duration forceReportAfter,
                                 @DefaultValue("1024") int orchestratorMaxTokens, @DefaultValue("8") int agentCycles,
                                 @DefaultValue("12m") Duration agentForceReportAfter, @DefaultValue("30m") Duration agentTimeout,
                                 @DefaultValue("1000") int agentMaxTokens, @DefaultValue("10000") int intermediateReportTokens,
                                 @DefaultValue("20000") int finalReportTokens, @DefaultValue("50000") int minimumContextTokens,
                                 @DefaultValue("5") int userMessagesForContext) {
    public ResearchProperties {
        if (orchestratorCycles < 2 || orchestratorCycles > 20 || reasoningOrchestratorCycles < 2 || reasoningOrchestratorCycles > 20
                || parallelAgents < 1 || parallelAgents > 3 || invalid(forceReportAfter) || orchestratorMaxTokens < 256
                || agentCycles < 2 || agentCycles > 20 || invalid(agentForceReportAfter) || invalid(agentTimeout)
                || agentForceReportAfter.compareTo(agentTimeout) > 0 || agentMaxTokens < 256
                || intermediateReportTokens < 1000 || finalReportTokens < 1000 || minimumContextTokens < 8000
                || userMessagesForContext < 1 || userMessagesForContext > 50)
            throw new IllegalArgumentException("Invalid Chat research limits");
    }

    private static boolean invalid(Duration value) {
        return value == null || value.toMillis() <= 0 || value.compareTo(Duration.ofHours(2)) > 0;
    }

    /** Onyx gives reasoning models fewer orchestrator cycles because each cycle already reasons. */
    public int orchestratorCycles(boolean reasoning) {
        return reasoning ? reasoningOrchestratorCycles : orchestratorCycles;
    }
}
