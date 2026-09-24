package io.memoryos.ai;

import com.embabel.agent.core.Budget;
import com.embabel.common.ai.model.PricingModel;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.metadata.Usage;

/**
 * Token and cost allowance admitted for one turn. Deep research guards share one ledger, so parallel research agents
 * cannot each spend the whole turn budget. Admission bounds only: Embabel remains the invocation, usage and cost ledger.
 */
public final class ModelAdmissionLedger {
    private long admittedTokens;
    private double admittedCost;

    record Reservation(long tokens, double cost) {}

    /** Reserve before IO so concurrent callers cannot all spend the same remaining budget. */
    synchronized Reservation reserve(Budget budget, @Nullable PricingModel pricing, int input, int output) {
        long tokens = (long) input + output;
        double cost = pricing == null ? 0 : pricing.costOf(input, output);
        if (admittedTokens + tokens > budget.getTokens() || admittedCost + cost > budget.getCost())
            throw new IllegalStateException("CHAT_BUDGET_EXCEEDED");
        admittedTokens += tokens;
        admittedCost += cost;
        return new Reservation(tokens, cost);
    }

    synchronized void settle(Reservation reservation, @Nullable PricingModel pricing, Usage usage) {
        // Unknown/failed requests retain their allowance: never turn unreported usage into free budget.
        if (usage.getTotalTokens() <= 0) return;
        admittedTokens += usage.getTotalTokens() - reservation.tokens();
        if (pricing != null) admittedCost += pricing.costOf(usage.getPromptTokens(), usage.getCompletionTokens()) - reservation.cost();
    }
}
