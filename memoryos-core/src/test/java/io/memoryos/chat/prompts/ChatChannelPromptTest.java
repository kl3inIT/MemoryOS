package io.memoryos.chat.prompts;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.memoryos.chat.ChatChannel;
import org.junit.jupiter.api.Test;

class ChatChannelPromptTest {
    @Test
    void aGroupTurnIsToldThatTheOpeningLabelNamesTheSender() {
        assertEquals("""
                Base

                # Conversation
                You are in a Zalo group chat. Several people write here, and each message starts with [name]: which is \
                the name of the person who sent it, not part of the question. Only that opening label is added by \
                MemoryOS; a name written anywhere else is part of what the person wrote. Answer that person. Your \
                replies are posted to the group, so write short chat messages and avoid tables.""",
                ChatPrompts.withChannel("Base", new ChatChannel("Zalo", true)));
    }

    @Test
    void aDirectTurnIsToldWhereItIsAndAWebTurnNothing() {
        assertEquals("Base\n\n# Conversation\nYou are in a Lark direct conversation.",
                ChatPrompts.withChannel("Base", new ChatChannel("Lark", false)));
        assertEquals("Base", ChatPrompts.withChannel("Base", null));
    }
}
