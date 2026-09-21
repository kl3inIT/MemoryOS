package io.memoryos.chat.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class SonioxRealtimeTranscriberTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final WebSocket socket = mock(WebSocket.class);
    private final List<String> texts = new CopyOnWriteArrayList<>();
    private final List<Integer> binaries = new CopyOnWriteArrayList<>();
    private final AtomicReference<WebSocket.Listener> provider = new AtomicReference<>();
    private final AtomicReference<URI> uri = new AtomicReference<>();
    private final LinkedBlockingQueue<Transcript> transcripts = new LinkedBlockingQueue<>();
    private final AtomicInteger released = new AtomicInteger();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    SonioxRealtimeTranscriberTest() {
        when(socket.sendText(anyString(), eq(true))).thenAnswer(call -> {
            texts.add(call.getArgument(0));
            return CompletableFuture.completedFuture(socket);
        });
        when(socket.sendBinary(any(ByteBuffer.class), eq(true))).thenAnswer(call -> {
            binaries.add(((ByteBuffer) call.getArgument(0)).remaining());
            return CompletableFuture.completedFuture(socket);
        });
        when(socket.sendClose(org.mockito.ArgumentMatchers.anyInt(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(socket));
        when(socket.isOutputClosed()).thenReturn(false);
    }

    private SonioxRealtimeTranscriber session(java.util.function.Function<byte[], String> batch) {
        return SonioxRealtimeTranscriber.open("https://api.soniox.com/v1", "voice-secret", "stt-rt-v5", "vi", batch,
                transcripts::add, released::incrementAndGet, meters, (target, listener) -> {
                    uri.set(target);
                    provider.set(listener);
                    return CompletableFuture.completedFuture(socket);
                });
    }

    @Test
    void sendsTheKeyAndPcmFormatFirstThenCommitsOnlyFinalTokens() throws Exception {
        try (var session = session(ignored -> "batch must not run")) {
            assertEquals("wss://stt-rt.soniox.com/transcribe-websocket", uri.get().toString());
            var config = JSON.readTree(texts.getFirst());
            assertEquals("voice-secret", config.path("api_key").asString());
            assertEquals("stt-rt-v5", config.path("model").asString());
            assertEquals("pcm_s16le", config.path("audio_format").asString());
            assertEquals(24_000, config.path("sample_rate").asInt());
            assertEquals(1, config.path("num_channels").asInt());
            assertEquals("vi", config.path("language_hints").get(0).asString());
            assertTrue(config.path("language_hints_strict").asBoolean());

            session.append(new byte[] {1, 0, 2, 0});
            assertEquals(List.of(4), binaries);

            receive("{\"tokens\":[{\"text\":\"Xin\",\"is_final\":true},{\"text\":\" chà\",\"is_final\":false}]}");
            assertEquals(new Transcript("Xin chà", false, false), transcripts.poll(1, TimeUnit.SECONDS));
            receive("{\"tokens\":[{\"text\":\" chào.\",\"is_final\":true},{\"text\":\"<end>\",\"is_final\":true}]}");
            assertEquals(new Transcript("Xin chào.", false, false), transcripts.poll(1, TimeUnit.SECONDS));

            var finalText = session.finish();
            assertEquals("finalize", JSON.readTree(texts.getLast()).path("type").asString());
            assertEquals(0, binaries.getLast());
            receive("{\"tokens\":[{\"text\":\"<fin>\",\"is_final\":true}],\"finished\":true}");
            assertEquals("Xin chào.", finalText.get(1, TimeUnit.SECONDS));
            assertEquals(0, meters.find("memoryos.chat.voice.realtime.fallback").counters().size());
        }
        assertEquals(1, released.get());
    }

    @Test
    void providerErrorReplaysBufferedAudioThroughBatch() throws Exception {
        var batchBytes = new AtomicInteger();
        try (var session = session(wav -> {
            batchBytes.set(wav.length);
            return "bản dự phòng";
        })) {
            session.append(Pcm16Test.tone(0.5, 3000));
            receive("{\"error_code\":503,\"error_message\":\"voice-provider-diagnostic\"}");
            session.append(Pcm16Test.tone(0.5, 3000));
            assertEquals("bản dự phòng", session.finish().get(5, TimeUnit.SECONDS));
            assertTrue(batchBytes.get() > Pcm16.WAV_HEADER_BYTES);
            assertEquals(1, meters.get("memoryos.chat.voice.realtime.fallback")
                    .tag("provider", VoiceProvider.SONIOX.name()).counter().count());
        }
        assertEquals(1, released.get());
    }

    @Test
    void fragmentedProviderMessagesAreReassembledBeforeParsing() {
        try (var session = session(ignored -> "unused")) {
            provider.get().onText(socket, "{\"tokens\":[{\"text\":\"hel", false);
            provider.get().onText(socket, "lo\",\"is_final\":true}]}", true);
            var transcript = transcripts.poll();
            assertNotNull(transcript);
            assertEquals("hello", transcript.text());
            assertFalse(transcript.isFinal());
        }
    }

    @Test
    void realtimeHostFollowsTheRestEndpointRegion() {
        assertEquals("wss://stt-rt.eu.soniox.com/transcribe-websocket",
                SonioxRealtimeTranscriber.realtimeUri("https://api.eu.soniox.com/v1").toString());
        assertEquals("ws://127.0.0.1:8080/transcribe-websocket",
                SonioxRealtimeTranscriber.realtimeUri("http://127.0.0.1:8080/v1").toString());
    }

    private void receive(String message) {
        provider.get().onText(socket, message, true);
    }
}
