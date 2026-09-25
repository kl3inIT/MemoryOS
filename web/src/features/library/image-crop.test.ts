import { expect, it } from "vitest";
import { cropPixels, cropType, croppedFileName, rectBetween } from "./image-crop";

const box = { left: 100, top: 50, width: 200, height: 100 };

it("reads a drag in either direction as the same rectangle of the shown image", () => {
  const forwards = rectBetween({ clientX: 150, clientY: 75 }, { clientX: 250, clientY: 125 }, box);
  const backwards = rectBetween({ clientX: 250, clientY: 125 }, { clientX: 150, clientY: 75 }, box);

  expect(forwards).toEqual({ x: 0.25, y: 0.25, width: 0.5, height: 0.5 });
  expect(backwards).toEqual(forwards);
});

it("keeps a drag that leaves the image inside it", () => {
  const rect = rectBetween({ clientX: 200, clientY: 100 }, { clientX: 900, clientY: 900 }, box);

  expect(rect).toEqual({ x: 0.5, y: 0.5, width: 0.5, height: 0.5 });
});

it("measures the crop in the image's own pixels and never encodes an empty one", () => {
  const natural = { width: 1280, height: 721 };

  expect(cropPixels({ x: 0.25, y: 0.5, width: 0.5, height: 0.25 }, natural)).toEqual({
    x: 320,
    y: 361,
    width: 640,
    height: 180,
  });
  expect(cropPixels({ x: 0, y: 0, width: 0, height: 0 }, natural)).toMatchObject({
    width: 1,
    height: 1,
  });
});

it("saves as the image's own type when a canvas can encode it, and as PNG otherwise", () => {
  expect(cropType("image/jpeg")).toBe("image/jpeg");
  expect(cropType("image/heic")).toBe("image/png");
  expect(cropType(undefined)).toBe("image/png");
});

it("names the crop after the file, with the extension it was encoded as", () => {
  expect(croppedFileName("So do kien truc.png", "image/png")).toBe("So do kien truc (đã cắt).png");
  expect(croppedFileName("anh.heic", "image/png")).toBe("anh (đã cắt).png");
  expect(croppedFileName("bao cao", "image/jpeg")).toBe("bao cao (đã cắt).jpg");
});
