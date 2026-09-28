/** A brush stroke in the image's natural pixels; `size` is the brush diameter. */
export type MaskStroke = { size: number; points: { x: number; y: number }[] };

/** The server reads which image a mask belongs to from this name (MEM-109). */
export function maskFileName(imageId: string) {
  return `mask-for-${imageId}.png`;
}

/** A pointer position on the displayed image, in the image's natural pixels. */
export function toNatural(
  point: { clientX: number; clientY: number },
  box: { left: number; top: number; width: number; height: number },
  natural: { width: number; height: number },
) {
  return {
    x: ((point.clientX - box.left) / box.width) * natural.width,
    y: ((point.clientY - box.top) / box.height) * natural.height,
  };
}

/** Round strokes; a single point is a dot of the brush diameter. */
export function drawStrokes(
  context: CanvasRenderingContext2D,
  strokes: MaskStroke[],
  color: string,
) {
  context.strokeStyle = color;
  context.fillStyle = color;
  context.lineCap = "round";
  context.lineJoin = "round";
  for (const stroke of strokes) {
    const [first, ...rest] = stroke.points;
    if (!first) continue;
    context.beginPath();
    if (rest.length === 0) {
      context.arc(first.x, first.y, stroke.size / 2, 0, Math.PI * 2);
      context.fill();
      continue;
    }
    context.lineWidth = stroke.size;
    context.moveTo(first.x, first.y);
    for (const point of rest) context.lineTo(point.x, point.y);
    context.stroke();
  }
}

/** White marks what may change and black what must stay; without strokes the whole image may change. */
export function renderMask(strokes: MaskStroke[], width: number, height: number): Promise<Blob> {
  const canvas = document.createElement("canvas");
  canvas.width = width;
  canvas.height = height;
  const context = canvas.getContext("2d");
  if (!context) return Promise.reject(new Error("Canvas is unavailable"));
  context.fillStyle = strokes.length > 0 ? "#000000" : "#ffffff";
  context.fillRect(0, 0, width, height);
  drawStrokes(context, strokes, "#ffffff");
  return new Promise((resolve, reject) => {
    canvas.toBlob(
      (blob) => (blob ? resolve(blob) : reject(new Error("Mask encoding failed"))),
      "image/png",
    );
  });
}
