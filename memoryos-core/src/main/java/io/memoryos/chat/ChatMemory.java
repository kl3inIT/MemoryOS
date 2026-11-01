package io.memoryos.chat;

/**
 * What Chat remembers about one member across conversations (MEM-250): Onyx's {@code ## User Memories} as one
 * document, as Dust keeps it. {@code revision} 0 is a member who has never had a memory written.
 */
public record ChatMemory(String content, boolean enabled, long revision) {
    public static final int MAX_CONTENT = 4000;
    public static final ChatMemory EMPTY = new ChatMemory("", true, 0);

    public ChatMemory {
        content = content == null ? "" : content.strip();
    }
}
