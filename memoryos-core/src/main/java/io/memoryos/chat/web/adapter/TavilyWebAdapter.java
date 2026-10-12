package io.memoryos.chat.web.adapter;

import static io.memoryos.chat.web.WebCall.base;
import static io.memoryos.chat.web.WebCall.bearer;

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

/** Tavily search and extract; one connection serves both. */
@Component
public final class TavilyWebAdapter implements WebSearchAdapter, WebContentAdapter {
    private static final String PUBLIC_BASE = "https://api.tavily.com";

    @Override public WebProvider provider() { return WebProvider.TAVILY; }
    @Override public WebProviderCapabilities capabilities() { return new WebProviderCapabilities(true, false, false, true); }

    @Override public List<WebProviderClient.Result> search(WebCall call, WebConnectionService.Connection connection, String key,
                                                           String query) throws IOException {
        var root = call.json("POST", base(connection, PUBLIC_BASE) + "/search", bearer(key),
                Map.of("query", query, "max_results", 20, "search_depth", "basic", "include_answer", false));
        return WebCall.results(root, "/results", "url", "content", null);
    }

    @Override public WebProviderClient.Result read(WebCall call, WebConnectionService.Connection connection, String key,
                                                   String url) throws IOException {
        var root = call.json("POST", base(connection, PUBLIC_BASE) + "/extract", bearer(key), Map.of("urls", List.of(url)))
                .path("results").path(0);
        if (root.isMissingNode()) throw new IOException("Web extraction failed");
        return WebCall.page(url, root.path("title").asString(url), root.path("raw_content").asString(""));
    }
}
