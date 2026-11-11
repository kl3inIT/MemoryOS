import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { i18n } from "@/i18n/index";
import { ImageView } from "./image-view";
import type { PdfHighlight } from "./pdf-pages";
import type { ScanLine } from "./scan-line-marks";

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

it("draws a tilted block in its outline and an upright one as its box", () => {
  render(
    view([
      {
        ...box,
        outline: [
          [100, 800],
          [450, 300],
          [500, 400],
          [150, 900],
        ],
      },
      { ...box, top: 1000, bottom: 1200 },
    ]),
  );
  load(1000, 2000);

  const [tilted, upright] = screen.getAllByRole("img", { name: "Vùng được trích dẫn trên ảnh" });
  expect(tilted?.tagName.toLowerCase()).toBe("svg");
  expect(tilted).toHaveAttribute("data-slot", "image-citation-box");
  expect(tilted).toHaveStyle({ left: "10%", top: "15%", width: "40%", height: "30%" });
  expect(tilted?.querySelector("polygon")?.getAttribute("points")).toBe(
    `0,${5 / 6} 0.875,0 1,${1 / 6} 0.125,1`,
  );
  expect(upright?.tagName.toLowerCase()).toBe("span");
  expect(upright).toHaveAttribute("data-slot", "image-citation-box");
});

it("marks the question in its line, the line lighter and the citation's other block faint", () => {
  const tilted: PdfHighlight = {
    ...box,
    outline: [
      [100, 300],
      [500, 300],
      [500, 900],
      [120, 900],
    ],
  };
  const below: PdfHighlight = { ...box, top: 1000, bottom: 1200 };
  const line = (text: string, top: number): ScanLine => ({
    page: 1,
    text,
    origin: "TOPLEFT",
    frame: { width: 1000, height: 2000 },
    points: [
      [120, top],
      [480, top],
      [480, top + 40],
      [120, top + 40],
    ],
  });
  const lines = [line("Điều 5. Nghỉ phép năm", 400), line("Điều 6. Nghỉ ốm", 1050)];
  const { rerender } = render(
    <ImageView
      blob={blob}
      alt="hoa-don.png"
      zoom={100}
      rotation={0}
      boxes={[tilted, below]}
      lines={lines}
      terms={{ phrase: "nghỉ phép năm", words: ["nghỉ", "phép", "năm"] }}
    />,
  );
  load(1000, 2000);

  // jsdom reads no pixels, so the marked line is drawn as its quadrilateral, cut to the block's outline, and the
  // question over it from the share of the line's characters it takes.
  const [cited, other] = screen.getAllByRole("img", { name: "Vùng được trích dẫn trên ảnh" });
  expect(cited).toHaveAttribute("data-shape", "lines");
  const bands = cited!.querySelectorAll("polygon[data-band]");
  expect(bands).toHaveLength(1);
  expect(bands[0]).toHaveAttribute("data-tone", "focus");
  expect(bands[0]?.getAttribute("points")).toBe(
    `0.05,${100 / 600} 0.95,${100 / 600} 0.95,${140 / 600} 0.05,${140 / 600}`,
  );
  const mark = cited!.querySelector('[data-mark="whole"] polygon');
  const xs = mark!
    .getAttribute("points")!
    .split(" ")
    .map((point) => Number(point.split(",")[0]));
  // "Điều 5. " is eight of the line's 21 characters; the question takes the rest.
  expect(Math.min(...xs)).toBeCloseTo(0.05 + (0.9 * 8) / 21, 6);
  expect(Math.max(...xs)).toBeCloseTo(0.95, 6);
  expect(cited!.querySelector('[data-mark="whole"] polyline')).not.toBeNull();
  expect(other).toHaveAttribute("data-tone", "faint");

  // Without a question nothing is narrowed: the regions are drawn as recorded.
  rerender(
    <ImageView
      blob={blob}
      alt="hoa-don.png"
      zoom={100}
      rotation={0}
      boxes={[tilted, below]}
      lines={lines}
    />,
  );
  const [whole, plain] = screen.getAllByRole("img", { name: "Vùng được trích dẫn trên ảnh" });
  expect(whole).toHaveAttribute("data-shape", "outline");
  expect(plain).not.toHaveAttribute("data-tone");
});
