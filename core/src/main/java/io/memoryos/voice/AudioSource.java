package io.memoryos.voice;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.http.HttpRequest;

/**
 * Audio that is read where it is kept, as the provider call sends it. A five-hour recording is hundreds of megabytes;
 * it is streamed from object storage to the provider in small reads and is never held whole in memory.
 */
@FunctionalInterface
public interface AudioSource {
    /** Opens the audio from its first byte; a provider call that sends it again opens it again. */
    InputStream open() throws IOException;

    /** Audio that is already in memory, such as a few seconds of dictation. */
    static AudioSource of(byte[] audio) {
        return () -> new ByteArrayInputStream(audio);
    }

    /** The audio as a request body of a known length, so the provider receives a {@code Content-Length}. */
    static HttpRequest.BodyPublisher body(AudioSource source, long sizeBytes) {
        return HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofInputStream(() -> {
            try {
                return source.open();
            } catch (IOException unreadable) {
                throw new UncheckedIOException(unreadable);
            }
        }), sizeBytes);
    }
}
