package io.memoryos.voice;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.Resource;

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

    /**
     * The audio as one part of a multipart upload, read from its source as the part is written. The provider detects
     * the container itself, but the name must not break the part's header.
     */
    static Resource part(AudioSource source, long sizeBytes, String filename) {
        String safe = filename.replaceAll("[\"\\r\\n\\\\]", "").strip();
        String name = safe.isEmpty() ? "audio" : safe;
        return new AbstractResource() {
            @Override public InputStream getInputStream() throws IOException { return source.open(); }
            @Override public long contentLength() { return sizeBytes; }
            @Override public String getFilename() { return name; }
            @Override public String getDescription() { return "audio part " + name; }
        };
    }
}
