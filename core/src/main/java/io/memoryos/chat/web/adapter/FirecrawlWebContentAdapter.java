package io.memoryos.chat.web.adapter;

import static io.memoryos.chat.web.WebCall.base;
import static io.memoryos.chat.web.WebCall.bearer;

import io.memoryos.chat.web.WebCall;
import io.memoryos.chat.web.WebConnectionService;
import io.memoryos.chat.web.WebContentAdapter;
import io.memoryos.chat.web.WebProvider;
import io.memoryos.chat.web.WebProviderCapabilities;
import io.memoryos.chat.web.WebProviderClient;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Firecrawl reads pages only: the main content as Markdown. */
@Component
public final class FirecrawlWebContentAdapter implements WebContentAdapter {
    @Override public WebProvider provider() { return WebProvider.FIRECRAWL; }
    @Override public WebProviderCapabilities capabilities() { return new WebProviderCapabilities(true, false, false, false); }

    @Override public WebProviderClient.Result read(WebCall call, WebConnectionService.Connection connection, String key,
                                                   String url) throws IOException {
        var root = call.json("POST", base(connection, "https://api.firecrawl.dev") + "/v2/scrape", bearer(key),
                Map.of("url", url, "formats", List.of("markdown"), "onlyMainContent", true));
        if (!root.path("success").asBoolean()) throw new IOException("Web extraction failed");
        var data = root.path("data");
        return WebCall.page(url, data.path("metadata").path("title").asString(url), data.path("markdown").asString(""));
    }
}
