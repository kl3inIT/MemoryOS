package io.memoryos.api.source;

import io.memoryos.connector.SourceException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;

/**
 * Bounds a Source selection request body before JSON binding, so a scope or selection far beyond the provider's
 * configured byte budget is refused while it is read instead of after it has been materialized.
 */
@NullMarked
final class SelectionRequestBodies {
    private SelectionRequestBodies() {}

    static HttpInputMessage bounded(HttpInputMessage input, int limit) throws IOException {
        if (input.getHeaders().getContentLength() > limit) throw tooLarge();
        InputStream bounded = new FilterInputStream(input.getBody()) {
            private long count;

            @Override
            public int read() throws IOException {
                int value = super.read();
                if (value >= 0 && ++count > limit) throw tooLarge();
                return value;
            }

            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                int read = in.read(bytes, offset, (int) Math.min(length, limit - count + 1));
                if (read > 0 && (count += read) > limit) throw tooLarge();
                return read;
            }
        };
        return new HttpInputMessage() {
            @Override
            public InputStream getBody() {
                return bounded;
            }

            @Override
            public HttpHeaders getHeaders() {
                return input.getHeaders();
            }
        };
    }

    private static SourceException tooLarge() {
        return SourceException.invalid("The selection request exceeds the configured byte limit.",
                "selection transport byte budget exceeded");
    }
}
