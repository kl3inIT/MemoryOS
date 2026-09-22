package io.memoryos.chat.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ChunkedLiveTranscriptionTest {
    private final List<LiveTranscription.Segment> segments = new CopyOnWriteArrayList<>();
    private final AtomicInteger failures = new AtomicInteger();
    private final LiveTranscription.Listener listener = new LiveTranscription.Listener() {
        @Override public void preview(String speaker, String text) {}
        @Override public void segment(LiveTranscription.Segment segment) { segments.add(segment); }
        @Override public void failed() { failures.incrementAndGet(); }
    };

    @Test
    void cutsUtterancesAtPausesSkipsSilenceAndKeepsTheRecordingClock() throws Exception {
        var calls = new AtomicInteger();
        try (var stream = new ChunkedLiveTranscription(wav -> "câu " + calls.incrementAndGet(), 10_000, listener)) {
            stream.append(new byte[Pcm16.BYTES_PER_SECOND]);
            stream.append(Pcm16Test.tone(1.0, 3000));
            stream.append(new byte[Pcm16.BYTES_PER_SECOND * 2]);
            stream.append(Pcm16Test.tone(0.5, 3000));
            stream.finish().get(5, TimeUnit.SECONDS);
        }
        assertEquals(2, calls.get(), "silence never reaches the provider");
        assertEquals("câu 1", segments.get(0).text());
        assertEquals(11_000, segments.get(0).startMs());
        assertTrue(segments.get(0).endMs() >= 12_000);
        assertEquals("câu 2", segments.get(1).text());
        assertEquals(14_000, segments.get(1).startMs());
        assertEquals("1", segments.get(1).speaker());
    }

    @Test
    void aProviderErrorIsReported() throws Exception {
        try (var stream = new ChunkedLiveTranscription(wav -> { throw new IllegalStateException("down"); }, 0, listener)) {
            stream.append(Pcm16Test.tone(0.5, 3000));
            stream.finish().get(5, TimeUnit.SECONDS);
        }
        assertEquals(1, failures.get());
        assertTrue(segments.isEmpty());
    }
}
