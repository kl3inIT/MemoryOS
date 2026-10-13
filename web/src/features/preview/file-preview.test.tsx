import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import type * as CodeRenderers from "@/components/assistant-ui/elements/code-renderers.aui";
import { i18n } from "@/i18n/index";
import { FilePreview } from "./file-preview";

// Source is drawn plain and then again as other markup of the same text, as the syntax highlighter does once
// it has loaded; here the second rendering arrives on a timer instead of with a grammar.
vi.mock("@/components/assistant-ui/elements/code-renderers.aui", async (importOriginal) => {
  const { useEffect, useState } = await import("react");
  return {
    ...(await importOriginal<typeof CodeRenderers>()),
    HighlightedCode: ({ code }: { code: string }) => {
      const [highlighted, setHighlighted] = useState(false);
      useEffect(() => {
        const loaded = setTimeout(() => setHighlighted(true), 20);
        return () => clearTimeout(loaded);
      }, []);
      return (
        <pre data-highlighted={highlighted || undefined}>
          {highlighted ? (
            code.split("\n").map((line) => <span key={line}>{`${line}\n`}</span>)
          ) : (
            <code>{code}</code>
          )}
        </pre>
      );
    },
  };
});

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

it("paints the reader's words over the located passage, above both passage highlights", async () => {
  // jsdom has no Custom Highlight API; this registry records what the preview registers.
  const registry = new Map<string, { ranges: Range[]; priority: number }>();
  vi.stubGlobal(
    "Highlight",
    class {
      priority = 0;
      ranges: Range[];
      constructor(...ranges: Range[]) {
        this.ranges = ranges;
      }
    },
  );
  vi.stubGlobal("CSS", { highlights: registry });
  const text = "Doanh thu quý 3 đạt 412 tỷ đồng.\nChi phí quản lý tăng 12%.";
  render(
    <FilePreview
      content={{ kind: "text", text, truncated: false, bytes: text.length }}
      filename="bao-cao.txt"
      download="/api/files/1/content"
      citations={{ texts: ["Chi phí quản lý tăng 12%."], terms: ["quản lý", "doanh thu"] }}
    />,
  );

  await waitFor(() => expect(registry.has("memoryos-citation-term")).toBe(true));
  const terms = registry.get("memoryos-citation-term");
  // "Doanh thu" is in the file but outside the cited passage, so it is left alone.
  expect(terms?.ranges.map((range) => range.toString())).toEqual(["quản lý"]);
  expect(terms?.priority).toBeGreaterThan(registry.get("memoryos-citation-active")?.priority ?? 0);
  vi.unstubAllGlobals();
});

it("keeps a passage marked in source once the highlighter has replaced the plain rendering", async () => {
  const registry = new Map<string, { ranges: Range[] }>();
  vi.stubGlobal(
    "Highlight",
    class {
      priority = 0;
      ranges: Range[];
      constructor(...ranges: Range[]) {
        this.ranges = ranges;
      }
    },
  );
  vi.stubGlobal("CSS", { highlights: registry });
  const text = "Doanh thu quý 3 đạt 412 tỷ đồng.\nChi phí quản lý tăng 12%.";
  const { container } = render(
    <FilePreview
      content={{ kind: "text", text, truncated: false, bytes: text.length }}
      filename="bao-cao.txt"
      download="/api/files/1/content"
      citations={{ texts: ["Chi phí quản lý tăng 12%."] }}
    />,
  );
  const marked = () => registry.get("memoryos-citation-active")?.ranges[0];
  await waitFor(() => expect(marked()?.toString()).toBe("Chi phí quản lý tăng 12%."));

  // The highlighter's own markup takes the place of the plain text the passage was first found in.
  await waitFor(() => expect(container.querySelector("pre[data-highlighted]")).not.toBeNull());
  await waitFor(() => expect(marked()?.toString()).toBe("Chi phí quản lý tăng 12%."));
  expect(marked()?.collapsed).toBe(false);
  vi.unstubAllGlobals();
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
