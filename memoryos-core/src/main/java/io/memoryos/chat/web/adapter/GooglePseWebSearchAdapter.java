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

/** Google Programmable Search: the engine identity is the {@code cx} parameter. */
@Component
public final class GooglePseWebSearchAdapter implements WebSearchAdapter {
    @Override public WebProvider provider() { return WebProvider.GOOGLE_PSE; }
    @Override public WebProviderCapabilities capabilities() { return new WebProviderCapabilities(true, false, true, true); }

    @Override public List<WebProviderClient.Result> search(WebCall call, WebConnectionService.Connection connection, String key,
                                                           String query) throws IOException {
        var root = call.json("GET", base(connection, "https://customsearch.googleapis.com") + "/customsearch/v1?key=" + encode(key)
                + "&cx=" + encode(connection.engineId()) + "&q=" + encode(query) + "&num=10", Map.of(), null);
        return WebCall.results(root, "/items", "link", "snippet", null);
    }
}
