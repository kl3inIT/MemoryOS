package io.memoryos.chat.execution;

import com.embabel.agent.api.common.Asyncer;
import com.embabel.agent.api.common.ExecutingOperationContext;
import com.embabel.agent.core.AgentProcessRepository;
import com.embabel.agent.spi.support.ExecutorAsyncer;
import io.memoryos.ai.ModelCalls;
import io.memoryos.chat.ChatExecutionProperties;
import io.memoryos.retrieval.SearchTasks;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

/**
 * How a process that runs Chat turns calls a model outside a turn's own loop: the native Asyncer the agent platform
 * uses for a tool's helper calls, and single model calls such as the guardrail check. Both come with the turn
 * runtime, so the API and {@code memoryos-channels} have them alike (MEM-246).
 */
@Configuration(proxyBeanMethods = false)
class ChatModelCallsConfiguration {
    @Bean
    @Primary
    Asyncer chatNativeAsyncer(@Qualifier("chatTaskExecutor") SimpleAsyncTaskExecutor executor) {
        // Keep native context propagation, typed binding and usage accounting. Attach actual native
        // tasks to the helper deadline because canceling a CompletableFuture does not stop its IO.
        return new ExecutorAsyncer(command -> SearchTasks.executeNative(executor, command));
    }

    /** Single model calls outside conversations, bounded by the same deployment budgets as a Chat turn. */
    @Bean
    ModelCalls modelCalls(ObjectProvider<ExecutingOperationContext> contexts, AgentProcessRepository repository,
                          ChatExecutionProperties limits,
                          @Value("${embabel.agent.platform.llm-operations.data-binding.max-attempts:10}") int attempts) {
        return new ModelCalls(contexts, repository, limits.costCap(), limits.tokenCap(), attempts);
    }
}
