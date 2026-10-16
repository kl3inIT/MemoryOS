package io.memoryos.chat.prompts;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ChatUserInformationTest {
    @Test
    void appendsOnyxUserInformationOnlyForWhatTheMemberHas() {
        assertEquals("Base", ChatPrompts.withUserInformation("Base", null, " ", "", "", " "));
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
                "Trả lời ngắn gọn.", ""));
        assertEquals("""
                Base

                # User Information

                ## User Preferences
                Luôn nêu nguồn.
                """, ChatPrompts.withUserInformation("Base", null, null, "", "Luôn nêu nguồn.", ""));
    }

    @Test
    void theMemoryIsTheLastPartOfTheUserInformation() {
        assertEquals("""
                Base

                # User Information

                ## Basic Information
                User name: Trần Thu Hà
                User email: ha@tasco.vn

                ## User Preferences
                Trả lời ngắn gọn.

                ## User Memories
                Đang làm báo cáo quý 3 cho ban giám đốc.
                """, ChatPrompts.withUserInformation("Base", "Trần Thu Hà", "ha@tasco.vn", "", "Trả lời ngắn gọn.",
                " Đang làm báo cáo quý 3 cho ban giám đốc. "));
        assertEquals("""
                Base

                # User Information

                ## User Memories
                Thích bảng.
                """, ChatPrompts.withUserInformation("Base", null, null, "", "", "Thích bảng."));
    }
}
