package io.memoryos.api.chat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.embabel.agent.core.AgentProcess;
import com.embabel.agent.core.Budget;
import com.embabel.common.ai.model.LlmMetadata;
import io.memoryos.chat.ChatException;
import io.memoryos.chat.catalog.ModelSettings;
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
import reactor.core.publisher.Flux;

class OpenAiChatRequestPolicyTest {
    @Test
    void textOnlyPolicyRemovesConvertedToolsAndBoundsEveryCallNotJustLastCycle() {
        var settings = new ModelSettings(1024, 128, new ModelSettings.Capabilities(true, false, false, false),
                Map.of(), null, ChatTokenizerProfiles.SMOL);
        try (var profiles = new ChatTokenizerProfiles(); var lease = profiles.acquire(ChatTokenizerProfiles.SMOL)) {
            var policy = OpenAiChatRequestPolicy.create(settings, lease.tokens());
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
                    policy, 896, OpenAiChatRequestPolicy::withoutTools);
            var options = OpenAiChatOptions.builder().model("any-name").maxTokens(1000).maxCompletionTokens(1000)
                    .toolCallbacks(List.of(mock(ToolCallback.class))).toolChoice("required").parallelToolCalls(true)
                    .toolContext(Map.of("unexpected", "context")).reasoningEffort("high").build();
            for (int cycle = 0; cycle < 3; cycle++)
                assertEquals("answer", guard.stream(new Prompt("Question", options)).blockLast().getResult().getOutput().getText());
            verify(model, times(3)).stream(any(Prompt.class));
            var tool = ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse("1", "lookup", "data"))).build();
            assertThrows(ChatException.class, () -> policy.request(new Prompt(List.of(tool), options), 896));
            var unsupported = AssistantMessage.builder().content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall("1", "function", "lookup", "{}"))).build();
            assertThrows(ChatException.class, () -> policy.response().accept(new ChatResponse(List.of(new Generation(unsupported)))));
        }
    }

    @Test
    void profileValidationRejectsUnknownAndContradictoryCapabilitiesLocally() {
        var meters = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        try (var adapter = new OpenAiChatProviderAdapter(io.micrometer.observation.ObservationRegistry.NOOP, meters)) {
            assertThrows(ChatException.class, () -> adapter.validate("http://private/v1", "model",
                    new ModelSettings(1024, 128, new ModelSettings.Capabilities(true, false, false, false), Map.of(), null, "unknown")));
            assertThrows(ChatException.class, () -> adapter.validate("http://private/v1", "model",
                    new ModelSettings(1024, 128, new ModelSettings.Capabilities(true, true, false, false), Map.of(), null, ChatTokenizerProfiles.SMOL)));
        } finally { meters.close(); }
    }
}
