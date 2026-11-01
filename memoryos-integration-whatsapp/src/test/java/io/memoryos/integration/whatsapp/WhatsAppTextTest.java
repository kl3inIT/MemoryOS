package io.memoryos.integration.whatsapp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class WhatsAppTextTest {
    @Test
    void emphasisBecomesWhatsAppsOwnMarks() {
        assertEquals("*Quan trọng*: nộp _trước_ thứ Sáu, ~không~ sau.",
                WhatsAppText.of("**Quan trọng**: nộp *trước* thứ Sáu, ~~không~~ sau."));
        assertEquals("Dùng `git pull` rồi chạy lại.", WhatsAppText.of("Dùng `git pull` rồi chạy lại."));
    }

    @Test
    void aHeadingIsABoldLineAndALinkItsTextAndAddress() {
        assertEquals("*Quy trình nghỉ phép*\n\nXem Quy chế (https://memoryos.example/doc) hoặc https://memoryos.example/a.",
                WhatsAppText.of("## Quy trình **nghỉ** phép\n\nXem [Quy chế](https://memoryos.example/doc) hoặc "
                        + "<https://memoryos.example/a>."));
        assertEquals("Sơ đồ (https://memoryos.example/a.png)", WhatsAppText.of("![Sơ đồ](https://memoryos.example/a.png)"));
    }

    @Test
    void listsQuotesAndCodeKeepTheirShape() {
        assertEquals("- Một\n- Hai\n  - Hai rưỡi", WhatsAppText.of("* Một\n* Hai\n    * Hai rưỡi"));
        assertEquals("3. Ba\n4. Bốn", WhatsAppText.of("3. Ba\n4. Bốn"));
        assertEquals("> Trích dẫn\n> dòng hai", WhatsAppText.of("> Trích dẫn\n> dòng hai"));
        assertEquals("```\nSELECT *\nFROM t\n```", WhatsAppText.of("```sql\nSELECT *\nFROM t\n```"));
    }

    @Test
    void aTableIsOneHeaderAndValueLineACell() {
        assertEquals("Phòng: Kế toán\nSố người: 5\n\nPhòng: Nhân sự\nSố người: 3",
                WhatsAppText.of("| Phòng | Số người |\n|---|---:|\n| Kế toán | 5 |\n| Nhân sự | 3 |"));
    }
}
