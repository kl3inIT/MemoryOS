package io.memoryos.chat;

/**
 * The messaging platform a turn was asked on, which the system prompt names (OpenClaw {@code buildGroupChatContext}).
 *
 * @param platform the platform's name as people know it, such as {@code Zalo}
 * @param group    whether the question came from a group, where each question is labelled with its sender's name
 * @param publicBot whether the bot answers anyone (MEM-298), so the conversation reads its assistant's attachments
 *                 only and offers no code interpreter
 */
public record ChatChannel(String platform, boolean group, boolean publicBot) {
    public ChatChannel {
        if (platform == null || platform.isBlank()) throw new IllegalArgumentException("A channel names its platform.");
    }
}
