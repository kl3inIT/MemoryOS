package io.memoryos.api.chat;

import com.embabel.agent.api.common.ExecutingOperationContext;
import com.embabel.agent.core.AgentProcessRepository;
import io.memoryos.chat.application.ChatTurnPersistence;
import io.memoryos.chat.ChatTurnService;
import io.memoryos.chat.execution.ChatExecutionProperties;
import io.memoryos.chat.execution.ChatModelExecutor;
import io.memoryos.chat.catalog.ChatModelResolver;
import io.memoryos.chat.streaming.ChatStreamProperties;
import io.memoryos.chat.streaming.StreamBufferWriter;
import io.memoryos.chat.tools.ChatSearchProperties;
import io.memoryos.retrieval.DocumentSearchService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ChatExecutionProperties.class, ChatStreamProperties.class, ChatSearchProperties.class})
@EnableScheduling
class ChatRuntimeConfiguration {
    @Bean
    @org.springframework.context.annotation.Primary
    com.embabel.agent.api.common.Asyncer chatNativeAsyncer(
            @Qualifier("chatTaskExecutor") SimpleAsyncTaskExecutor executor) {
        // Keep native context propagation, typed binding and usage accounting. Attach actual native
        // tasks to the helper deadline because canceling a CompletableFuture does not stop its IO.
        return new com.embabel.agent.spi.support.ExecutorAsyncer(
                command -> io.memoryos.retrieval.SearchTasks.executeNative(executor, command));
    }

    @Bean(destroyMethod = "close", defaultCandidate = false)
    SimpleAsyncTaskExecutor chatTaskExecutor() {
        var executor = new SimpleAsyncTaskExecutor("chat-");
        executor.setVirtualThreads(true);
        executor.setTaskTerminationTimeout(5000);
        executor.setCancelRemainingTasksOnClose(true);
        return executor;
    }

    @Bean
    ChatModelExecutor chatModelExecutor(ObjectProvider<ExecutingOperationContext> contexts, AgentProcessRepository repository,
                                        ChatExecutionProperties limits, DocumentSearchService search, ChatSearchProperties searchLimits,
                                        @Qualifier("chatInferenceScheduler") Scheduler scheduler, io.memoryos.retrieval.SearchTimings timings) {
        return new ChatModelExecutor(contexts, repository, limits, search, searchLimits, scheduler, timings);
    }

    @Bean(destroyMethod = "dispose")
    Scheduler chatInferenceScheduler(ChatExecutionProperties limits) {
        return Schedulers.newBoundedElastic(limits.concurrency(), 16,
                Thread.ofVirtual().name("chat-inference-", 0).factory(), 60);
    }

    @Bean(destroyMethod = "close")
    ChatTurnService chatTurnService(ChatTurnPersistence persistence, ChatModelExecutor model, ChatExecutionProperties limits,
                                    @Qualifier("chatTaskExecutor") SimpleAsyncTaskExecutor chatTaskExecutor, StreamBufferWriter streams,
                                    ChatModelResolver models) {
        return new ChatTurnService(persistence, model, limits, chatTaskExecutor, streams, models);
    }

    @Bean
    StreamBufferWriter chatStreamBuffer(ChatStreamProperties properties) {
        return new StreamBufferWriter(properties);
    }

    @Bean(destroyMethod = "dispose")
    Scheduler chatStreamScheduler(@Qualifier("chatTaskExecutor") SimpleAsyncTaskExecutor executor) {
        return Schedulers.fromExecutor(executor);
    }

    @Bean
    ChatMaintenance chatMaintenance(ChatTurnService turns, StreamBufferWriter streams) {
        return new ChatMaintenance(turns, streams);
    }

    @Bean(defaultCandidate = false)
    ThreadPoolTaskScheduler chatMaintenanceScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setVirtualThreads(true);
        scheduler.setThreadNamePrefix("chat-maintenance-");
        return scheduler;
    }

    record ChatMaintenance(ChatTurnService turns, StreamBufferWriter streams) {
        @Scheduled(fixedDelayString = "${memoryos.chat.execution.maintenance-interval:1s}", scheduler = "chatMaintenanceScheduler")
        public void maintain() {
            turns.maintain();
        }

        @Scheduled(fixedDelayString = "${memoryos.chat.stream.flush-interval:25ms}", scheduler = "chatMaintenanceScheduler")
        public void flush() {
            streams.flush();
        }
    }
}
