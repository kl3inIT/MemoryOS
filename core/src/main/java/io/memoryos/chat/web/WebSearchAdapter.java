package io.memoryos.chat.web;

import java.io.IOException;
import java.util.List;

/** Answers a Web query through one provider; {@link WebCall} owns transport, parsing and result validation. */
public interface WebSearchAdapter extends WebAdapter {
    List<WebProviderClient.Result> search(WebCall call, WebConnectionService.Connection connection, String key, String query)
            throws IOException;
}
