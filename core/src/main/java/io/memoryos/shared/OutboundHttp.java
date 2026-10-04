package io.memoryos.shared;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/**
 * Outbound HTTP to an endpoint an administrator configured. A client built here never follows a redirect, so a
 * credential cannot be sent on to another host; it ends at its deadline, stops reading at its bound, and reports a
 * failed answer by its status alone, so a provider's error body reaches neither a response nor a log (ADR 0025).
 */
public final class OutboundHttp {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    /** HTTP/1.1: left at its default, the JDK client asks every new plain-HTTP connection to upgrade to HTTP/2. */
    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(CONNECT_TIMEOUT).build();
    /** A streamed request body is written on a virtual thread; without this Spring starts a thread for each one. */
    private static final Executor BODY_WRITERS = Executors.newVirtualThreadPerTaskExecutor();
    /** Named, not detected: core has both Jackson generations on its classpath. */
    private static final List<HttpMessageConverter<?>> CONVERTERS = List.of(new ByteArrayHttpMessageConverter(),
            new StringHttpMessageConverter(StandardCharsets.UTF_8), new FormHttpMessageConverter(), json());

    private OutboundHttp() {}

    /**
     * JSON is written as {@code application/json} and read whatever the answer calls itself: a self-hosted server
     * may declare no content type, or the wrong one, for a body that is JSON.
     */
    private static JacksonJsonHttpMessageConverter json() {
        var converter = new JacksonJsonHttpMessageConverter();
        converter.setSupportedMediaTypes(List.of(MediaType.APPLICATION_JSON, MediaType.ALL));
        return converter;
    }

    /** The deadline for one exchange, from sending the request to closing the response, and the largest response read. */
    public record Limits(Duration timeout, int maxResponseBytes) {}

    /** The response was larger than its bound. */
    public static final class ResponseTooLargeException extends IOException {
        ResponseTooLargeException() { super("Response too large"); }
    }

    /**
     * A builder for a client that keeps the rules above; the caller adds what belongs to its own API, such as a base
     * URL or a credential header. Any status but 2xx, a redirect included, is a {@link RestClientResponseException}
     * that carries the status and no body. {@code exchange} hands the response to the caller instead, still bounded.
     */
    public static RestClient.Builder builder(Limits limits) {
        var requests = new JdkClientHttpRequestFactory(HTTP, BODY_WRITERS);
        requests.setReadTimeout(limits.timeout());
        // Compression would add a request header no call sends today and make Content-Length count other bytes.
        requests.enableCompression(false);
        return RestClient.builder()
                .requestFactory(new BoundedRequestFactory(requests, limits.maxResponseBytes()))
                .configureMessageConverters(converters -> CONVERTERS.forEach(converters::addCustomConverter))
                .defaultStatusHandler(status -> !status.is2xxSuccessful(), (request, response) -> {
                    throw new RestClientResponseException("Outbound request failed", response.getStatusCode(), "", null,
                            null, null);
                });
    }

    /** The implementation of an {@code @HttpExchange} interface over a client built here. */
    public static <T> T service(Class<T> api, RestClient client) {
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(client)).build().createClient(api);
    }
}
