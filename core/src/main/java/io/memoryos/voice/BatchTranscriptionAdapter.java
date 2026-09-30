package io.memoryos.voice;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

/** A whole uploaded recording in the container it arrived in; see {@link BatchTranscriptionService}. */
interface BatchTranscriptionAdapter extends VoiceAdapter {
    /** Whether the answer separates speakers; elsewhere every sentence belongs to one speaker. */
    boolean diarizes();

    /** The largest recording the provider accepts. */
    long maxBytes();

    List<LiveTranscription.Segment> segments(HttpClient http, VoiceConnectionService.Connection connection, String key,
                                             LiveTranscription.Options options, boolean diarize,
                                             BatchTranscriptionService.Recording recording, Duration timeout)
            throws IOException, InterruptedException;
}
