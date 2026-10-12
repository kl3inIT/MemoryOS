package io.memoryos.ai.systemone;

import io.memoryos.shared.OutboundHttp;
import java.time.Duration;
import java.util.List;
import org.springaicommunity.typesafe.RetryPolicy;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.api.TypeSafeApi;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * How a {@link TypeSafeClient} is built here (ADR 0026): on a {@link RestClient} from {@link OutboundHttp}, so the
 * four transport rules hold (no redirect, one deadline, a bounded answer, a failed answer reported by its status with
 * the body unread), without retries, and without the SDK's own error handler, which reads the failed body into the
 * exception message. A connection without a key sends no {@code Authorization} header.
 */
public final class SystemOneProtocol {
    /** An answer is a number or a label per question; 64 KiB is far past the largest. */
    public static final int MAX_RESPONSE_BYTES = 64 * 1024;

    private SystemOneProtocol() {}

    /**
     * A client of a server that speaks {@code POST /systemone} under its version path.
     *
     * @param base the address the path is appended to, ending in the version path ({@code https://host/v1})
     * @param key  the bearer key, empty for none
     */
    public static TypeSafeClient client(String base, String key, String model, Duration timeout) {
        return client(http(base, key, timeout).build(), "/systemone", model);
    }

    /** A client whose question path is not the protocol's, on a {@code RestClient} the caller finished. */
    public static TypeSafeClient client(RestClient http, String path, String model) {
        return new TypeSafeClient(new TypeSafeApi(path, "/models", http), model, RetryPolicy.noRetry());
    }

    /** The transport of a client: the address, JSON both ways and the key when there is one. */
    public static RestClient.Builder http(String base, String key, Duration timeout) {
        return OutboundHttp.builder(new OutboundHttp.Limits(timeout, MAX_RESPONSE_BYTES))
                .baseUrl(base.replaceAll("/+$", ""))
                .defaultHeaders(headers -> {
                    headers.setContentType(MediaType.APPLICATION_JSON);
                    headers.setAccept(List.of(MediaType.APPLICATION_JSON));
                    if (!key.isEmpty()) headers.setBearerAuth(key);
                });
    }
}
