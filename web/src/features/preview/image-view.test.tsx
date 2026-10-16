import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { i18n } from "@/i18n/index";
import { ImageView } from "./image-view";
import type { PdfHighlight } from "./pdf-pages";

const blob = new Blob(["picture"], { type: "image/png" });
// block_bbox [100, 300, 500, 900] of a picture the OCR read as 1000 x 2000.
const box: PdfHighlight = {
  page: 1,
  left: 100,
  top: 300,
  right: 500,
  bottom: 900,
  origin: "TOPLEFT",
  frame: { width: 1000, height: 2000 },
};

beforeEach(async () => {
  await i18n.changeLanguage("vi");
  // jsdom has no object URLs.
  URL.createObjectURL = () => "blob:picture";
  URL.revokeObjectURL = vi.fn();
});
afterEach(() => {
  Reflect.deleteProperty(URL, "createObjectURL");
  Reflect.deleteProperty(URL, "revokeObjectURL");
  cleanup();
});

/** jsdom decodes nothing, so the picture's size is given and its load announced by hand. */
function load(width: number, height: number) {
  const picture = screen.getByRole("img", { name: "hoa-don.png" });
  Object.defineProperty(picture, "naturalWidth", { value: width });
  Object.defineProperty(picture, "naturalHeight", { value: height });
  fireEvent.load(picture);
}

function view(boxes: readonly PdfHighlight[], onMarked = vi.fn(), zoom = 100, rotation = 0) {
  return (
    <ImageView
      blob={blob}
      alt="hoa-don.png"
      zoom={zoom}
      rotation={rotation}
      boxes={boxes}
      onMarked={onMarked}
    />
  );
}

it("marks the cited region as a share of the picture, in a layer that takes the picture's transform", () => {
  const onMarked = vi.fn();
  const { rerender } = render(view([box], onMarked));
  expect(screen.queryByRole("img", { name: "Vùng được trích dẫn trên ảnh" })).toBeNull();

  load(1000, 2000);

  const region = screen.getByRole("img", { name: "Vùng được trích dẫn trên ảnh" });
  expect(region).toHaveStyle({ left: "10%", top: "15%", width: "40%", height: "30%" });
  expect(onMarked).toHaveBeenCalledExactlyOnceWith(true);
  const picture = screen.getByRole("img", { name: "hoa-don.png" });
  expect(region.parentElement?.style.transform).toBe(picture.style.transform);

  rerender(view([box], onMarked, 150, 90));
  expect(picture.style.transform).toContain("scale(1.5) rotate(90deg)");
  expect(region.parentElement?.style.transform).toBe(picture.style.transform);
  expect(onMarked).toHaveBeenCalledTimes(1);
});

it("draws nothing on a picture that is not the size the region was measured in, and says so", () => {
  const onMarked = vi.fn();
  render(view([box], onMarked));

  // The same picture decoded a quarter turn away from what the OCR read.
  load(2000, 1000);

  expect(screen.queryByRole("img", { name: "Vùng được trích dẫn trên ảnh" })).toBeNull();
  expect(onMarked).toHaveBeenCalledExactlyOnceWith(false);
});

it("draws only framed regions of the picture's one page and reports nothing when there are none", () => {
  const onMarked = vi.fn();
  const { frame: _frame, ...unframed } = box;
  render(view([unframed, { ...box, page: 2 }], onMarked));

  load(1000, 2000);

  expect(screen.queryByRole("img", { name: "Vùng được trích dẫn trên ảnh" })).toBeNull();
  expect(onMarked).not.toHaveBeenCalled();
});
