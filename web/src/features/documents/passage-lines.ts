import { useQuery } from "@tanstack/react-query";
import type { ScanLine } from "@/features/preview/scan-line-marks";
import { readSearchPassageLinesOptions } from "@/lib/hey-api/@tanstack/react-query.gen";
import type { SearchPassageLines } from "@/lib/hey-api/types.gen";

const NONE: readonly ScanLine[] = [];
const MAX_LINES = 600;

/**
 * The text lines the OCR located in the blocks a Search citation's matching passage cites. Asked for only where
 * they can narrow a mark — the Search reader, a PDF or a picture with cited regions, and a question — and read
 * once per passage of a generation, which never changes. Anything the reader cannot place is left out.
 */
export function usePassageLines({
  documentId,
  generation,
  ordinal,
  enabled,
}: {
  documentId: string;
  generation: string;
  ordinal: number | undefined;
  enabled: boolean;
}): readonly ScanLine[] {
  const asked = enabled && ordinal !== undefined;
  const query = useQuery({
    ...readSearchPassageLinesOptions({
      path: { documentId, ordinal: ordinal ?? 0 },
      query: { generation },
    }),
    enabled: asked,
    retry: false,
    staleTime: Infinity,
    select: scanLines,
  });
  return asked && query.data ? query.data : NONE;
}

/** The answer as lines the viewers draw: a page, text, a known origin, a frame with a size, and finite corners. */
export function scanLines(data: SearchPassageLines): ScanLine[] {
  const lines: ScanLine[] = [];
  for (const line of data.lines.slice(0, MAX_LINES)) {
    const points = line.points.filter(
      (point): point is [number, number] =>
        point.length === 2 && point.every((value) => Number.isFinite(value)),
    );
    if (!Number.isInteger(line.pageNo) || line.pageNo < 1 || !line.text.trim()) continue;
    if (points.length !== line.points.length || points.length < 3) continue;
    const framed = line.frameWidth !== undefined || line.frameHeight !== undefined;
    if (
      framed &&
      (line.coordOrigin !== "TOPLEFT" ||
        !(Number(line.frameWidth) > 0) ||
        !(Number(line.frameHeight) > 0))
    )
      continue;
    lines.push({
      page: line.pageNo,
      text: line.text,
      origin: line.coordOrigin,
      ...(framed ? { frame: { width: line.frameWidth!, height: line.frameHeight! } } : {}),
      points,
    });
  }
  return lines;
}
