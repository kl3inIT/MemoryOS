package io.memoryos.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ChunkedTranscriberTest {
    private final List<Integer> uploads = new CopyOnWriteArrayList<>();
    private final LinkedBlockingQueue<Transcript> transcripts = new LinkedBlockingQueue<>();
    private final AtomicInteger released = new AtomicInteger();

    private ChunkedTranscriber transcriber(java.util.function.Function<byte[], String> provider) {
        return new ChunkedTranscriber(wav -> {
            uploads.add(wav.length - 44);
            return provider.apply(wav);
        }, transcripts::add, released::incrementAndGet);
    }

    @Test
    void voicedWindowsProduceInterimTextAndSilentWindowsNeverReachTheProvider() throws Exception {
        var calls = new AtomicInteger();
        try (var transcriber = transcriber(wav -> "phần " + calls.incrementAndGet())) {
            transcriber.append(new byte[ChunkedTranscriber.WINDOW_BYTES]);
            transcriber.append(Pcm16Test.tone(3, 3000));
            var interim = transcripts.poll(5, TimeUnit.SECONDS);
            assertNotNull(interim);
            assertEquals(new Transcript("phần 1", false), interim);
            assertEquals(List.of(ChunkedTranscriber.WINDOW_BYTES), uploads);
        }
    }

    @Test
    void finishTranscribesOnlyTheVoicedSpanOfTheWholeRecording() throws Exception {
        try (var transcriber = transcriber(wav -> "xin chào")) {
            // Shorter than one window, so no interim request can race the final pass.
            transcriber.append(Pcm16Test.concat(new byte[Pcm16.BYTES_PER_SECOND], Pcm16Test.tone(1, 3000)));
            assertEquals("xin chào", transcriber.finish().get(5, TimeUnit.SECONDS));
            assertEquals(List.of(Pcm16.BYTES_PER_SECOND * 3 / 2), uploads);
            assertThrows(IllegalStateException.class, () -> transcriber.append(new byte[2]));
        }
    }

    @Test
    void failedFinalPassFallsBackToInterimTextAndFailsWithoutAny() throws Exception {
        var calls = new AtomicInteger();
        try (var withInterim = transcriber(wav -> {
            if (calls.incrementAndGet() > 1) throw new IllegalStateException("provider down");
            return "đoạn đầu";
        })) {
            withInterim.append(Pcm16Test.tone(3, 3000));
            assertNotNull(transcripts.poll(5, TimeUnit.SECONDS));
            assertEquals("đoạn đầu", withInterim.finish().get(5, TimeUnit.SECONDS));
        }
        try (var without = transcriber(wav -> { throw new IllegalStateException("provider down"); })) {
            without.append(Pcm16Test.tone(1, 3000));
            var failure = assertThrows(ExecutionException.class, () -> without.finish().get(5, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof IllegalStateException);
        }
    }

    @Test
    void silentRecordingFinishesEmptyWithoutAProviderCallAndCloseReleasesOnce() throws Exception {
        var transcriber = transcriber(wav -> "never");
        transcriber.append(new byte[Pcm16.BYTES_PER_SECOND]);
        assertEquals("", transcriber.finish().get(5, TimeUnit.SECONDS));
        assertTrue(uploads.isEmpty());
        transcriber.close();
        transcriber.close();
        assertEquals(1, released.get());
        assertFalse(transcripts.iterator().hasNext());
    }
}
