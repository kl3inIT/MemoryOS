package io.memoryos.ai.openai;

import io.memoryos.ai.AiException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.embabel.agent.core.AgentProcess;
import com.embabel.agent.core.Budget;
import com.embabel.common.ai.model.LlmMetadata;
import io.memoryos.ai.ModelSettings;
import io.memoryos.ai.TurnFailureException;
import io.memoryos.chat.execution.ChatModelGuard;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;

class OpenAiRequestPolicyTest {
    @Test
    void textOnlyPolicyRemovesConvertedToolsAndBoundsEveryCallNotJustLastCycle() {
        var settings = new ModelSettings(1024, 128, new ModelSettings.Capabilities(true, false, false, false),
                Map.of(), null, TokenizerProfiles.HOSTED);
        var policy = OpenAiRequestPolicy.create(settings, TokenizerProfiles.hostedTokens());
        var model = mock(ChatModel.class);
        var process = mock(AgentProcess.class);
        var budget = mock(Budget.class, RETURNS_DEEP_STUBS);
        when(budget.earlyTerminationPolicy().shouldTerminate(process)).thenReturn(null);
        when(budget.getTokens()).thenReturn(100000);
        when(budget.getCost()).thenReturn(100.0);
        when(model.stream(any(Prompt.class))).thenAnswer(invocation -> {
            var actual = (OpenAiChatOptions) invocation.<Prompt>getArgument(0).getOptions();
            assertTrue(actual.getToolCallbacks().isEmpty());
            assertNull(actual.getToolChoice());
            assertNull(actual.getParallelToolCalls());
            assertNull(actual.getToolContext());
            assertNull(actual.getReasoningEffort());
            assertEquals(128, actual.getMaxTokens());
            assertNull(actual.getMaxCompletionTokens());
            return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("answer"),
                    ChatGenerationMetadata.builder().finishReason("stop").build()))));
        });
        var guard = new ChatModelGuard(model, process, mock(LlmMetadata.class), budget, 3, () -> {},
                policy, 896, OpenAiRequestPolicy::withoutTools);
        var options = OpenAiChatOptions.builder().model("any-name").maxTokens(1000).maxCompletionTokens(1000)
                .toolCallbacks(List.of(toolCallback())).toolChoice("required").parallelToolCalls(true)
                .toolContext(Map.of("unexpected", "context")).reasoningEffort("high").build();
        for (int cycle = 0; cycle < 3; cycle++)
            assertEquals("answer", guard.stream(new Prompt("Question", options)).blockLast().getResult().getOutput().getText());
        verify(model, times(3)).stream(any(Prompt.class));
        var tool = ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse("1", "lookup", "data"))).build();
        assertThrows(AiException.class, () -> policy.request(new Prompt(List.of(tool), options), 896));
        var unsupported = AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("1", "function", "lookup", "{}"))).build();
        assertThrows(AiException.class, () -> policy.response().accept(new ChatResponse(List.of(new Generation(unsupported)))));
    }

    @Test
    void hostedVisionCountsNativeMediaAndRejectsItForTextOnlyBindings() {
        var tokens = TokenizerProfiles.hostedTokens();
        var vision = OpenAiRequestPolicy.create(new ModelSettings(8192, 128,
                new ModelSettings.Capabilities(true, false, true, false), Map.of(), null, TokenizerProfiles.HOSTED), tokens);
        var media = new org.springframework.ai.content.Media(org.springframework.util.MimeTypeUtils.IMAGE_PNG,
                new org.springframework.core.io.ByteArrayResource(new byte[]{1, 2, 3}));
        var message = org.springframework.ai.chat.messages.UserMessage.builder().text("Inspect").media(List.of(media)).build();
        var options = OpenAiChatOptions.builder().model("vision").maxTokens(64).build();
        var prompt = new Prompt(List.of(message), options);
        int textBudget = vision.framing().applyAsInt(new Prompt("Inspect", options));
        assertThrows(IllegalStateException.class, () -> vision.request(prompt, textBudget));
        int imageBudget = textBudget + io.memoryos.chat.execution.ChatTurnSetup.IMAGE_INPUT_TOKENS;
        assertThrows(IllegalStateException.class, () -> vision.request(prompt, imageBudget - 1));
        var accepted = vision.request(prompt, imageBudget);
        assertEquals(List.of(media), assertInstanceOf(org.springframework.ai.chat.messages.UserMessage.class,
                accepted.getInstructions().getFirst()).getMedia());
        var textOnly = OpenAiRequestPolicy.create(new ModelSettings(8192, 128,
                new ModelSettings.Capabilities(true, false, false, false), Map.of(), null, TokenizerProfiles.HOSTED), tokens);
        assertThrows(AiException.class, () -> textOnly.request(prompt, imageBudget));
    }

    @Test
    void aModelWithoutAPublishedOutputLimitSendsNoCapUnlessTheRequestSetsOne() {
        // Onyx llm_loop passes no max_tokens: the provider's own default applies.
        var policy = OpenAiRequestPolicy.create(new ModelSettings(131_072, null,
                new ModelSettings.Capabilities(true, true, false, false), Map.of(), null, TokenizerProfiles.HOSTED),
                TokenizerProfiles.hostedTokens());
        var open = assertInstanceOf(OpenAiChatOptions.class, policy.request(new Prompt("Question",
                OpenAiChatOptions.builder().model("grok-4").build()), 1000).getOptions());
        assertNull(open.getMaxTokens());
        assertNull(open.getMaxCompletionTokens());
        var bounded = assertInstanceOf(OpenAiChatOptions.class, policy.request(new Prompt("Question",
                OpenAiChatOptions.builder().model("grok-4").maxTokens(2048).build()), 1000).getOptions());
        assertEquals(2048, bounded.getMaxTokens());
    }

    @Test
    void requiredToolChoiceAppliesOnlyToRequestsWithTools() {
        var plain = new Prompt("Question", OpenAiChatOptions.builder().model("gpt-5-mini").build());
        assertSame(plain, OpenAiRequestPolicy.requireTools(plain), "A tool-free research inference keeps its request");
        var withTools = new Prompt("Question", OpenAiChatOptions.builder().model("gpt-5-mini")
                .toolCallbacks(List.of(mock(ToolCallback.class))).toolChoice("auto").build());
        assertEquals("required", assertInstanceOf(OpenAiChatOptions.class, OpenAiRequestPolicy.requireTools(withTools).getOptions()).getToolChoice());
        assertEquals("CHAT_UNSUPPORTED_OPTIONS", assertThrows(TurnFailureException.class,
                () -> OpenAiRequestPolicy.requireTools(new Prompt("Question"))).code());
    }

    @Test
    void profileValidationRejectsUnknownProfilesLocally() {
        var meters = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        try {
            var adapter = new OpenAiProviderAdapter(io.micrometer.observation.ObservationRegistry.NOOP, meters);
            assertThrows(AiException.class, () -> adapter.validate("http://private/v1", "model",
                    new ModelSettings(1024, 128, new ModelSettings.Capabilities(true, false, false, false), Map.of(), null, "unknown")));
        } finally { meters.close(); }
    }

    private static ToolCallback toolCallback() {
        var definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn("lookup");
        when(definition.description()).thenReturn("Look a value up");
        when(definition.inputSchema()).thenReturn("{}");
        var callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(definition);
        return callback;
    }
}
