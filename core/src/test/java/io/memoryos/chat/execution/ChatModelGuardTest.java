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
import com.embabel.agent.core.EarlyTermination;
import com.embabel.common.ai.model.LlmMetadata;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
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
    private final ChatRequestPolicy policy = ChatRequestPolicy.hosted(new JTokkitTokenCountEstimator(), p -> p);
    private final ChatModelGuard guard = new ChatModelGuard(provider, process, mock(LlmMetadata.class), budget, 1, () -> {}, policy, 32000,
            request -> new Prompt(request.getInstructions(), assertInstanceOf(OpenAiChatOptions.class, request.getOptions()).mutate()
                    .toolCallbacks(List.of()).toolChoice(null).build()));
    private final Prompt prompt = new Prompt("Question", OpenAiChatOptions.builder().model("gpt-5-mini").toolChoice("auto").build());

    @BeforeEach
    void allowInference() {
        when(budget.earlyTerminationPolicy().shouldTerminate(process)).thenReturn(null);
        when(budget.getTokens()).thenReturn(100000);
        when(budget.getCost()).thenReturn(100.0);
    }

    @Test
    void concurrentHelpersReserveBudgetBeforeIoAndReleaseOnlyReportedAllowance() throws Exception {
        when(budget.getTokens()).thenReturn(300);
        guard.outputLimit(200);
        guard.synchronousLimit(2);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        when(provider.call(any(Prompt.class))).thenAnswer(_ -> {
            entered.countDown(); assertTrue(release.await(3, java.util.concurrent.TimeUnit.SECONDS));
            return response("{}", "stop", 7);
        });
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> guard.call(prompt));
            try {
                assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS));
                assertEquals("CHAT_BUDGET_EXCEEDED", assertThrows(IllegalStateException.class, () -> guard.call(prompt)).getMessage());
                verify(provider).call(any(Prompt.class));
            } finally { release.countDown(); }
            first.get(3, java.util.concurrent.TimeUnit.SECONDS);
        }
        // The rejected call spent neither a model invocation nor its allowance.
        guard.call(prompt);
        verify(provider, org.mockito.Mockito.times(2)).call(any(Prompt.class));
        verify(process, never()).recordLlmInvocation(any());
    }

    @Test
    void lengthIsTerminalAndKnownUsageIsRecordedOnceWithToolsOff() {
        when(provider.stream(any(Prompt.class))).thenAnswer(call -> {
            assertTrue(call.<Prompt>getArgument(0).getContents().contains("no longer have any tool calls available"));
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
    void citationReminderTracksAvailableEvidenceWithoutMutatingConversationMessages() {
        var evidence = new java.util.concurrent.atomic.AtomicBoolean();
        var twoCycles = new ChatModelGuard(provider, process, mock(LlmMetadata.class), budget, 3, () -> {}, policy, 32000, request -> request);
        twoCycles.evidenceAvailable(evidence::get);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        when(provider.stream(any(Prompt.class))).thenAnswer(call -> {
            Prompt sent = call.getArgument(0);
            assertEquals(calls.incrementAndGet() > 1, sent.getContents().contains("cite relevant statements INLINE"));
            return Flux.just(response("Answer", "stop", 12));
        });
        twoCycles.stream(prompt).blockLast();
        evidence.set(true);
        twoCycles.stream(prompt).blockLast();
        assertEquals(1, prompt.getInstructions().size());
        assertEquals("Question", prompt.getContents());
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

    @Test
    void synchronousUsageCompletenessIsTrackedButOnlyNativeCallerRecordsIt() {
        when(provider.call(any(Prompt.class))).thenReturn(response("{}", "stop", 7));
        guard.call(prompt);
        verify(process, never()).recordLlmInvocation(any());
        when(provider.stream(any(Prompt.class))).thenReturn(Flux.just(response("Answer", "stop", 12)));
        guard.stream(prompt).blockLast();
        assertTrue(guard.usageKnown());
        verify(process).recordLlmInvocation(any());
    }

    @Test
    void unknownTypedUsageKeepsWholeTurnAccountingUnknownAndBudgetStopsAllInference() {
        when(provider.call(any(Prompt.class))).thenReturn(response("{}", "stop", 0));
        guard.call(prompt);
        when(provider.stream(any(Prompt.class))).thenReturn(Flux.just(response("Answer", "stop", 12)));
        guard.stream(prompt).blockLast();
        assertFalse(guard.usageKnown());
        when(budget.earlyTerminationPolicy().shouldTerminate(process)).thenReturn(mock(EarlyTermination.class));
        assertEquals("CHAT_BUDGET_EXCEEDED", assertThrows(IllegalStateException.class, guard::checkActive).getMessage());
        assertThrows(IllegalStateException.class, () -> guard.call(prompt));
        assertThrows(IllegalStateException.class, () -> guard.stream(prompt).blockLast());
        verify(provider).call(any(Prompt.class));
        verify(provider).stream(any(Prompt.class));
    }

    @Test
    void toolResponseContentCountsAgainstContextBeforeProviderInference() {
        var guarded = new ChatModelGuard(provider, process, mock(LlmMetadata.class), budget, 1, () -> {}, policy, 128, p -> p);
        var response = ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse(
                "tool-1", "searchKnowledge", "private document ".repeat(1000)))).build();
        var request = new Prompt(List.of(response), prompt.getOptions());
        assertEquals("CHAT_CONTEXT_LIMIT", assertThrows(IllegalStateException.class, () -> guarded.stream(request).blockLast()).getMessage());
        verify(provider, never()).stream(any(Prompt.class));
    }

    @Test
    void budgetPolicyRejectsExpandedContinuationBeforeAnotherProviderCall() {
        var tokens = new org.springframework.ai.tokenizer.JTokkitTokenCountEstimator(com.knuddels.jtokkit.api.EncodingType.O200K_BASE);
        var policy = ChatRequestPolicy.hosted(tokens, p -> p);
        var guarded = new ChatModelGuard(provider, process, mock(LlmMetadata.class), budget, 3, () -> {},
                policy, 64, p -> p);
        when(provider.stream(any(Prompt.class))).thenReturn(Flux.just(response("first", "stop", 12)));
        assertEquals("first", guarded.stream(prompt).blockLast().getResult().getOutput().getText());
        assertEquals("CHAT_CONTEXT_LIMIT", assertThrows(IllegalStateException.class,
                () -> guarded.stream(new Prompt("Expanded tool result ".repeat(200))).blockLast()).getMessage());
        assertTrue(guarded.usageKnown(), "A local rejection must not erase usage from completed native calls");
        verify(provider).stream(any(Prompt.class));
    }

    private ChatResponse response(String text, String reason, int tokens) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text),
                ChatGenerationMetadata.builder().finishReason(reason).build())),
                ChatResponseMetadata.builder().usage(new DefaultUsage(tokens, tokens)).build());
    }
}
