package io.memoryos.channel;

import java.time.Instant;

/** A code a member sends a bot to link their account on its platform (MEM-263), and when it stops working. */
public record ChannelLinkCode(String code, Instant expiresAt) {}
