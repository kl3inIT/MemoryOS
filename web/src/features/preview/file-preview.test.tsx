import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { i18n } from "@/i18n/index";
import { FilePreview } from "./file-preview";

beforeEach(async () => {
  await i18n.changeLanguage("vi");
  // jsdom has no layout; the located passage is scrolled to in a real browser.
  Element.prototype.scrollIntoView = vi.fn();
});
afterEach(() => {
  Reflect.deleteProperty(Element.prototype, "scrollIntoView");
  cleanup();
});

it("reports each citation where the locator placed it, and a passage not in the file as unlocated", async () => {
  const onPlaced = vi.fn();
  const text = "Doanh thu quý 3 đạt 412 tỷ đồng.\nChi phí quản lý tăng 12%.";
  render(
    <FilePreview
      content={{ kind: "text", text, truncated: false, bytes: text.length }}
      filename="bao-cao.txt"
      download="/api/files/1/content"
      citations={{
        texts: ["Chi phí quản lý tăng 12%.", "Lợi nhuận sau thuế đạt 87 tỷ đồng."],
        onPlaced,
      }}
    />,
  );

  await waitFor(() => expect(onPlaced).toHaveBeenCalledWith(["exact", "none"]));
});

it("offers the download for a file it cannot show", () => {
  render(
    <FilePreview content={{ kind: "doc" }} filename="cu.doc" download="/api/files/2/content" />,
  );

  expect(
    screen.getByText("Không xem trước được tệp .doc cũ. Hãy tải tệp xuống để mở."),
  ).toBeVisible();
  expect(screen.getByRole("link", { name: "Tải xuống" })).toHaveAttribute(
    "href",
    "/api/files/2/content",
  );
});
