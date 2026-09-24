package io.memoryos.chat.catalog.openai;

import static org.junit.jupiter.api.Assertions.*;

import com.embabel.common.ai.model.LlmOptions;
import io.memoryos.chat.ChatSampling;
import io.memoryos.chat.catalog.ModelSettings;
import io.memoryos.chat.preferences.ReasoningEffort;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatOptions;

/** The order a turn's creativity and reasoning level settle in, as Onyx settles its own. */
class ChatSamplingTest {
    private static final ModelSettings.Capabilities REASONING =
            new ModelSettings.Capabilities(true, true, false, true);
    private static final ModelSettings.Capabilities PLAIN =
            new ModelSettings.Capabilities(true, true, false, false);

    private static ModelSettings settings(ModelSettings.Capabilities capabilities, Map<String, Object> options) {
        return new ModelSettings(200_000, 32_000, capabilities, options, null, "hosted");
    }

    private static OpenAiChatOptions apply(ModelSettings settings, ChatSampling sampling, LlmOptions requested) {
        var converted = OpenAiChatRequestPolicy.withSampling(
                OpenAiChatOptions.builder().model("m").build(), requested, sampling, settings);
        return (OpenAiChatOptions) converted;
    }

    @Test
    void aPinnedLevelOutranksTheModelConfigurationAndAMemberDefaultDoesNot() {
        var configured = settings(REASONING, Map.of("reasoningEffort", "low"));
        assertEquals("high", apply(configured,
                new ChatSampling(null, ReasoningEffort.HIGH, true), new LlmOptions()).getReasoningEffort());
        // The member's own default only reaches a model whose configuration names no level.
        assertNull(apply(configured, new ChatSampling(null, ReasoningEffort.HIGH, false), new LlmOptions())
                .getReasoningEffort());
        assertEquals("medium", apply(settings(REASONING, Map.of()),
                new ChatSampling(null, ReasoningEffort.MEDIUM, false), new LlmOptions()).getReasoningEffort());
    }

    @Test
    void aModelThatDoesNotReasonTakesNoLevelAndAReasoningModelTakesNoCreativity() {
        assertNull(apply(settings(PLAIN, Map.of()),
                new ChatSampling(null, ReasoningEffort.HIGH, true), new LlmOptions()).getReasoningEffort());
        assertNull(apply(settings(REASONING, Map.of()),
                new ChatSampling(0.4, null, false), new LlmOptions()).getTemperature());
        assertEquals(0.4, apply(settings(PLAIN, Map.of()),
                new ChatSampling(0.4, null, false), new LlmOptions()).getTemperature());
        // A creativity the administrator pinned on the model stays.
        assertNull(apply(settings(PLAIN, Map.of("temperature", 0.1)),
                new ChatSampling(0.4, null, false), new LlmOptions()).getTemperature());
    }

    @Test
    void aHelperCallKeepsTheLowEffortItsConverterGaveIt() {
        var helper = new LlmOptions().withoutThinking();
        assertNull(apply(settings(REASONING, Map.of()),
                new ChatSampling(null, ReasoningEffort.HIGH, true), helper).getReasoningEffort());
    }
}
