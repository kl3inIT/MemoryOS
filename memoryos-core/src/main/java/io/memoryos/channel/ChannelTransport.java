package io.memoryos.channel;

import java.util.Optional;

/** A provider transport an adapter opened for one bot. */
public interface ChannelTransport extends AutoCloseable {
    /** Whether it still receives; a transport that gave up reconnecting answers false and is opened again. */
    boolean connected();

    /**
     * Why a transport that is no longer {@link #connected()} stopped, when opening it again cannot mend it until an
     * administrator changes the bot (MEM-283: WhatsApp logged the device out, banned the number, or another process
     * opened the session); empty when opening it again may.
     */
    default Optional<ChannelException> stopped() {
        return Optional.empty();
    }

    /**
     * The HTTP end the provider pushes to; empty for a transport that keeps a connection of its own. The capability
     * serves it at {@code /hooks/channels/<connection id>} and then calls {@link ChannelWebhook#register()}.
     */
    default Optional<ChannelWebhook> webhook() {
        return Optional.empty();
    }

    /** Stops receiving and releases what the transport holds, its threads included. */
    @Override
    void close();
}
