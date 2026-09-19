package io.memoryos.chat.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import com.embabel.agent.spi.support.springai.SpringAiLlmService;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class ChatModelBindingLimitsTest {
    private static ChatModelBinding binding(int contextWindow, @Nullable Integer maxOutputTokens) {
        return new ChatModelBinding(mock(SpringAiLlmService.class), UnaryOperator.identity(), mock(ChatRequestPolicy.class),
                contextWindow, maxOutputTokens, true, false);
    }

    @Test
    void theInputBudgetIsTheModelWindowLessTheReserveHeldFivePercentBelowAsOnyx() {
        // gpt-5.6-luna: 922,000 input, 128,000 output; Onyx llm_loop keeps GEN_AI_INPUT_TOKEN_SAFETY_MARGIN 5% back.
        assertEquals((int) ((922_000 - 1024) * 0.95), binding(922_000, 128_000).inputLimit(1024));
        // A model with a smaller output limit than the reserve reserves only its own limit.
        assertEquals((int) ((8192 - 512) * 0.95), binding(8192, 512).inputLimit(1024));
        assertEquals((int) ((32_000 - 1024) * 0.95), binding(32_000, null).inputLimit(1024));
    }

    @Test
    void boundedWorkUsesTheModelLimitElseOnyxsFallbackWithinAQuarterOfTheWindow() {
        assertEquals(128_000, binding(922_000, 128_000).outputBound());
        assertEquals(32_000, binding(1_000_000, null).outputBound());
        assertEquals(8_000, binding(32_000, null).outputBound());
    }
}
