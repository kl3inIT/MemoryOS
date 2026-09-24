package io.memoryos.api.chat;

import com.embabel.agent.api.common.ExecutingOperationContext;
import com.embabel.agent.core.AgentProcessRepository;
import io.memoryos.chat.application.ChatTurnPersistence;
import io.memoryos.chat.ChatTurnService;
import io.memoryos.chat.execution.ChatExecutionProperties;
import io.memoryos.chat.execution.ChatModelExecutor;
import io.memoryos.ai.ModelCalls;
import io.memoryos.chat.execution.ChatModelSelector;
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
@EnableConfigurationProperties({ChatExecutionProperties.class, ChatStreamProperties.class, ChatSearchProperties.class,
        io.memoryos.chat.research.ResearchProperties.class})
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
                                        @Qualifier("chatInferenceScheduler") Scheduler scheduler, io.memoryos.retrieval.SearchTimings timings,
                                        io.memoryos.chat.ChatFileService files, io.memoryos.chat.ChatFileSearchService fileSearch, io.memoryos.chat.ChatFileContentService fileContent,
                                        io.memoryos.chat.web.WebProviderClient web, io.memoryos.chat.image.ImageProviderClient image,
                                        io.memoryos.chat.image.ImageArtifactService imageArtifacts,
                                        io.memoryos.chat.interpreter.InterpreterClient interpreter,
                                        io.memoryos.chat.interpreter.InterpreterService interpreterSettings,
                                        io.memoryos.retrieval.DocumentOriginalService originals,
                                        io.memoryos.chat.research.ResearchProperties research,
                                        io.micrometer.core.instrument.MeterRegistry meters, io.micrometer.observation.ObservationRegistry observations) {
        return new ChatModelExecutor(contexts, repository, limits, search, searchLimits, scheduler, timings, files, fileSearch, fileContent,
                web, image, imageArtifacts, interpreter, interpreterSettings, originals,
                research, new io.memoryos.chat.research.ResearchTelemetry(meters, observations), meters);
    }

    /** Single model calls outside conversations, bounded by the same deployment budgets as a Chat turn. */
    @Bean
    ModelCalls modelCalls(ObjectProvider<ExecutingOperationContext> contexts, AgentProcessRepository repository,
                          ChatExecutionProperties limits) {
        return new ModelCalls(contexts, repository, limits.costCap(), limits.tokenCap());
    }

    @Bean(destroyMethod = "dispose")
    Scheduler chatInferenceScheduler(ChatExecutionProperties limits) {
        return Schedulers.newBoundedElastic(limits.concurrency(), 16,
                Thread.ofVirtual().name("chat-inference-", 0).factory(), 60);
    }

    @Bean(destroyMethod = "close")
    ChatTurnService chatTurnService(ChatTurnPersistence persistence, ChatModelExecutor model, ChatExecutionProperties limits,
                                    @Qualifier("chatTaskExecutor") SimpleAsyncTaskExecutor chatTaskExecutor, StreamBufferWriter streams,
                                    ChatModelSelector models, io.memoryos.chat.web.WebConnectionService web,
                                    io.memoryos.chat.image.ImageConnectionService images, io.memoryos.chat.ChatSettingsService settings,
                                    io.memoryos.chat.research.ResearchProperties research,
                                    io.memoryos.mcp.McpTurnService mcp,
                                    io.memoryos.usage.AiUsageLimitService spending) {
        var service = new ChatTurnService(persistence, model, limits, chatTaskExecutor, streams, models, web, images, settings, research, mcp, spending);
        // Context refresh completes before the web server accepts requests, so no send can race this.
        service.failOrphanedRuns();
        return service;
    }

    @Bean
    StreamBufferWriter chatStreamBuffer(org.springframework.data.redis.core.StringRedisTemplate redis, ChatStreamProperties properties) {
        return new StreamBufferWriter(redis, properties);
    }

    @Bean(destroyMethod = "dispose")
    Scheduler chatStreamScheduler(@Qualifier("chatTaskExecutor") SimpleAsyncTaskExecutor executor) {
        return Schedulers.fromExecutor(executor);
    }

    @Bean
    ChatMaintenance chatMaintenance(ChatTurnService turns, StreamBufferWriter streams) {
        return new ChatMaintenance(turns, streams);
    }

    /**
     * Meeting minutes run here, not in the Worker, because the model catalog (`ai`) and its provider clients are wired
     * in this application. The claim leases one meeting per replica, so running several API replicas is safe.
     */
    @Bean
    MeetingMinutes meetingMinutes(io.memoryos.meeting.MeetingMinutesService minutes) {
        return new MeetingMinutes(minutes);
    }

    @Bean(defaultCandidate = false)
    ThreadPoolTaskScheduler chatMaintenanceScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setVirtualThreads(true);
        scheduler.setThreadNamePrefix("chat-maintenance-");
        return scheduler;
    }

    /**
     * The meetings' own scheduler: writing minutes and transcribing a recording are both long provider calls, while
     * the chat maintenance ticks are due every second and every 25 ms. Neither may wait behind the other, so the two
     * meeting jobs have a thread each and the chat ticks keep theirs.
     */
    @Bean(defaultCandidate = false)
    ThreadPoolTaskScheduler meetingMinutesScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setVirtualThreads(true);
        scheduler.setThreadNamePrefix("meeting-jobs-");
        return scheduler;
    }

    @Bean
    MeetingRecordings meetingRecordings(io.memoryos.meeting.MeetingRecordingService recordings) {
        return new MeetingRecordings(recordings);
    }

    /** Uploaded recordings, on the meetings' scheduler beside the minutes. */
    record MeetingRecordings(io.memoryos.meeting.MeetingRecordingService recordings) {
        @Scheduled(fixedDelayString = "${memoryos.meeting.recording-interval:5s}", scheduler = "meetingMinutesScheduler")
        public void transcribe() {
            for (int done = 0; done < 2 && recordings.transcribeNext(); done++) { /* drain */ }
        }
    }

    record MeetingMinutes(io.memoryos.meeting.MeetingMinutesService minutes) {
        @Scheduled(fixedDelayString = "${memoryos.meeting.minutes-interval:5s}", scheduler = "meetingMinutesScheduler")
        public void write() {
            // A few per pass, so one replica draining a backlog still leaves room for the chat maintenance ticks.
            for (int written = 0; written < 2 && minutes.writeNext(); written++) { /* drain */ }
        }
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
