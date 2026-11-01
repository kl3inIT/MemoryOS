package io.memoryos.chat.web.adapter;

import static io.memoryos.chat.web.WebCall.base;
import static io.memoryos.chat.web.WebCall.encode;

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

@Component
public final class BraveWebSearchAdapter implements WebSearchAdapter {
    @Override public WebProvider provider() { return WebProvider.BRAVE; }
    @Override public WebProviderCapabilities capabilities() { return new WebProviderCapabilities(true, false, false, true); }

    @Override public List<WebProviderClient.Result> search(WebCall call, WebConnectionService.Connection connection, String key,
                                                           String query) throws IOException {
        var root = call.json("GET", base(connection, "https://api.search.brave.com") + "/res/v1/web/search?q=" + encode(query)
                + "&count=20", Map.of("X-Subscription-Token", key), null);
        return WebCall.results(root, "/web/results", "url", "description", null);
    }
}
