package io.memoryos.channel;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The receiving end of a transport the provider pushes to over HTTP. The capability serves the route and claims each
 * message; the adapter knows the provider's secret and the shape of its request.
 */
public interface ChannelWebhook {
    /** The path, under the deployment's public address, at which the capability serves a bot's webhook. */
    static String path(UUID connectionId) {
        return "/hooks/channels/" + connectionId;
    }

    /**
     * Whether a request comes from the provider, by its headers alone, so that a stranger's body is never read.
     *
     * @param headers the request's headers, names in lower case
     */
    boolean authorized(Map<String, String> headers);

    /**
     * Reads the messages an authorized request carries; it checks the headers again, so a body is never read for a
     * request that is not the provider's.
     *
     * @param headers the request's headers, names in lower case
     * @return the messages, possibly none; empty when the request is not the provider's
     */
    Optional<List<ChannelMessage>> accept(Map<String, String> headers, byte[] body);

    /**
     * Gives the provider this end's address once the capability serves it, so that the provider's check of the address
     * reaches it, and says whether that check arrived.
     *
     * @throws ChannelException {@code CHANNEL_WEBHOOK_UNREACHABLE} when the provider could not reach it
     */
    void register();
}
