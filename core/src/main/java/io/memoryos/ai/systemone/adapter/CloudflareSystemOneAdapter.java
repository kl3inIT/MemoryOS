package io.memoryos.ai.systemone.adapter;

import io.memoryos.ai.systemone.SystemOneAdapter;
import io.memoryos.ai.systemone.SystemOneCapabilities;
import io.memoryos.ai.systemone.SystemOneClient;
import io.memoryos.ai.systemone.SystemOneConnectionService;
import io.memoryos.ai.systemone.SystemOneProtocol;
import io.memoryos.ai.systemone.SystemOneProvider;
import io.memoryos.shared.OutboundHttp;
import java.time.Duration;
import java.util.LinkedHashMap;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Clef and Clef-flash on Cloudflare Workers AI. The request body and the answer have the protocol's shape, but the
 * address is built from the account and the model, and Workers AI may wrap the answer in its
 * {@code {result, success}} envelope, which the SDK's typed answer does not read; so this is one {@code RestClient}
 * call read as a tree (ADR 0025, choice 3). The connection's endpoint holds the account ID.
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

    @Override public SystemOneClient.Decision choose(SystemOneConnectionService.Connection connection, String key,
                                                     SystemOneClient.Question question, Duration timeout) {
        // The body names the model without its namespace, the address with it.
        String model = connection.model().startsWith(NAMESPACE)
                ? connection.model().substring(NAMESPACE.length()) : connection.model();
        var root = OutboundHttp.builder(new OutboundHttp.Limits(timeout, SystemOneProtocol.MAX_RESPONSE_BYTES)).build()
                .post().uri(accounts + "/{account}/ai/run/" + NAMESPACE + "{model}", connection.endpoint(), model)
                .headers(headers -> headers.setBearerAuth(key))
                .contentType(MediaType.APPLICATION_JSON)
                .body(SystemOneProtocol.request(question).withModel(model))
                .retrieve().body(JsonNode.class);
        if (root == null) throw new IllegalStateException("Cloudflare returned no answer");
        var answer = root.path("result").isObject() ? root.path("result") : root;
        var choice = answer.path("answers").path(SystemOneProtocol.QUESTION);
        var probabilities = new LinkedHashMap<String, Double>();
        for (var entry : choice.path("probabilities").properties())
            if (entry.getValue().isNumber()) probabilities.put(entry.getKey(), entry.getValue().asDouble());
        return new SystemOneClient.Decision(choice.path("choice").asString(""), choice.path("confidence").asDouble(0),
                probabilities, tokens(answer.path("usage").path("input_tokens")),
                tokens(answer.path("usage").path("output_tokens")));
    }

    private static @Nullable Long tokens(JsonNode count) {
        return count.isIntegralNumber() ? count.asLong() : null;
    }
}
