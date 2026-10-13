import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
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
  const texts = ["Chi phí quản lý tăng 12%.", "Lợi nhuận sau thuế đạt 87 tỷ đồng."];
  const preview = () => (
    <FilePreview
      content={{ kind: "text", text, truncated: false, bytes: text.length }}
      filename="bao-cao.txt"
      download="/api/files/1/content"
      citations={{ texts, onPlaced }}
    />
  );
  const { rerender } = render(preview());

  await waitFor(() => expect(onPlaced).toHaveBeenCalledWith(["exact", "none"]));
  // The same citations on the next render are neither placed nor scrolled to again.
  rerender(preview());
  expect(onPlaced).toHaveBeenCalledTimes(1);
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

it("marks a picture's cited region and reports the citations as located, or not when the picture differs", async () => {
  // jsdom has no object URLs.
  URL.createObjectURL = () => "blob:picture";
  URL.revokeObjectURL = vi.fn();
  const onPlaced = vi.fn();
  const boxes = [
    {
      page: 1,
      left: 100,
      top: 300,
      right: 500,
      bottom: 900,
      origin: "TOPLEFT",
      frame: { width: 1000, height: 2000 },
    } as const,
  ];
  const show = (width: number) => {
    const { unmount } = render(
      <FilePreview
        content={{ kind: "image", blob: new Blob(["picture"], { type: "image/png" }) }}
        filename="hoa-don.png"
        download="/api/files/3/content"
        citations={{ texts: ["Tổng cộng 1.200.000"], boxes, onPlaced }}
      />,
    );
    const picture = screen.getByRole("img", { name: "hoa-don.png" });
    Object.defineProperty(picture, "naturalWidth", { value: width });
    Object.defineProperty(picture, "naturalHeight", { value: 2000 });
    fireEvent.load(picture);
    return unmount;
  };

  const unmount = show(1000);
  await waitFor(() => expect(onPlaced).toHaveBeenLastCalledWith(["exact"]));
  expect(screen.getByRole("img", { name: "Vùng được trích dẫn trên ảnh" })).toBeInTheDocument();
  unmount();

  show(1001);
  await waitFor(() => expect(onPlaced).toHaveBeenLastCalledWith(["none"]));
  expect(screen.queryByRole("img", { name: "Vùng được trích dẫn trên ảnh" })).toBeNull();
  Reflect.deleteProperty(URL, "createObjectURL");
  Reflect.deleteProperty(URL, "revokeObjectURL");
});
