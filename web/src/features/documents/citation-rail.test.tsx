import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
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

    const cards = screen.getAllByRole("button");
    expect(cards[0]).toHaveAttribute("aria-current", "true");
    expect(cards[1]).not.toHaveAttribute("aria-current");

    await userEvent.click(cards[1]!);
    expect(onActivate).toHaveBeenCalledWith(1);
  });
});
