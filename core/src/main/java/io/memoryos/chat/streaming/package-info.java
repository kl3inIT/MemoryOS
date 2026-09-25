/**
 * The reply stream buffer (MEM-26): the API's stream endpoint reads a turn's events through
 * {@link io.memoryos.chat.streaming.StreamBufferWriter.Reader} and bounds its subscriptions with
 * {@link io.memoryos.chat.streaming.ChatStreamProperties}.
 */
@NamedInterface("streaming")
package io.memoryos.chat.streaming;

import org.springframework.modulith.NamedInterface;
