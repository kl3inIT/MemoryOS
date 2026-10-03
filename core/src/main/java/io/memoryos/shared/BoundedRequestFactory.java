package io.memoryos.shared;

import io.memoryos.shared.OutboundHttp.ResponseTooLargeException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.StreamingHttpOutputMessage;
import org.springframework.http.client.AbstractClientHttpRequestFactoryWrapper;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Bounds the response of every request the wrapped factory creates and closes a response without reading the rest of
 * it. It wraps the factory rather than intercepting the request, because Spring holds the whole request body in
 * memory once a client has an interceptor.
 */
final class BoundedRequestFactory extends AbstractClientHttpRequestFactoryWrapper {
    private final int maxResponseBytes;

    BoundedRequestFactory(ClientHttpRequestFactory requests, int maxResponseBytes) {
        super(requests);
        this.maxResponseBytes = maxResponseBytes;
    }

    @Override
    protected ClientHttpRequest createRequest(URI uri, HttpMethod method, ClientHttpRequestFactory requests)
            throws IOException {
        return new Request(requests.createRequest(uri, method), maxResponseBytes);
    }

    /** Passes everything through, a streamed body included, so an upload is sent as it is produced. */
    private record Request(ClientHttpRequest delegate, int maxResponseBytes)
            implements ClientHttpRequest, StreamingHttpOutputMessage {
        @Override public HttpMethod getMethod() { return delegate.getMethod(); }
        @Override public URI getURI() { return delegate.getURI(); }
        @Override public Map<String, Object> getAttributes() { return delegate.getAttributes(); }
        @Override public HttpHeaders getHeaders() { return delegate.getHeaders(); }
        @Override public OutputStream getBody() throws IOException { return delegate.getBody(); }

        @Override public void setBody(Body body) {
            if (!(delegate instanceof StreamingHttpOutputMessage streaming))
                throw new IllegalStateException("The request factory does not stream request bodies");
            streaming.setBody(body);
        }

        @Override public ClientHttpResponse execute() throws IOException {
            return new Response(delegate.execute(), maxResponseBytes);
        }
    }

    private static final class Response implements ClientHttpResponse {
        private final ClientHttpResponse delegate;
        private final int maxBytes;
        private @Nullable InputStream body;

        private Response(ClientHttpResponse delegate, int maxBytes) {
            this.delegate = delegate;
            this.maxBytes = maxBytes;
        }

        @Override public HttpStatusCode getStatusCode() throws IOException { return delegate.getStatusCode(); }
        @Override public String getStatusText() throws IOException { return delegate.getStatusText(); }
        @Override public HttpHeaders getHeaders() { return delegate.getHeaders(); }

        /** A declared length over the bound is refused before a byte is read; an undeclared one fails as it is read. */
        @Override public InputStream getBody() throws IOException {
            if (body == null) {
                if (delegate.getHeaders().getContentLength() > maxBytes) throw new ResponseTooLargeException();
                body = new Bounded(delegate.getBody(), maxBytes);
            }
            return body;
        }

        /**
         * Spring's response reads the rest of the body before it closes, which for a body that never ends is until
         * the deadline. Closing the body first ends that read at once, and closing the JDK body cancels the exchange.
         */
        @Override public void close() {
            try {
                delegate.getBody().close();
            } catch (IOException ignored) {
                // The response is being abandoned either way.
            }
            delegate.close();
        }
    }

    private static final class Bounded extends FilterInputStream {
        private long remaining;

        private Bounded(InputStream body, int maxBytes) {
            super(body);
            this.remaining = maxBytes;
        }

        @Override public int read() throws IOException {
            int value = super.read();
            if (value >= 0 && --remaining < 0) throw new ResponseTooLargeException();
            return value;
        }

        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = super.read(bytes, offset, length);
            if (read > 0 && (remaining -= read) < 0) throw new ResponseTooLargeException();
            return read;
        }

        /** Skipped bytes count too, so the bound cannot be passed by skipping. */
        @Override public long skip(long count) throws IOException {
            long skipped = super.skip(count);
            if ((remaining -= skipped) < 0) throw new ResponseTooLargeException();
            return skipped;
        }
    }
}
