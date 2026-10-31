package io.memoryos.chat.web.adapter;

import static io.memoryos.chat.web.WebCall.bearer;
import static io.memoryos.chat.web.WebCall.endpoint;

import io.memoryos.chat.web.WebCall;
import io.memoryos.chat.web.WebConnectionService;
import io.memoryos.chat.web.WebEngineListAdapter;
import io.memoryos.chat.web.WebProvider;
import io.memoryos.chat.web.WebProviderCapabilities;
import io.memoryos.chat.web.WebProviderClient;
import io.memoryos.chat.web.WebSearchAdapter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The 9Router gateway routes to the configured engine, which it names "model". Its endpoint may already carry the
 * {@code /search} suffix an administrator copied from a URL.
 */
@Component
public final class NineRouterWebAdapter implements WebSearchAdapter, WebEngineListAdapter {
    @Override public WebProvider provider() { return WebProvider.NINEROUTER; }
    @Override public WebProviderCapabilities capabilities() { return new WebProviderCapabilities(true, true, true, false); }

    @Override public List<WebProviderClient.Result> search(WebCall call, WebConnectionService.Connection connection, String key,
                                                           String query) throws IOException {
        var root = call.json("POST", endpoint(connection).replaceAll("/search$", "") + "/search", bearer(key),
                Map.of("model", connection.engineId(), "query", query, "max_results", 20));
        return WebCall.results(root, "/results", "url", "snippet", "content");
    }

    /**
     * 9Router lists its Web engines at {@code GET /v1/models/web}: connected search and fetch providers and combos.
     * Only search engines are returned; a fetch-only entry ({@code kind: webFetch}) cannot answer a query.
     */
    @Override public List<String> engines(WebCall call, String endpoint, String key) throws IOException {
        String base = endpoint.replaceAll("/+$", "").replaceAll("/search$", "");
        var root = call.json("GET", base + "/models/web", bearer(key), null);
        var engines = new ArrayList<String>();
        for (var item : root.path("data")) {
            if ("webFetch".equals(item.path("kind").asString(""))) continue;
            String id = item.path("id").asString("");
            if (!id.isBlank() && id.length() <= 200 && !engines.contains(id)) engines.add(id);
            if (engines.size() >= 200) break;
        }
        return List.copyOf(engines);
    }
}
