package io.memoryos.chat.web.adapter;

import static io.memoryos.chat.web.WebCall.base;

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
public final class SerperWebSearchAdapter implements WebSearchAdapter {
    @Override public WebProvider provider() { return WebProvider.SERPER; }
    @Override public WebProviderCapabilities capabilities() { return new WebProviderCapabilities(true, false, false, true); }

    @Override public List<WebProviderClient.Result> search(WebCall call, WebConnectionService.Connection connection, String key,
                                                           String query) throws IOException {
        var root = call.json("POST", base(connection, "https://google.serper.dev") + "/search", Map.of("X-API-KEY", key),
                Map.of("q", query, "num", 20));
        return WebCall.results(root, "/organic", "link", "snippet", null);
    }
}
