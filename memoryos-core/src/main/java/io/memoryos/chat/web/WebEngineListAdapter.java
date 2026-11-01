package io.memoryos.chat.web;

import java.io.IOException;
import java.util.List;

/** Lists the search engines a gateway routes to, so an administrator can pick one before saving the connection. */
public interface WebEngineListAdapter extends WebAdapter {
    List<String> engines(WebCall call, String endpoint, String key) throws IOException;
}
