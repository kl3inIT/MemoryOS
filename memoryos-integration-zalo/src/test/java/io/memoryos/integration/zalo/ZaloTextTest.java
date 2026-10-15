package io.memoryos.integration.zalo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** An answer's markdown made what Zalo shows: what it renders kept, what it shows as typed made plain text. */
class ZaloTextTest {
    @Test
    void whatZaloRendersIsKept() {
        assertEquals("", ZaloText.plain(""));
        assertEquals("**đậm** và *nghiêng*\n\n- một\n- hai", ZaloText.plain("**đậm** và *nghiêng*\n\n- một\n- hai"));
    }

    @Test
    void aLinkIsItsTextAndItsTappableAddress() {
        assertEquals("xem báo cáo (https://a.example/r) nhé", ZaloText.plain("xem [báo cáo](https://a.example/r) nhé"));
        assertEquals("https://a.example", ZaloText.plain("[https://a.example](https://a.example)"));
        assertEquals("tài liệu (/library/1)", ZaloText.plain("[tài liệu](/library/1)"), "an address of any form");
        assertEquals("Wiki (https://vi.wikipedia.org/wiki/A_(B))", ZaloText.plain("[Wiki](https://vi.wikipedia.org/wiki/A_(B))"));
        assertEquals("trang (https://a.example)", ZaloText.plain("[trang](https://a.example \"tiêu đề\")"));
        assertEquals("https://a.example/x", ZaloText.plain("<https://a.example/x>"));
        assertEquals("[không phải link] nhé", ZaloText.plain("[không phải link] nhé"));
    }

    @Test
    void anImageIsItsDescriptionAndAddressOrNothing() {
        assertEquals("sơ đồ (https://a.example/s.png)", ZaloText.plain("![sơ đồ](https://a.example/s.png)"));
        assertEquals("trước  sau", ZaloText.plain("trước ![](https://a.example/s.png) sau"));
    }

    @Test
    void codeIsItsText() {
        assertEquals("chạy npm test rồi báo", ZaloText.plain("chạy `npm test` rồi báo"));
        assertEquals("trước\nfmt.Println(\"x\")\n  thụt lề\nsau", ZaloText.plain("trước\n```go\nfmt.Println(\"x\")\n  thụt lề\n```\nsau"));
        assertEquals("[giữ](nguyên)", ZaloText.plain("```\n[giữ](nguyên)\n```"), "a code block's lines are not rewritten");
    }

    @Test
    void headingsQuotesAndRules() {
        assertEquals("**Tổng quan**\n\nnội dung", ZaloText.plain("# Tổng quan\n\nnội dung"));
        assertEquals("**Mục con**", ZaloText.plain("### **Mục con** ###"));
        assertEquals("được trích\ndòng hai", ZaloText.plain("> được trích\n> dòng hai"));
        assertEquals("lồng", ZaloText.plain("> > lồng"));
        assertEquals("trên\n\ndưới", ZaloText.plain("trên\n\n---\n\ndưới"));
        assertEquals("a\n\nb", ZaloText.plain("a\n\n\n\n\nb"));
    }

    @Test
    void aTableIsOneLineACell() {
        String table = """
                Doanh thu:

                | Quý | Doanh thu | Ghi chú |
                |:---|---:|---|
                | Q1 | 10 tỷ | |
                | Q2 | 12 tỷ | tăng |

                Hết.""";
        assertEquals("Doanh thu:\n\nQuý: Q1\nDoanh thu: 10 tỷ\n\nQuý: Q2\nDoanh thu: 12 tỷ\nGhi chú: tăng\n\nHết.",
                ZaloText.plain(table));
    }
}
