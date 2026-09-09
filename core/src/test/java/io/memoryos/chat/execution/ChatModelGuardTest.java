package io.memoryos.chat.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.embabel.agent.core.AgentProcess;
import com.embabel.agent.core.Budget;
import com.embabel.common.ai.model.LlmMetadata;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

class ChatModelGuardTest {
    private final ChatModel provider = mock(ChatModel.class);
    private final AgentProcess process = mock(AgentProcess.class);
    private final Budget budget = mock(Budget.class, RETURNS_DEEP_STUBS);
    private final ChatModelGuard guard = new ChatModelGuard(provider, process, mock(LlmMetadata.class), budget, 1, () -> {},
            request -> new Prompt(request.getInstructions(), assertInstanceOf(OpenAiChatOptions.class, request.getOptions()).mutate()
                    .toolCallbacks(List.of()).toolChoice(null).build()));
    private final Prompt prompt = new Prompt("Question", OpenAiChatOptions.builder().model("gpt-5-mini").toolChoice("auto").build());

    @BeforeEach
    void allowInference() { when(budget.earlyTerminationPolicy().shouldTerminate(process)).thenReturn(null); }

    @Test
    void lengthIsTerminalAndKnownUsageIsRecordedOnceWithToolsOff() {
        when(provider.stream(any(Prompt.class))).thenAnswer(call -> {
            var options = assertInstanceOf(OpenAiChatOptions.class, call.<Prompt>getArgument(0).getOptions());
            assertNotNull(options);
            assertEquals(List.of(), options.getToolCallbacks());
            assertNull(options.getToolChoice());
            return Flux.just(response("Partial but terminal", "length", 12));
        });
        var result = guard.stream(prompt).blockLast();
        assertNotNull(result);
        var generation = result.getResult();
        assertNotNull(generation);
        assertEquals("Partial but terminal", generation.getOutput().getText());
        assertTrue(guard.usageKnown());
        verify(process).recordLlmInvocation(any());
        assertEquals("CHAT_CYCLE_LIMIT", assertThrows(IllegalStateException.class, () -> guard.stream(prompt).blockLast()).getMessage());
        assertTrue(guard.usageKnown());
        verify(provider).stream(any(Prompt.class));
    }

    @Test
    void missingTerminalMetadataFailsAndUnknownUsageIsNotFabricated() {
        when(provider.stream(any(Prompt.class))).thenReturn(Flux.just(response("Partial", "", 0)));
        assertEquals("CHAT_INCOMPLETE_RESPONSE", assertThrows(IllegalStateException.class,
                () -> guard.stream(prompt).blockLast()).getMessage());
        assertFalse(guard.usageKnown());
        verify(process, never()).recordLlmInvocation(any());
    }

    @Test
    void usageReceivedBeforeProviderFailureIsStillRecorded() {
        when(provider.stream(any(Prompt.class))).thenReturn(Flux.concat(Flux.just(response("Partial", "", 12)),
                Flux.error(new IllegalStateException("provider failed"))));
        assertThrows(IllegalStateException.class, () -> guard.stream(prompt).blockLast());
        assertTrue(guard.usageKnown());
        verify(process).recordLlmInvocation(any());
    }

    private ChatResponse response(String text, String reason, int tokens) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text),
                ChatGenerationMetadata.builder().finishReason(reason).build())),
                ChatResponseMetadata.builder().usage(new DefaultUsage(tokens, tokens)).build());
    }
}
