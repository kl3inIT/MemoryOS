package io.memoryos.chat;

/**
 * Where a conversation is held. A conversation of a channel, such as a chat with the Lark bot, is its owner's like
 * any other and opens by its address, but the sidebar and the conversation search list those of the web only; the
 * administrator's chat history lists both (MEM-246).
 */
public enum ChatSurface {
    WEB,
    CHANNEL
}
