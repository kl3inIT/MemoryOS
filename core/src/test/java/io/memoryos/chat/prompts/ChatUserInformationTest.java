package io.memoryos.chat.prompts;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ChatUserInformationTest {
    @Test
    void appendsOnyxUserInformationOnlyForWhatTheMemberHas() {
        assertEquals("Base", ChatPrompts.withUserInformation("Base", null, " ", "", ""));
        assertEquals("""
                Base

                # User Information

                ## Basic Information
                User name: Trần Thu Hà
                User email: ha@tasco.vn
                User role: Kế toán trưởng

                ## User Preferences
                Trả lời ngắn gọn.
                """, ChatPrompts.withUserInformation("Base", "Trần Thu Hà", "ha@tasco.vn", " Kế toán trưởng ",
                "Trả lời ngắn gọn."));
        assertEquals("""
                Base

                # User Information

                ## User Preferences
                Luôn nêu nguồn.
                """, ChatPrompts.withUserInformation("Base", null, null, "", "Luôn nêu nguồn."));
    }
}
