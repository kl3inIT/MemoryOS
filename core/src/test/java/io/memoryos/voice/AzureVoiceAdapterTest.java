package io.memoryos.voice;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class AzureVoiceAdapterTest {
    private final FakeAzureSpeechGateway gateway = new FakeAzureSpeechGateway();
    private final AzureVoiceAdapter adapter = new AzureVoiceAdapter(gateway);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final List<Transcript> transcripts = new CopyOnWriteArrayList<>();
    private final AtomicInteger released = new AtomicInteger();

    private static VoiceConnectionService.Connection connection(String endpoint) {
        return new VoiceConnectionService.Connection(UUID.randomUUID(), UUID.randomUUID(), VoiceProvider.AZURE, endpoint,
                "default", "neural", "vi-VN-HoaiMyNeural", "encrypted", 1);
    }

    private TranscriptionSession open(String endpoint, String language, Function<byte[], String> batch) {
        return adapter.openRealtime(connection(endpoint), "azure-secret", language, "user", batch, transcripts::add,
                released::incrementAndGet, meters).orElseThrow();
    }

    @Test
    void aCustomDomainOrContainerIsTheEndpointAndTheRegionalFormIsItsRegion() {
        assertEquals(new AzureSpeechTarget(URI.create("https://memoryos-speech.cognitiveservices.azure.com"), null),
                AzureSpeechTarget.of("https://memoryos-speech.cognitiveservices.azure.com"));
        assertEquals(new AzureSpeechTarget(null, "southeastasia"),
                AzureSpeechTarget.of("https://SouthEastAsia.api.cognitive.microsoft.com"));
        assertEquals(new AzureSpeechTarget(URI.create("http://speech-container:5000"), null),
                AzureSpeechTarget.of("http://speech-container:5000"));
        assertThrows(IllegalArgumentException.class, () -> new AzureSpeechTarget(null, null));
    }

    @Test
    void theMemberLanguageIsRecognizedAsGivenAndWithoutOneVietnameseAndEnglishAreIdentified() {
        open("https://westeurope.api.cognitive.microsoft.com", "en", wav -> "").close();
        open("https://speech.cognitiveservices.azure.com", null, wav -> "").close();
        assertEquals(List.of(List.of("en-US"), List.of("vi-VN", "en-US")), gateway.locales);
        assertEquals("westeurope", gateway.targets.getFirst().region());
        assertEquals(2, released.get(), "each session returns its slot once");
    }

    @Test
    void audioIsResampledTo16kHzAndUtterancesEndedBySilenceAreCommittedBoundaries() throws Exception {
        var session = open("https://speech.cognitiveservices.azure.com", "vi", wav -> "unused");
        session.append(new byte[4]);
        session.append(new byte[8]);
        assertEquals(8, gateway.written.size(), "12 bytes at 24 kHz are 8 at 16 kHz, whole sample triples only");

        gateway.listener.recognizing("Chốt ngân");
        gateway.listener.recognized("Chốt ngân sách.");
        gateway.listener.recognizing("Em gửi");
        assertEquals(List.of(new Transcript("Chốt ngân", false, false), new Transcript("Chốt ngân sách.", false, true),
                new Transcript("Chốt ngân sách. Em gửi", false, false)), transcripts);

        gateway.onStop = () -> gateway.listener.recognized("Em gửi KPI.");
        assertEquals("Chốt ngân sách. Em gửi KPI.", session.finish().get(5, TimeUnit.SECONDS));
        assertTrue(gateway.stopped);
        session.close();
        session.close();
        assertEquals(1, released.get());
        assertEquals(1, gateway.recognitionsClosed);
    }

    @Test
    void aProviderFailureFallsBackToChunkedRestTranscriptionOfTheWholeRecording() throws Exception {
        var session = open("https://speech.cognitiveservices.azure.com", "vi", wav -> "bản ghi đầy đủ");
        session.append(speech(1));
        gateway.listener.failed();
        session.append(speech(1));
        assertEquals("bản ghi đầy đủ", session.finish().get(10, TimeUnit.SECONDS));
        assertEquals(1, meters.get("memoryos.chat.voice.realtime.fallback").tag("provider", "AZURE").counter().count());
        session.close();
    }

    @Test
    void aSessionTheSdkCannotStartIsReportedSoTheServiceFallsBack() {
        gateway.unavailable = true;
        assertThrows(IllegalStateException.class, () -> open("https://speech.cognitiveservices.azure.com", "vi", wav -> ""));
        assertEquals(0, released.get(), "the service, not the failed session, owns the slot");
    }

    @Test
    void readAloudStreamsEachSegmentThroughTheSdkAndClosingStopsTheSpeechInProgress() {
        gateway.speeches.add(List.of(new byte[] {1, 2}, new byte[] {3}));
        gateway.speeches.add(List.of(new byte[] {4}));
        var speech = adapter.speech(HttpClient.newHttpClient(), connection("https://eastus.api.cognitive.microsoft.com"),
                "azure-secret", 1.25, Duration.ofSeconds(5));
        try (var chunks = speech.chunks(List.of("Một & hai.", "Ba."))) {
            var all = chunks.toList();
            assertEquals(3, all.size());
            assertArrayEquals(new byte[] {4}, all.getLast());
        }
        assertEquals(2, gateway.ssml.size());
        assertTrue(gateway.ssml.getFirst().contains("<voice name=\"vi-VN-HoaiMyNeural\"><prosody rate=\"1.25\">Một &amp; hai.</prosody>"));
        assertEquals("eastus", gateway.targets.getFirst().region());
        assertEquals(2, gateway.speechesClosed);

        gateway.speeches.add(List.of(new byte[] {5}, new byte[] {6}));
        try (var chunks = speech.chunks(List.of("Bốn."))) {
            assertArrayEquals(new byte[] {5}, chunks.iterator().next());
        }
        assertEquals(3, gateway.speechesClosed, "a stopped read-aloud stops the speech it was reading");
        assertNull(gateway.speeches.peek());
    }

    /** Seconds of a 440 Hz tone at 24 kHz PCM16, loud enough to count as speech. */
    private static byte[] speech(int seconds) {
        byte[] pcm = new byte[Pcm16.BYTES_PER_SECOND * seconds];
        for (int i = 0; i < pcm.length / 2; i++) {
            short sample = (short) (Math.sin(2 * Math.PI * 440 * i / Pcm16.SAMPLE_RATE) * 6000);
            pcm[2 * i] = (byte) sample;
            pcm[2 * i + 1] = (byte) (sample >> 8);
        }
        return pcm;
    }
}
