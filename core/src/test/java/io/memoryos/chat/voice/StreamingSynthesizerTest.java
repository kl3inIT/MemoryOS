package io.memoryos.chat.voice;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.memoryos.chat.ChatException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class StreamingSynthesizerTest {
    private final List<String> audio = new CopyOnWriteArrayList<>();
    private final List<String> outcomes = new CopyOnWriteArrayList<>();

    private StreamingSynthesizer synthesizer(Function<String, Stream<byte[]>> provider) {
        return new StreamingSynthesizer(provider, bytes -> audio.add(new String(bytes, UTF_8)), outcomes::add);
    }

    private static Stream<byte[]> chunks(String... parts) {
        return Stream.of(parts).map(part -> part.getBytes(UTF_8));
    }

    @Test
    void readsPartsInOrderAndFinishesAfterTheLast() throws Exception {
        try (var speech = synthesizer(text -> chunks(text + "a", text + "b"))) {
            speech.append("Một.");
            speech.append("   ");
            speech.append("Hai.");
            speech.finish().get(5, TimeUnit.SECONDS);
            assertEquals(List.of("Một.a", "Một.b", "Hai.a", "Hai.b"), audio);
        }
        assertEquals(List.of("succeeded"), outcomes);
    }

    @Test
    void providerFailureFailsTheSpeechWithoutItsPayloadAndSkipsLaterParts() {
        var calls = new AtomicInteger();
        try (var speech = synthesizer(text -> {
            calls.incrementAndGet();
            return Stream.<byte[]>generate(() -> { throw new IllegalStateException("provider said key-123"); });
        })) {
            speech.append("Một.");
            speech.append("Hai.");
            var failure = assertThrows(ExecutionException.class, () -> speech.finish().get(5, TimeUnit.SECONDS));
            var cause = (ChatException) failure.getCause();
            assertEquals("CHAT_PROVIDER_UNAVAILABLE", cause.code());
            assertFalse(cause.getMessage().contains("key-123"));
            assertEquals(1, calls.get());
        }
        assertEquals(List.of("failed"), outcomes);
    }

    @Test
    void boundsEachPartAndTheWholeTextAndRefusesTextAfterFinish() {
        try (var speech = synthesizer(text -> chunks("x"))) {
            assertEquals("CHAT_INVALID_REQUEST", assertThrows(ChatException.class, () -> speech.append("a".repeat(4097))).code());
            for (int i = 0; i < 7; i++) speech.append("a".repeat(4096));
            assertEquals("CHAT_INVALID_REQUEST", assertThrows(ChatException.class, () -> speech.append("a".repeat(4000))).code());
            speech.finish();
            assertThrows(IllegalStateException.class, () -> speech.append("b"));
        }
    }

    @Test
    void closingCancelsThePartBeingReadAndReleasesOnce() throws Exception {
        var reading = new CountDownLatch(1);
        var cancelled = new AtomicBoolean();
        var speech = synthesizer(text -> Stream.<byte[]>generate(() -> {
            reading.countDown();
            try {
                Thread.sleep(10);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            return new byte[] {1};
        }).onClose(() -> cancelled.set(true)));
        speech.append("Một.");
        assertTrue(reading.await(5, TimeUnit.SECONDS));
        speech.close();
        speech.close();
        assertTrue(cancelled.get());
        assertEquals(List.of("cancelled"), outcomes);
    }
}
