package io.memoryos.chat;

import org.jspecify.annotations.Nullable;

/**
 * A conversation search hit. {@code snippet} is a short fragment of the newest matching message with each query token
 * wrapped in {@link #MATCH_START}/{@link #MATCH_END}; it is null for recent sessions, title-only matches and messages
 * above the 64 KiB search index bound.
 */
public record ChatSessionMatch(ChatSession session, @Nullable String snippet) {
    public static final char MATCH_START = '\uE000';
    public static final char MATCH_END = '\uE001';
}
