package io.memoryos.chat.web.adapter;

import static io.memoryos.chat.web.WebCall.bearer;
import static io.memoryos.chat.web.WebCall.encode;
import static io.memoryos.chat.web.WebCall.endpoint;

import io.memoryos.chat.web.WebCall;
import io.memoryos.chat.web.WebConnectionService;
import io.memoryos.chat.web.WebProvider;
import io.memoryos.chat.web.WebProviderCapabilities;
import io.memoryos.chat.web.WebProviderClient;
import io.memoryos.chat.web.WebSearchAdapter;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** A self-hosted SearXNG instance; a key is optional and sent as a bearer token when configured. */
@Component
public final class SearxngWebSearchAdapter implements WebSearchAdapter {
    @Override public WebProvider provider() { return WebProvider.SEARXNG; }
    @Override public WebProviderCapabilities capabilities() { return new WebProviderCapabilities(false, true, false, true); }

    @Override public List<WebProviderClient.Result> search(WebCall call, WebConnectionService.Connection connection, String key,
                                                           String query) throws IOException {
        var root = call.json("GET", endpoint(connection) + "/search?q=" + encode(query) + "&format=json",
                key.isEmpty() ? Map.of() : bearer(key), null);
        return WebCall.results(root, "/results", "url", "content", null);
    }
}
