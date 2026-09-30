package io.memoryos.chat.web;

import java.io.IOException;

/** Reads one public page through one provider instead of the built-in reader. */
public interface WebContentAdapter extends WebAdapter {
    WebProviderClient.Result read(WebCall call, WebConnectionService.Connection connection, String key, String url)
            throws IOException;
}
