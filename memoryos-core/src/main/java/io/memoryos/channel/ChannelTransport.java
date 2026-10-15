package io.memoryos.channel;

import java.util.Optional;

/** A provider transport an adapter opened for one bot. */
public interface ChannelTransport extends AutoCloseable {
    /** Whether it still receives; a transport that gave up reconnecting answers false and is opened again. */
    boolean connected();

    /** The HTTP end the provider pushes to; empty for a transport that keeps a connection of its own. */
    default Optional<ChannelWebhook> webhook() {
        return Optional.empty();
    }

    /** Stops receiving and releases what the transport holds, its threads included. */
    @Override
    void close();
}
