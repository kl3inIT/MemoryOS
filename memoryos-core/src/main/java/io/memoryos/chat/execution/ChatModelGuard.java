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
    private boolean grounded;
    private String topicRules = "";
    private String mcpNotice = "";

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

    public void evidenceAvailable(BooleanSupplier value) { this.hasEvidence = value; }

    /** MEM-195: every inference of this turn carries the answer-from-documents instruction. */
    public void grounded(boolean value) { grounded = value; }

    /** Blocked topics this turn's answer model applies itself, behind the guardrail check (MEM-208). */
    public void topicRules(String value) { topicRules = value; }

    /** The turn's apps the model cannot use and who can fix each (MEM-273); empty when there are none. */
    public void mcpNotice(String value) { mcpNotice = value; }

    @Override
    protected Prompt guide(Prompt original, boolean lastCycle) {
        return ChatPrompts.forInference(original, hasEvidence.getAsBoolean(), lastCycle, webSiteFilter, grounded,
                topicRules, mcpNotice);
    }

    @Override
    protected void checkHelperActive() {
        SearchTasks.checkNativeActive();
    }
}
