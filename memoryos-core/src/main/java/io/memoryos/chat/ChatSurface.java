package io.memoryos.chat;

/**
 * Where a conversation is held. New conversations are held on the web. {@code CHANNEL} marks those the chat bots
 * held before they were removed (MEM-246, ADR 0032): they stay their owners' and in the administrator's chat history,
 * while the sidebar and the conversation search list those of the web only.
 */
public enum ChatSurface {
    WEB,
    CHANNEL
}
