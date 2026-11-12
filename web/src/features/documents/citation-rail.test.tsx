import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { assert, describe, expect, it, vi } from "vitest";
import { i18n } from "@/i18n";
import { CitationRail } from "./citation-rail";

const entries = [
  { text: "Doanh thu quý 3 đạt 412 tỷ đồng.", section: "PHẦN 2 > 2.5. Kết quả kinh doanh" },
  { text: "Lợi nhuận sau thuế đạt 87 tỷ đồng." },
];

function rail(
  confidence: Array<"exact" | "approximate" | "none">,
  located = true,
  variant: "search" | "chat" = "search",
  shown = entries,
) {
  const onActivate = vi.fn();
  render(
    <CitationRail
      entries={shown}
      confidence={confidence}
      active={0}
      onActivate={onActivate}
      located={located}
      variant={variant}
    />,
  );
  return onActivate;
}

describe("CitationRail", () => {
  it("counts the passages of a search as the best matches, not as every match in the document", async () => {
    await i18n.changeLanguage("vi");
    rail(["exact", "exact"]);

    // Search ranks passages and keeps only the strongest, so a bare count would read as a total.
    expect(screen.getByRole("heading")).toHaveTextContent("2 đoạn khớp nhất");
  });

  it("reads a single ranked passage without a number in front of it", async () => {
    await i18n.changeLanguage("vi");
    rail(["exact"], true, "search", entries.slice(0, 1));

    expect(screen.getByRole("heading")).toHaveTextContent("Đoạn khớp nhất");
  });

  it("still calls a Chat answer's passages cited, because the answer did cite exactly those", async () => {
    await i18n.changeLanguage("vi");
    rail(["exact", "exact"], true, "chat");

    expect(screen.getByRole("heading")).toHaveTextContent("Các đoạn được trích dẫn");
  });

  it("says which citations are marked in the original and which are not", async () => {
    await i18n.changeLanguage("vi");
    rail(["exact", "none"]);

    const cards = screen.getAllByRole("button");
    expect(cards[0]).toHaveTextContent("Đã đánh dấu");
    // The rule the rail exists for: nothing is drawn for this one, and the reader is told so.
    expect(cards[1]).toHaveTextContent("Không tìm thấy");
    expect(cards[0]).toHaveTextContent("PHẦN 2 > 2.5. Kết quả kinh doanh");
  });

  it("says an original with no text layer was never searched, rather than blaming the passage", async () => {
    await i18n.changeLanguage("vi");
    rail(["none", "none"], false);

    for (const card of screen.getAllByRole("button")) {
      expect(card).toHaveTextContent("Không có lớp văn bản");
      expect(card).not.toHaveTextContent("Không tìm thấy");
    }
  });

  it("hands the opened citation to the reader, and marks the one being read", async () => {
    await i18n.changeLanguage("vi");
    const onActivate = rail(["exact", "exact"]);

    const [first, second] = screen.getAllByRole("button");
    assert.isDefined(second);
    expect(first).toHaveAttribute("aria-current", "true");
    expect(second).not.toHaveAttribute("aria-current");

    await userEvent.click(second);
    expect(onActivate).toHaveBeenCalledWith(1);
  });
});

describe("CitationRail table rows", () => {
  it("shows a table row by its page and heading, not by its labelled cells", async () => {
    await i18n.changeLanguage("vi");
    const row = JSON.stringify({
      source: { page_no: 22, bbox: { l: 1, t: 2, r: 3, b: 4, coord_origin: "TOPLEFT" } },
      tableRow: 3,
    });
    rail(["exact", "exact"], true, "search", [
      {
        text: "Column 1: Các khoản tương đương tiền (1)\nSố cuối năm: 11.045.201.439",
        section: "PHẦN II > Tiền và các khoản tương đương tiền",
        provenance: [row],
      } as (typeof entries)[number],
      entries[1]!,
    ]);

    const [table, prose] = screen.getAllByRole("button");
    expect(table).toHaveTextContent("Trang 22");
    expect(table).toHaveTextContent("Tiền và các khoản tương đương tiền");
    expect(table).not.toHaveTextContent("Column 1");
    expect(table).not.toHaveTextContent("11.045.201.439");
    expect(prose).toHaveTextContent("Lợi nhuận sau thuế đạt 87 tỷ đồng.");
  });

  it("keeps the text of a table row that could not be marked in the original", async () => {
    await i18n.changeLanguage("vi");
    const row = JSON.stringify({ source: { page_no: 22 }, tableRow: 3 });
    rail(["none"], true, "search", [
      { text: "Column 1: Tiền mặt", provenance: [row] } as (typeof entries)[number],
    ]);

    expect(screen.getByRole("button")).toHaveTextContent("Column 1: Tiền mặt");
  });
});

describe("CitationRail table rows in Chat", () => {
  it("keeps a Chat citation's table row text, which the answer quoted", async () => {
    await i18n.changeLanguage("vi");
    const row = JSON.stringify({ source: { page_no: 22 }, tableRow: 3 });
    rail(["exact"], true, "chat", [
      { text: "Column 1: Tiền mặt", provenance: [row] } as (typeof entries)[number],
    ]);

    expect(screen.getByRole("button")).toHaveTextContent("Column 1: Tiền mặt");
  });
});
