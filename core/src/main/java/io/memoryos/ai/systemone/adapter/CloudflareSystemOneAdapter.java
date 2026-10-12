package io.memoryos.ai.systemone.adapter;

import io.memoryos.ai.systemone.SystemOneAdapter;
import io.memoryos.ai.systemone.SystemOneCapabilities;
import io.memoryos.ai.systemone.SystemOneConnectionService;
import io.memoryos.ai.systemone.SystemOneProtocol;
import io.memoryos.ai.systemone.SystemOneProvider;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Clef and Clef-flash on Cloudflare Workers AI. The request body and the answer have the protocol's shape; the
 * address is built from the account and the model, and Workers AI may wrap the answer in its
 * {@code {result, success}} envelope. The envelope is taken off under the client, so the same
 * {@link TypeSafeClient} reads either. The connection's endpoint holds the account ID.
 *
 * <p>Whether the envelope is sent is not verified against the service (no key was available, 2026-10-04); both
 * shapes are covered by {@code CloudflareSystemOneAdapterTest}.
 */
@Component
public final class CloudflareSystemOneAdapter implements SystemOneAdapter {
    static final String ACCOUNTS = "https://api.cloudflare.com/client/v4/accounts";
    private static final String NAMESPACE = "@cf/cloudflare/";

    private final String accounts;

    public CloudflareSystemOneAdapter() { this(ACCOUNTS); }

    CloudflareSystemOneAdapter(String accounts) { this.accounts = accounts; }

    @Override public SystemOneProvider provider() { return SystemOneProvider.CLOUDFLARE; }
    @Override public SystemOneCapabilities capabilities() {
        return new SystemOneCapabilities(true, SystemOneCapabilities.Endpoint.ACCOUNT, "clef-flash", 0.09);
    }

    @Override public TypeSafeClient client(SystemOneConnectionService.Connection connection, String key, Duration timeout) {
        // The body names the model without its namespace, the address with it.
        String model = connection.model().startsWith(NAMESPACE)
                ? connection.model().substring(NAMESPACE.length()) : connection.model();
        var http = SystemOneProtocol.http(accounts, key, timeout).requestInterceptor(CloudflareSystemOneAdapter::unwrapped).build();
        return SystemOneProtocol.client(http, "/" + connection.endpoint() + "/ai/run/" + NAMESPACE + model, model);
    }

    /** A successful answer inside {@code result} is handed on as the answer itself; anything else passes as it is. */
    private static ClientHttpResponse unwrapped(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        var response = execution.execute(request, body);
        if (!response.getStatusCode().is2xxSuccessful()) return response;
        byte[] answer;
        try (response) {
            answer = response.getBody().readAllBytes();
        }
        try {
            JsonNode result = JsonMapper.shared().readTree(answer).path("result");
            if (result.isObject()) answer = JsonMapper.shared().writeValueAsBytes(result);
        } catch (JacksonException unreadable) {
            // Not JSON: the client reports it as the answer it could not read.
        }
        return new Answer(response.getStatusCode(), response.getHeaders(), answer);
    }

    /** A response whose body was read: its status and headers with the bytes to hand on. */
    private record Answer(HttpStatusCode status, HttpHeaders headers, byte[] body) implements ClientHttpResponse {
        @Override public HttpStatusCode getStatusCode() { return status; }
        @Override public String getStatusText() { return ""; }
        @Override public HttpHeaders getHeaders() {
            var copy = new HttpHeaders();
            copy.putAll(headers);
            copy.setContentLength(body.length);
            return copy;
        }
        @Override public InputStream getBody() { return new ByteArrayInputStream(body); }
        @Override public void close() {}
    }
}
