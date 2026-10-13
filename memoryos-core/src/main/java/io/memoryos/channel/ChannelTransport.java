package io.memoryos.channel;

/** A provider transport an adapter opened for one bot. */
public interface ChannelTransport extends AutoCloseable {
    /** Whether it still receives; a transport that gave up reconnecting answers false and is opened again. */
    boolean connected();

    /** Stops receiving and releases what the transport holds, its threads included. */
    @Override
    void close();
}
