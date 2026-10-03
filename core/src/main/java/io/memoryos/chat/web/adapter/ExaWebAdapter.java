package io.memoryos.chat.web.adapter;

import static io.memoryos.chat.web.WebCall.base;

import io.memoryos.chat.web.WebCall;
import io.memoryos.chat.web.WebConnectionService;
import io.memoryos.chat.web.WebContentAdapter;
import io.memoryos.chat.web.WebProvider;
import io.memoryos.chat.web.WebProviderCapabilities;
import io.memoryos.chat.web.WebProviderClient;
import io.memoryos.chat.web.WebSearchAdapter;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Exa search and contents; one connection serves both. Exa does not accept the {@code site:} operator. */
@Component
public final class ExaWebAdapter implements WebSearchAdapter, WebContentAdapter {
    private static final String PUBLIC_BASE = "https://api.exa.ai";

    @Override public WebProvider provider() { return WebProvider.EXA; }
    @Override public WebProviderCapabilities capabilities() { return new WebProviderCapabilities(true, false, false, false); }

    @Override public List<WebProviderClient.Result> search(WebCall call, WebConnectionService.Connection connection, String key,
                                                           String query) throws IOException {
        var root = call.json("POST", base(connection, PUBLIC_BASE) + "/search", Map.of("x-api-key", key),
                Map.of("query", query, "numResults", 20));
        return WebCall.results(root, "/results", "url", "text", null);
    }

    @Override public WebProviderClient.Result read(WebCall call, WebConnectionService.Connection connection, String key,
                                                   String url) throws IOException {
        var root = call.json("POST", base(connection, PUBLIC_BASE) + "/contents", Map.of("x-api-key", key),
                Map.of("ids", List.of(url), "text", true)).path("results").path(0);
        if (root.isMissingNode()) throw new IOException("Web extraction failed");
        return WebCall.page(url, root.path("title").asString(url), root.path("text").asString(""));
    }
}
