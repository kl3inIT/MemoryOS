package io.memoryos.api.meeting;

import io.memoryos.meeting.MeetingMinutesService;
import io.memoryos.meeting.MeetingRecordingService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * The API's scheduled meeting jobs. Chat assembles its own turn runtime, the model calls and the ticks it owes
 * ({@code io.memoryos.chat.execution}), which every process that runs turns shares.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class MeetingJobsConfiguration {
    /**
     * Meeting minutes run here, not in the Worker, because the model catalog (`ai`) and its provider clients are wired
     * in this application. The claim leases one meeting per replica, so running several API replicas is safe.
     */
    @Bean
    MeetingMinutes meetingMinutes(MeetingMinutesService minutes) {
        return new MeetingMinutes(minutes);
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
}
