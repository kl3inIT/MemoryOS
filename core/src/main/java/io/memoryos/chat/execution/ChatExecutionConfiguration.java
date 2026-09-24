package io.memoryos.chat.execution;

import com.embabel.agent.api.common.ExecutingOperationContext;
import com.embabel.agent.core.AgentProcessRepository;
import io.memoryos.chat.ChatExecutionProperties;
import io.memoryos.chat.ChatModelSelector;
import io.memoryos.chat.ChatSettingsService;
import io.memoryos.chat.ChatTurnService;
import io.memoryos.chat.image.ImageArtifactService;
import io.memoryos.chat.image.ImageConnectionService;
import io.memoryos.chat.image.ImageProviderClient;
import io.memoryos.chat.interpreter.InterpreterClient;
import io.memoryos.chat.interpreter.InterpreterService;
import io.memoryos.chat.research.ResearchProperties;
import io.memoryos.chat.research.ResearchTelemetry;
import io.memoryos.chat.session.ChatTurnPersistence;
import io.memoryos.chat.streaming.ChatStreamProperties;
import io.memoryos.chat.streaming.StreamBufferWriter;
import io.memoryos.chat.tools.ChatSearchProperties;
import io.memoryos.chat.web.WebConnectionService;
import io.memoryos.chat.web.WebProviderClient;
import io.memoryos.library.ChatFileContentService;
import io.memoryos.library.ChatFileSearchService;
import io.memoryos.library.ChatFileService;
import io.memoryos.mcp.McpTurnService;
import io.memoryos.retrieval.DocumentOriginalService;
import io.memoryos.retrieval.DocumentSearchService;
import io.memoryos.retrieval.SearchTimings;
import io.memoryos.usage.AiUsageLimitService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.data.redis.core.StringRedisTemplate;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Assembles a Chat turn: the model executor with its tools, the turn service and the reply stream buffer, and the
 * executors they run on. The API scans all of {@code io.memoryos} and so registers these beans; the Worker does not
 * scan Chat and runs no turns. The bean names ({@code chatTaskExecutor}, {@code chatInferenceScheduler},
 * {@code chatStreamScheduler}) are what the API's stream controller and native Asyncer qualify.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ChatExecutionProperties.class, ChatStreamProperties.class, ChatSearchProperties.class,
        ResearchProperties.class})
class ChatExecutionConfiguration {

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
                                        @Qualifier("chatInferenceScheduler") Scheduler scheduler, SearchTimings timings,
                                        ChatFileService files, ChatFileSearchService fileSearch, ChatFileContentService fileContent,
                                        WebProviderClient web, ImageProviderClient image, ImageArtifactService imageArtifacts,
                                        InterpreterClient interpreter, InterpreterService interpreterSettings,
                                        DocumentOriginalService originals, ResearchProperties research,
                                        MeterRegistry meters, ObservationRegistry observations) {
        return new ChatModelExecutor(contexts, repository, limits, search, searchLimits, scheduler, timings, files, fileSearch, fileContent,
                web, image, imageArtifacts, interpreter, interpreterSettings, originals,
                research, new ResearchTelemetry(meters, observations), meters);
    }

    @Bean(destroyMethod = "dispose")
    Scheduler chatInferenceScheduler(ChatExecutionProperties limits) {
        return Schedulers.newBoundedElastic(limits.concurrency(), 16,
                Thread.ofVirtual().name("chat-inference-", 0).factory(), 60);
    }

    @Bean(destroyMethod = "close")
    ChatTurnService chatTurnService(ChatTurnPersistence persistence, ChatModelExecutor model, ChatExecutionProperties limits,
                                    @Qualifier("chatTaskExecutor") SimpleAsyncTaskExecutor chatTaskExecutor, StreamBufferWriter streams,
                                    ChatModelSelector models, WebConnectionService web, ImageConnectionService images,
                                    ChatSettingsService settings, ResearchProperties research, McpTurnService mcp,
                                    AiUsageLimitService spending) {
        var service = new ChatTurnService(persistence, model, limits, chatTaskExecutor, streams, models, web, images, settings, research, mcp, spending);
        // Context refresh completes before the web server accepts requests, so no send can race this.
        service.failOrphanedRuns();
        return service;
    }

    @Bean
    StreamBufferWriter chatStreamBuffer(StringRedisTemplate redis, ChatStreamProperties properties) {
        return new StreamBufferWriter(redis, properties);
    }

    @Bean(destroyMethod = "dispose")
    Scheduler chatStreamScheduler(@Qualifier("chatTaskExecutor") SimpleAsyncTaskExecutor executor) {
        return Schedulers.fromExecutor(executor);
    }
}
