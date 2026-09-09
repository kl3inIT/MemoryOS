package io.memoryos.chat.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;

import com.embabel.agent.core.AgentProcess;
import com.embabel.agent.core.Budget;
import com.embabel.agent.spi.support.springai.SpringAiLlmService;
import com.embabel.common.ai.model.LlmOptions;
import com.embabel.common.ai.model.PricingModel;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

class ChatModelBindingTest {
    @Test
    void nonOpenAiOptionsAndMetadataSurvivePerTurnDecoration() {
        var provider = mock(ChatModel.class);
        var captured = new AtomicReference<Prompt>();
        when(provider.stream(any(Prompt.class))).thenAnswer(call -> {
            captured.set(call.getArgument(0));
            return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("answer"),
                    ChatGenerationMetadata.builder().finishReason("stop").build()))));
        });
        var pricing = PricingModel.usdPer1MTokens(1, 2);
        var configured = new SpringAiLlmService("another-model", "another-provider", provider,
                (_, name) -> ChatOptions.builder().model(name).temperature(0.25).build(),
                LocalDate.of(2025, 1, 1), List.of(), pricing);
        var binding = new ChatModelBinding(configured, prompt -> prompt);
        var process = mock(AgentProcess.class);
        var budget = mock(Budget.class, RETURNS_DEEP_STUBS);
        when(budget.earlyTerminationPolicy().shouldTerminate(process)).thenReturn(null);
        var guard = new ChatModelGuard(provider, process, configured, budget, 1, () -> {}, binding.finalRequest());
        var decorated = binding.withModel(guard);
        decorated.getChatModel().stream(new Prompt("question", decorated.convertOptions(LlmOptions.withModel("ignored")))).blockLast();
        var actualOptions = captured.get().getOptions();
        assertNotNull(actualOptions);
        assertEquals("another-model", actualOptions.getModel());
        assertEquals(0.25, actualOptions.getTemperature());
        assertSame(pricing, decorated.getPricingModel());
        assertEquals(configured.getKnowledgeCutoffDate(), decorated.getKnowledgeCutoffDate());
        assertSame(configured.getToolResponseContentAdapter(), decorated.getToolResponseContentAdapter());
        assertSame(configured.getNativeStructuredOutputConfigurer(), decorated.getNativeStructuredOutputConfigurer());
        assertSame(provider, configured.getChatModel());
    }
}
