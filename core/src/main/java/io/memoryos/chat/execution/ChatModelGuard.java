package io.memoryos.chat.execution;

import com.embabel.agent.core.AgentProcess;
import com.embabel.agent.core.Budget;
import com.embabel.common.ai.model.LlmMetadata;
import io.memoryos.ai.ModelAdmissionLedger;
import io.memoryos.ai.ModelRequestPolicy;
import io.memoryos.ai.ModelGuard;
import io.memoryos.chat.prompts.ChatPrompts;
import io.memoryos.retrieval.SearchTasks;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.NullMarked;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

/** Per-turn integration of native streaming with accounting and final-cycle policy, guided by Chat's prompts. */
@NullMarked
public final class ChatModelGuard extends ModelGuard {
    private BooleanSupplier hasEvidence = () -> false;
    private boolean webSiteFilter = true;
    private String taskPrompt = "";

    public ChatModelGuard(ChatModel delegate, AgentProcess process, LlmMetadata model, Budget budget,
            int cycles, Runnable checkActive, ModelRequestPolicy policy, int inputLimit, UnaryOperator<Prompt> finalRequest) {
        super(delegate, process, model, budget, cycles, checkActive, policy, inputLimit, finalRequest);
    }

    public ChatModelGuard(ChatModel delegate, AgentProcess process, LlmMetadata model, Budget budget,
            int cycles, Runnable checkActive, ModelRequestPolicy policy, int inputLimit, UnaryOperator<Prompt> finalRequest,
            ModelAdmissionLedger ledger) {
        super(delegate, process, model, budget, cycles, checkActive, policy, inputLimit, finalRequest, ledger);
    }

    public void webSiteFilter(boolean supported) { webSiteFilter = supported; }

    /** Agent task prompt repeated as the final reminder of every inference (Onyx {@code task_prompt}). */
    public void taskPrompt(String value) { taskPrompt = value == null ? "" : value; }

    public void evidenceAvailable(BooleanSupplier value) { this.hasEvidence = value; }

    @Override
    protected Prompt guide(Prompt original, boolean lastCycle) {
        return ChatPrompts.forInference(original, hasEvidence.getAsBoolean(), lastCycle, webSiteFilter, taskPrompt);
    }

    @Override
    protected void checkHelperActive() {
        SearchTasks.checkNativeActive();
    }
}
