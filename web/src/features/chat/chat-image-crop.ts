/**
 * Cropping an image the preview already holds. The bytes never leave the browser until the owner saves the
 * result, which is then an ordinary upload: no route of its own and no server-side editing.
 */

/** A crop in fractions of the shown image: (0,0) is its top-left corner, 1 its full width or height. */
export type CropRect = { x: number; y: number; width: number; height: number };

/** A drag shorter than this on both sides is a click on the image, not a selection. */
export const MIN_CROP = 0.02;

/** Canvas can only encode these; anything else is saved as PNG, which is lossless for what it holds. */
const ENCODABLE = ["image/png", "image/jpeg", "image/webp"];

export function cropType(mediaType: string | undefined): string {
  return mediaType && ENCODABLE.includes(mediaType) ? mediaType : "image/png";
}

/** The rectangle two pointer positions describe inside the shown image, clamped to it. */
export function rectBetween(
  start: { clientX: number; clientY: number },
  end: { clientX: number; clientY: number },
  box: { left: number; top: number; width: number; height: number },
): CropRect {
  const fraction = (value: number, from: number, length: number) =>
    length <= 0 ? 0 : Math.min(1, Math.max(0, (value - from) / length));
  const x1 = fraction(start.clientX, box.left, box.width);
  const x2 = fraction(end.clientX, box.left, box.width);
  const y1 = fraction(start.clientY, box.top, box.height);
  const y2 = fraction(end.clientY, box.top, box.height);
  return {
    x: Math.min(x1, x2),
    y: Math.min(y1, y2),
    width: Math.abs(x2 - x1),
    height: Math.abs(y2 - y1),
  };
}

/** The crop in the image's own pixels, which is what the saved file measures. */
export function cropPixels(rect: CropRect, natural: { width: number; height: number }) {
  return {
    x: Math.round(rect.x * natural.width),
    y: Math.round(rect.y * natural.height),
    width: Math.max(1, Math.round(rect.width * natural.width)),
    height: Math.max(1, Math.round(rect.height * natural.height)),
  };
}

/** `name (đã cắt).png`, with the extension the encoded type actually has. */
export function croppedFileName(filename: string, type: string): string {
  const extension = type === "image/jpeg" ? ".jpg" : type === "image/webp" ? ".webp" : ".png";
  const dot = filename.lastIndexOf(".");
  const base = dot > 0 ? filename.slice(0, dot) : filename;
  return `${base} (đã cắt)${extension}`;
}

/** Draws the selected part of the loaded image into a canvas of exactly that size and encodes it. */
export async function renderCrop(
  image: CanvasImageSource & { width: number; height: number },
  rect: CropRect,
  type: string,
): Promise<Blob> {
  const area = cropPixels(rect, { width: image.width, height: image.height });
  const canvas = document.createElement("canvas");
  canvas.width = area.width;
  canvas.height = area.height;
  const context = canvas.getContext("2d");
  if (!context) throw new Error("Canvas is unavailable");
  context.drawImage(image, area.x, area.y, area.width, area.height, 0, 0, area.width, area.height);
  const { promise, resolve, reject } = Promise.withResolvers<Blob>();
  canvas.toBlob(
    (blob) => (blob ? resolve(blob) : reject(new Error("Image encoding failed"))),
    type,
  );
  return promise;
}
