package io.memoryos.chat.voice;

import io.memoryos.chat.ChatException;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.jspecify.annotations.Nullable;

/**
 * Audio from consecutive provider requests, one per text segment, passed on as each read returns. A rejected request is
 * reported without its body; closing the stream closes the response being read.
 */
final class HttpAudioStream {
    private static final int CHUNK_BYTES = 8 * 1024;

    private HttpAudioStream() {}

    static Stream<byte[]> of(HttpClient client, List<String> segments, Function<String, HttpRequest> request) {
        var reader = new Reader(client, segments.iterator(), request);
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(reader, Spliterator.ORDERED | Spliterator.NONNULL),
                false).onClose(reader::close);
    }

    private static final class Reader implements Iterator<byte[]> {
        private final HttpClient client;
        private final Iterator<String> segments;
        private final Function<String, HttpRequest> request;
        private volatile @Nullable InputStream body;
        private volatile boolean closed;
        private byte @Nullable [] next;

        private Reader(HttpClient client, Iterator<String> segments, Function<String, HttpRequest> request) {
            this.client = client;
            this.segments = segments;
            this.request = request;
        }

        @Override
        public boolean hasNext() {
            if (next != null) return true;
            byte[] buffer = new byte[CHUNK_BYTES];
            try {
                while (!closed) {
                    var current = body;
                    if (current == null) {
                        if (!segments.hasNext()) return false;
                        current = open(segments.next());
                        body = current;
                    }
                    int read = current.read(buffer);
                    if (read < 0) {
                        current.close();
                        body = null;
                    } else if (read > 0) {
                        next = Arrays.copyOf(buffer, read);
                        return true;
                    }
                }
                return false;
            } catch (IOException failed) {
                if (closed) return false;
                throw ChatException.providerUnavailable();
            }
        }

        @Override
        public byte[] next() {
            if (!hasNext()) throw new NoSuchElementException();
            byte[] chunk = next;
            next = null;
            return chunk;
        }

        private InputStream open(String segment) throws IOException {
            HttpResponse<InputStream> response;
            try {
                response = client.send(request.apply(segment), HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted", interrupted);
            }
            if (response.statusCode() >= 200 && response.statusCode() < 300) return response.body();
            // Error bodies may carry account detail and are never read.
            response.body().close();
            throw ChatException.providerUnavailable();
        }

        private void close() {
            closed = true;
            var current = body;
            if (current == null) return;
            try {
                current.close();
            } catch (IOException ignored) {
                // The client that owns the connection is closed by the speech.
            }
        }
    }
}
