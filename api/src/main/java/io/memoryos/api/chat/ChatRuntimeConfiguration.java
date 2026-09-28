package io.memoryos.api.chat;

import com.embabel.agent.api.common.Asyncer;
import com.embabel.agent.api.common.ExecutingOperationContext;
import com.embabel.agent.core.AgentProcessRepository;
import com.embabel.agent.spi.support.ExecutorAsyncer;
import io.memoryos.ai.ModelCalls;
import io.memoryos.chat.ChatExecutionProperties;
import io.memoryos.chat.ChatTurnService;
import io.memoryos.chat.streaming.StreamBufferWriter;
import io.memoryos.meeting.MeetingMinutesService;
import io.memoryos.meeting.MeetingRecordingService;
import io.memoryos.retrieval.SearchTasks;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * The API's scheduled Chat and meeting jobs and the model-call beans it composes across modules. Chat assembles its
 * own turn runtime ({@code io.memoryos.chat.execution.ChatExecutionConfiguration}); this class qualifies its
 * {@code chatTaskExecutor}.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class ChatRuntimeConfiguration {
    @Bean
    @Primary
    Asyncer chatNativeAsyncer(
            @Qualifier("chatTaskExecutor") SimpleAsyncTaskExecutor executor) {
        // Keep native context propagation, typed binding and usage accounting. Attach actual native
        // tasks to the helper deadline because canceling a CompletableFuture does not stop its IO.
        return new ExecutorAsyncer(
                command -> SearchTasks.executeNative(executor, command));
    }

    /** Single model calls outside conversations, bounded by the same deployment budgets as a Chat turn. */
    @Bean
    ModelCalls modelCalls(ObjectProvider<ExecutingOperationContext> contexts, AgentProcessRepository repository,
                          ChatExecutionProperties limits,
                          @Value("${embabel.agent.platform.llm-operations.data-binding.max-attempts:10}") int attempts) {
        return new ModelCalls(contexts, repository, limits.costCap(), limits.tokenCap(), attempts);
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
    MeetingMinutes meetingMinutes(MeetingMinutesService minutes) {
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
    MeetingRecordings meetingRecordings(MeetingRecordingService recordings) {
        return new MeetingRecordings(recordings);
    }

    /** Uploaded recordings, on the meetings' scheduler beside the minutes. */
    record MeetingRecordings(MeetingRecordingService recordings) {
        @Scheduled(fixedDelayString = "${memoryos.meeting.recording-interval:5s}", scheduler = "meetingMinutesScheduler")
        public void transcribe() {
            for (int done = 0; done < 2 && recordings.transcribeNext(); done++) { /* drain */ }
        }
    }

    record MeetingMinutes(MeetingMinutesService minutes) {
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
