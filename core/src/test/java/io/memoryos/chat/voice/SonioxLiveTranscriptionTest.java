package io.memoryos.chat.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class SonioxLiveTranscriptionTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final List<WebSocket.Listener> providers = new CopyOnWriteArrayList<>();
    private final List<List<String>> texts = new CopyOnWriteArrayList<>();
    private final List<List<Integer>> binaries = new CopyOnWriteArrayList<>();
    private final LinkedBlockingQueue<LiveTranscription.Segment> segments = new LinkedBlockingQueue<>();
    private final List<String> previews = new CopyOnWriteArrayList<>();
    private final AtomicInteger failures = new AtomicInteger();

    private final LiveTranscription.Listener listener = new LiveTranscription.Listener() {
        @Override public void preview(String speaker, String text) { previews.add(speaker + ":" + text); }
        @Override public void segment(LiveTranscription.Segment segment) { segments.add(segment); }
        @Override public void failed() { failures.incrementAndGet(); }
    };

    private WebSocket socket() {
        var sent = new CopyOnWriteArrayList<String>();
        var frames = new CopyOnWriteArrayList<Integer>();
        texts.add(sent);
        binaries.add(frames);
        var socket = mock(WebSocket.class);
        when(socket.sendText(anyString(), eq(true))).thenAnswer(call -> {
            sent.add(call.getArgument(0));
            return CompletableFuture.completedFuture(socket);
        });
        when(socket.sendBinary(any(ByteBuffer.class), eq(true))).thenAnswer(call -> {
            frames.add(((ByteBuffer) call.getArgument(0)).remaining());
            return CompletableFuture.completedFuture(socket);
        });
        when(socket.sendClose(anyInt(), anyString())).thenReturn(CompletableFuture.completedFuture(socket));
        when(socket.isOutputClosed()).thenReturn(false);
        return socket;
    }

    private SonioxLiveTranscription open(long offsetMs, boolean diarize) {
        return SonioxLiveTranscription.open("https://api.soniox.com/v1", "voice-secret", "stt-rt-v5",
                new LiveTranscription.Options("vi", List.of("Tasco", "OKR"), diarize), offsetMs, listener,
                (uri, provider) -> {
                    providers.add(provider);
                    return CompletableFuture.completedFuture(socket());
                }, List.of(Duration.ofMillis(10), Duration.ofMillis(10)));
    }

    @Test
    void configuresDiarizationEndpointsAndContextTerms() {
        try (var ignored = open(0, true)) {
            var config = JSON.readTree(texts.getFirst().getFirst());
            assertTrue(config.path("enable_speaker_diarization").asBoolean());
            assertTrue(config.path("enable_endpoint_detection").asBoolean());
            assertEquals(24_000, config.path("sample_rate").asInt());
            assertEquals("Tasco", config.path("context").path("terms").get(0).asString());
            assertEquals("vi", config.path("language_hints").get(0).asString());
        }
    }

    @Test
    void finalTokensBecomeSegmentsSplitBySpeakerAndEndpointWithTheOffsetApplied() throws Exception {
        try (var stream = open(60_000, true)) {
            receive(0, "{\"tokens\":[{\"text\":\"Chốt\",\"start_ms\":100,\"end_ms\":400,\"is_final\":true,\"speaker\":\"1\",\"confidence\":0.9},"
                    + "{\"text\":\" ngân sách\",\"start_ms\":400,\"end_ms\":900,\"is_final\":true,\"speaker\":\"1\",\"confidence\":0.7},"
                    + "{\"text\":\" Đồng\",\"start_ms\":1000,\"end_ms\":1200,\"is_final\":false,\"speaker\":\"2\"}]}");
            assertTrue(segments.isEmpty(), "a segment waits for a speaker change or an endpoint");
            assertEquals("1:Chốt ngân sách Đồng", previews.getLast());
            receive(0, "{\"tokens\":[{\"text\":\"Đồng ý.\",\"start_ms\":1000,\"end_ms\":1500,\"is_final\":true,\"speaker\":\"2\"},"
                    + "{\"text\":\"<end>\",\"is_final\":true}]}");
            var first = segments.poll(1, TimeUnit.SECONDS);
            assertEquals(new LiveTranscription.Segment("1", 60_100, 60_900, "Chốt ngân sách", 0.8), first);
            var second = segments.poll(1, TimeUnit.SECONDS);
            assertEquals("2", second.speaker());
            assertEquals("Đồng ý.", second.text());
            assertEquals(61_500, second.endMs());

            var done = stream.finish();
            assertEquals("finalize", JSON.readTree(texts.getFirst().getLast()).path("type").asString());
            assertEquals(0, binaries.getFirst().getLast());
            receive(0, "{\"tokens\":[{\"text\":\"<fin>\",\"is_final\":true}],\"finished\":true}");
            done.get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void providerFailureReconnectsReplaysTheRetainedTailAndShiftsTimes() throws Exception {
        try (var stream = open(0, true)) {
            byte[] second = new byte[Pcm16.BYTES_PER_SECOND];
            for (int i = 0; i < 8; i++) stream.append(second);
            receive(0, "{\"error_code\":503,\"error_message\":\"Cannot continue request\"}");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while ((binaries.size() < 2 || binaries.get(1).size() < 5) && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(2, providers.size(), "a new provider stream was opened");
            assertEquals(5, binaries.get(1).size(), "the last five seconds are replayed");
            receive(1, "{\"tokens\":[{\"text\":\"Tiếp tục\",\"start_ms\":0,\"end_ms\":500,\"is_final\":true,\"speaker\":\"1\"},"
                    + "{\"text\":\"<end>\",\"is_final\":true}]}");
            // Eight seconds were sent and five replayed, so the new stream starts three seconds into the recording.
            assertEquals(3_000, segments.poll(1, TimeUnit.SECONDS).startMs());
            assertEquals(0, failures.get());
        }
    }

    @Test
    void exhaustedRetriesReportFailureOnce() throws Exception {
        var connects = new AtomicInteger();
        try (var ignored = SonioxLiveTranscription.open("https://api.soniox.com/v1", "voice-secret", "stt-rt-v5",
                new LiveTranscription.Options(null, List.of(), false), 0, listener, (uri, provider) -> {
                    providers.add(provider);
                    return connects.getAndIncrement() == 0 ? CompletableFuture.completedFuture(socket())
                            : CompletableFuture.failedFuture(new IllegalStateException("unreachable"));
                }, List.of(Duration.ofMillis(10), Duration.ofMillis(10)))) {
            receive(0, "{\"error_code\":503}");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (failures.get() == 0 && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(1, failures.get());
            assertFalse(JSON.readTree(texts.getFirst().getFirst()).path("enable_speaker_diarization").asBoolean());
        }
    }

    private void receive(int stream, String message) {
        var socket = mock(WebSocket.class);
        providers.get(stream).onText(socket, message, true);
    }
}
