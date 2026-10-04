package io.memoryos.shared;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
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
    /** Over TLS the version is agreed in the handshake, so HTTP/2 is used wherever the server offers it. */
    private static final HttpClient SECURE = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2)
            .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(CONNECT_TIMEOUT).build();
    /**
     * Plain HTTP stays on HTTP/1.1: asked for HTTP/2 there, the JDK client sends an upgrade request with every new
     * connection, which self-hosted servers do not speak and some mishandle.
     */
    private static final HttpClient PLAIN = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
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
        return builder(limits, (request, response) -> {
            throw new RestClientResponseException("Outbound request failed", response.getStatusCode(), "", null, null,
                    null);
        });
    }

    /**
     * The same client for an API whose failed answers must be read: {@code failed} receives every answer but 2xx and
     * may read its body, within the bound. An {@code IOException} it throws reaches the caller unchecked.
     */
    public static RestClient.Builder builder(Limits limits, RestClient.ResponseSpec.ErrorHandler failed) {
        var secure = requests(SECURE, limits);
        var plain = requests(PLAIN, limits);
        ClientHttpRequestFactory byScheme = (uri, method) -> (transport(uri) == SECURE ? secure : plain).createRequest(uri, method);
        return RestClient.builder()
                .requestFactory(new BoundedRequestFactory(byScheme, limits.maxResponseBytes()))
                .configureMessageConverters(converters -> CONVERTERS.forEach(converters::addCustomConverter))
                .defaultStatusHandler(status -> !status.is2xxSuccessful(), failed);
    }

    /** The JDK client a request to this address is sent with. */
    static HttpClient transport(URI uri) {
        return "https".equalsIgnoreCase(uri.getScheme()) ? SECURE : PLAIN;
    }

    private static JdkClientHttpRequestFactory requests(HttpClient client, Limits limits) {
        var requests = new JdkClientHttpRequestFactory(client, BODY_WRITERS);
        requests.setReadTimeout(limits.timeout());
        // Compression would add a request header no call sends today and make Content-Length count other bytes.
        requests.enableCompression(false);
        return requests;
    }

    /** The implementation of an {@code @HttpExchange} interface over a client built here. */
    public static <T> T service(Class<T> api, RestClient client) {
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(client)).build().createClient(api);
    }
}
