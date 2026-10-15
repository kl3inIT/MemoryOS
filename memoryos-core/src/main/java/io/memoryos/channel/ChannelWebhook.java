package io.memoryos.channel;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The receiving end of a transport the provider pushes to over HTTP. The capability serves the route and claims each
 * message; the adapter knows the provider's secret and the shape of its request.
 */
public interface ChannelWebhook {
    /**
     * Checks that a request comes from the provider, before reading its body, and reads the messages it carries.
     *
     * @param headers the request's headers, names in lower case
     * @return the messages, possibly none; empty when the request is not the provider's
     */
    Optional<List<ChannelMessage>> accept(Map<String, String> headers, byte[] body);
}
