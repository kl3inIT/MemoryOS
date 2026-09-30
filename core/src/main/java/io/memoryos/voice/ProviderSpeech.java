package io.memoryos.voice;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

/** MP3 for text segments from one provider at the member's speed; closing it releases the provider client. */
interface ProviderSpeech extends AutoCloseable {
    /** Lazy audio of consecutive provider requests; closing the stream cancels the request in progress. */
    Stream<byte[]> chunks(List<String> segments);

    @Override
    void close();

    /**
     * REST providers on the shared client. Stopping a speech closes its chunk stream, which cancels the request in
     * flight, so there is no client of its own to shut down.
     */
    static ProviderSpeech http(HttpClient client, Function<String, HttpRequest> request) {
        return new ProviderSpeech() {
            @Override public Stream<byte[]> chunks(List<String> segments) { return HttpAudioStream.of(client, segments, request); }
            @Override public void close() {
                // Each chunk stream cancels its own request when it is closed.
            }
        };
    }
}
