// @vitest-environment jsdom
import { beforeEach, describe, expect, it } from "vitest";
import { placeCitations } from "./citation-highlight";

function render(html: string) {
  document.body.innerHTML = `<div id="doc">${html}</div>`;
  return document.querySelector("#doc")!;
}

beforeEach(() => {
  document.body.innerHTML = "";
});

describe("placeCitations", () => {
  it("places a passage that the renderer split across two paragraphs", () => {
    const container = render("<p>Doanh thu quý 3 </p><p>đạt 412 tỷ đồng.</p>");
    const [placed] = placeCitations(container, ["Doanh thu quý 3 đạt 412 tỷ đồng."]);
    expect(placed?.confidence).toBe("exact");
    expect(placed?.range?.toString()).toBe("Doanh thu quý 3 đạt 412 tỷ đồng.");
  });

  it("places a passage that stops inside a styled run", () => {
    const container = render("<p>Doanh thu <strong>412 tỷ</strong> đồng, tăng 6,2%.</p>");
    const [placed] = placeCitations(container, ["Doanh thu 412 tỷ"]);
    expect(placed?.confidence).toBe("exact");
    expect(placed?.range?.toString()).toBe("Doanh thu 412 tỷ");
  });

  it("gives an absent passage no range at all", () => {
    const container = render("<p>Doanh thu quý 3 đạt 412 tỷ đồng.</p>");
    const [placed] = placeCitations(container, ["Lợi nhuận sau thuế đạt 87 tỷ đồng."]);
    expect(placed).toEqual({ confidence: "none" });
  });

  it("places each passage of a citation, keeping the caller's order", () => {
    const container = render(
      "<p>Doanh thu quý 3 đạt 412 tỷ đồng.</p><p>Chi phí quản lý tăng 12%.</p>",
    );
    const placed = placeCitations(container, [
      "Chi phí quản lý tăng 12%.",
      "Không có trong tài liệu.",
      "Doanh thu quý 3 đạt 412 tỷ đồng.",
    ]);
    expect(placed.map((entry) => entry.confidence)).toEqual(["exact", "none", "exact"]);
    expect(placed[0]?.range?.toString()).toBe("Chi phí quản lý tăng 12%.");
    expect(placed[2]?.range?.toString()).toBe("Doanh thu quý 3 đạt 412 tỷ đồng.");
  });

  it("reads through nested markup without counting it as text", () => {
    const container = render(
      "<section><p><span>Doanh thu </span><em>quý 3</em> đạt <b>412</b> tỷ đồng.</p></section>",
    );
    const [placed] = placeCitations(container, ["Doanh thu quý 3 đạt 412 tỷ đồng."]);
    expect(placed?.confidence).toBe("exact");
    expect(placed?.range?.toString()).toBe("Doanh thu quý 3 đạt 412 tỷ đồng.");
  });

  const question = (phrase: string) => ({ phrase, words: phrase.split(" ") });
  const marks = (placement: ReturnType<typeof placeCitations>[number] | undefined) =>
    placement?.marks?.map((mark) => `${mark.whole ? "*" : ""}${mark.range.toString()}`);

  it("marks the whole question inside a placed passage only, with the sentence that holds it", () => {
    const container = render(
      "<p>Chi phí quản lý tăng.</p><p>Năm nay chi phí <strong>quản lý</strong> tăng 12%. Doanh thu giảm.</p>",
    );
    const placed = placeCitations(
      container,
      ["Năm nay chi phí quản lý tăng 12%. Doanh thu giảm.", "Không có trong tài liệu."],
      [],
      question("chi phí quản lý"),
    );
    // The first paragraph also holds the question, but it is not a cited passage.
    expect(marks(placed[0])).toEqual(["*chi phí quản lý"]);
    expect(placed[0]?.focus?.map((range) => range.toString())).toEqual([
      "Năm nay chi phí quản lý tăng 12%.",
    ]);
    expect(placed[1]).toEqual({ confidence: "none" });
  });

  it("marks the question's words alone where the passage does not hold it", () => {
    const container = render("<p>Chi phí bán hàng tăng 12%.</p>");
    const [placed] = placeCitations(
      container,
      ["Chi phí bán hàng tăng 12%."],
      [],
      question("chi phí quản lý"),
    );
    expect(marks(placed)).toEqual(["Chi", "phí"]);
  });

  it("never runs a sentence from one block into the next", () => {
    // Nothing ends the heading's text but its element.
    const container = render("<h2>Nghỉ phép năm</h2><p> Người lao động được nghỉ 12 ngày.</p>");
    const [placed] = placeCitations(
      container,
      ["Nghỉ phép năm Người lao động được nghỉ 12 ngày."],
      [],
      question("được nghỉ"),
    );
    expect(placed?.focus?.map((range) => range.toString())).toEqual([
      "Người lao động được nghỉ 12 ngày.",
    ]);
  });

  it("marks nothing when no question was asked, and nothing when none of it occurs", () => {
    const container = render("<p>Chi phí quản lý tăng 12%.</p>");
    expect(placeCitations(container, ["Chi phí quản lý tăng 12%."])[0]?.marks).toBeUndefined();
    const [placed] = placeCitations(
      container,
      ["Chi phí quản lý tăng 12%."],
      [],
      question("doanh thu"),
    );
    expect(placed?.marks).toEqual([]);
    expect(placed?.focus).toEqual([]);
  });

  it("places nothing in an empty container", () => {
    expect(placeCitations(render(""), ["Doanh thu quý 3."])).toEqual([{ confidence: "none" }]);
  });
});
