import { afterEach, expect, it, vi } from "vitest";
import { drawStrokes, maskFileName, renderMask, toNatural } from "./chat-image-mask";

afterEach(() => {
  vi.restoreAllMocks();
});

function recorder() {
  const calls: string[] = [];
  const context = {
    fillStyle: "",
    strokeStyle: "",
    lineWidth: 0,
    lineCap: "",
    lineJoin: "",
    beginPath: () => calls.push("beginPath"),
    moveTo: (x: number, y: number) => calls.push(`moveTo ${x},${y}`),
    lineTo: (x: number, y: number) => calls.push(`lineTo ${x},${y}`),
    arc: (x: number, y: number, radius: number) => calls.push(`arc ${x},${y},${radius}`),
    fill: () => calls.push("fill"),
    stroke: () => calls.push("stroke"),
    fillRect(_x: number, _y: number, width: number, height: number) {
      calls.push(`fillRect ${this.fillStyle} ${width}x${height}`);
    },
  };
  return { calls, context };
}

it("names the mask after the image it was painted on", () => {
  expect(maskFileName("7d1e0c7a-2f7b-4f1a-9f55-0f5c7b1d8e21")).toBe(
    "mask-for-7d1e0c7a-2f7b-4f1a-9f55-0f5c7b1d8e21.png",
  );
});

it("maps a pointer on the displayed image to natural pixels", () => {
  expect(
    toNatural(
      { clientX: 60, clientY: 30 },
      { left: 10, top: 10, width: 100, height: 50 },
      { width: 1000, height: 500 },
    ),
  ).toEqual({ x: 500, y: 200 });
});

it("draws a tap as a dot and a drag as a round line", () => {
  const { calls, context } = recorder();
  drawStrokes(
    context as unknown as CanvasRenderingContext2D,
    [
      { size: 10, points: [{ x: 5, y: 5 }] },
      {
        size: 4,
        points: [
          { x: 0, y: 0 },
          { x: 8, y: 6 },
        ],
      },
    ],
    "#ffffff",
  );
  expect(calls).toEqual([
    "beginPath",
    "arc 5,5,5",
    "fill",
    "beginPath",
    "moveTo 0,0",
    "lineTo 8,6",
    "stroke",
  ]);
  expect(context.lineWidth).toBe(4);
  expect(context.lineCap).toBe("round");
});

it("paints white on black, or keeps the whole mask white when nothing is painted", async () => {
  const { calls, context } = recorder();
  vi.spyOn(HTMLCanvasElement.prototype, "getContext").mockReturnValue(context as never);
  vi.spyOn(HTMLCanvasElement.prototype, "toBlob").mockImplementation((callback) => {
    callback(new Blob(["png"], { type: "image/png" }));
  });
  await expect(
    renderMask([{ size: 10, points: [{ x: 1, y: 1 }] }], 64, 32),
  ).resolves.toBeInstanceOf(Blob);
  expect(calls[0]).toBe("fillRect #000000 64x32");
  calls.length = 0;
  await renderMask([], 64, 32);
  expect(calls).toEqual(["fillRect #ffffff 64x32"]);
});
