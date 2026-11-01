package io.memoryos.chat.execution;

import io.memoryos.chat.ChatTurnService;
import io.memoryos.chat.streaming.StreamBufferWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * The ticks a process that executes Chat turns owes them: saving a finished turn that could not be saved yet,
 * renewing and reconciling run leases, giving a freed slot to a waiting turn, and writing reply streams to Redis.
 * It comes with the turn runtime, so the API and {@code memoryos-channels} run it alike (MEM-246).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class ChatMaintenanceConfiguration {

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
