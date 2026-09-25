package io.memoryos.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.net.http.WebSocket;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class OpenAiRealtimeTranscriberTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final WebSocket socket = mock(WebSocket.class);
    private final List<String> sent = new CopyOnWriteArrayList<>();
    private final AtomicReference<WebSocket.Listener> provider = new AtomicReference<>();
    private final AtomicReference<URI> uri = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> safetyIdentifier = new AtomicReference<>();
    private final LinkedBlockingQueue<Transcript> transcripts = new LinkedBlockingQueue<>();
    private final AtomicInteger released = new AtomicInteger();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    OpenAiRealtimeTranscriberTest() {
        when(socket.sendText(anyString(), eq(true))).thenAnswer(call -> {
            sent.add(call.getArgument(0));
            return CompletableFuture.completedFuture(socket);
        });
        when(socket.sendClose(org.mockito.ArgumentMatchers.anyInt(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(socket));
        when(socket.isOutputClosed()).thenReturn(false);
    }

    private OpenAiRealtimeTranscriber session(java.util.function.Function<byte[], String> batch) {
        return OpenAiRealtimeTranscriber.open("https://api.openai.com/v1", "voice-secret", "vi", "actor-id",
                batch, transcripts::add, released::incrementAndGet, meters, (target, auth, safety, listener) -> {
                    uri.set(target);
                    authorization.set(auth);
                    safetyIdentifier.set(safety);
                    provider.set(listener);
                    return CompletableFuture.completedFuture(socket);
                });
    }

    @Test
    void streamsCumulativeDeltasAndCommitsTheFinalTranscript() throws Exception {
        try (var session = session(ignored -> "batch must not run")) {
            assertEquals("wss://api.openai.com/v1/realtime?model=gpt-live-transcribe", uri.get().toString());
            assertEquals("Bearer voice-secret", authorization.get());
            assertNotEquals("actor-id", safetyIdentifier.get());
            assertEquals(64, safetyIdentifier.get().length());

            var config = JSON.readTree(sent.getFirst());
            var input = config.path("session").path("audio").path("input");
            assertEquals("transcription", config.path("session").path("type").asString());
            assertEquals(OpenAiRealtimeTranscriber.MODEL,
                    input.path("transcription").path("model").asString());
            assertEquals("low", input.path("transcription").path("delay").asString());
            assertEquals("vi", input.path("transcription").path("languages").get(0).asString());
            assertTrue(input.path("turn_detection").isNull());

            session.append(new byte[] {1, 0, 2, 0});
            var audio = JSON.readTree(sent.get(1));
            assertEquals("input_audio_buffer.append", audio.path("type").asString());
            assertFalse(audio.path("audio").asString().isEmpty());

            receive("{\"type\":\"conversation.item.input_audio_transcription.delta\",\"item_id\":\"one\",\"delta\":\"xin \"}");
            receive("{\"type\":\"conversation.item.input_audio_transcription.delta\",\"item_id\":\"one\",\"delta\":\"chào\"}");
            assertEquals(new Transcript("xin ", false, false), transcripts.poll(1, TimeUnit.SECONDS));
            assertEquals(new Transcript("xin chào", false, false), transcripts.poll(1, TimeUnit.SECONDS));

            var finalText = session.finish();
            assertEquals("input_audio_buffer.commit", JSON.readTree(sent.getLast()).path("type").asString());
            receive("{\"type\":\"conversation.item.input_audio_transcription.completed\",\"item_id\":\"one\",\"transcript\":\"Xin chào.\"}");
            assertEquals("Xin chào.", finalText.get(1, TimeUnit.SECONDS));
            assertEquals(0, meters.find("memoryos.chat.voice.realtime.fallback").counters().size());
        }
        assertEquals(1, released.get());
    }

    @Test
    void replaysBufferedAudioThroughBatchWhenTheProviderStreamFails() throws Exception {
        var batchBytes = new AtomicInteger();
        try (var session = session(wav -> {
            batchBytes.set(wav.length);
            return "bản dự phòng";
        })) {
            session.append(Pcm16Test.tone(0.5, 3000));
            provider.get().onError(socket, new IllegalStateException("provider diagnostic"));
            session.append(Pcm16Test.tone(0.5, 3000));
            assertEquals("bản dự phòng", session.finish().get(5, TimeUnit.SECONDS));
            assertTrue(batchBytes.get() > Pcm16.WAV_HEADER_BYTES);
            assertEquals(1, meters.get("memoryos.chat.voice.realtime.fallback")
                    .tag("provider", VoiceProvider.OPENAI.name()).counter().count());
        }
        assertEquals(1, released.get());
    }

    @Test
    void fragmentedProviderMessagesAreReassembledBeforeParsing() throws Exception {
        try (var session = session(ignored -> "unused")) {
            provider.get().onText(socket,
                    "{\"type\":\"conversation.item.input_audio_transcription.delta\",", false);
            provider.get().onText(socket, "\"delta\":\"hello\"}", true);
            var transcript = transcripts.poll(1, TimeUnit.SECONDS);
            assertNotNull(transcript);
            assertEquals("hello", transcript.text());
        }
    }

    private void receive(String message) {
        provider.get().onText(socket, message, true);
    }
}
