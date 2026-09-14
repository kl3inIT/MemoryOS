import type { Route } from "@playwright/test";

const PADDING_BYTES = 6 * 1024 * 1024;

/**
 * A multi-page PDF larger than pdf.js's range threshold (two 1 MiB chunks). Every page draws the two lines of the
 * former `cited-handbook.pdf` at the same position, so fixture boxes fit the glyphs on any cited page; an unreferenced padding stream separates the
 * first and last pages, so reading page 1 must not need the whole file.
 */
export function rangedHandbookPdf(pageCount = 12) {
  const firstHalf = Math.ceil(pageCount / 2);
  const pageObject = (page: number) => 4 + (page - 1) * 2;
  const text = (page: number) =>
    `BT /F1 18 Tf 72 680 Td (Employee handbook: leave policy) Tj 0 -60 Td (Requests need manager approval.) Tj 0 -60 Td (Handbook page ${page}) Tj ET`;
  const pageObjects = (page: number) => [
    `<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents ${pageObject(page) + 1} 0 R /Resources << /Font << /F1 3 0 R >> >> >>`,
    `<< /Length ${text(page).length} >>\nstream\n${text(page)}\nendstream`,
  ];
  const kids = Array.from({ length: pageCount }, (_, index) => `${pageObject(index + 1)} 0 R`);
  const paddingObject = pageObject(pageCount) + 2;
  const numbered: Array<[number, string]> = [
    [1, "<< /Type /Catalog /Pages 2 0 R >>"],
    [2, `<< /Type /Pages /Kids [${kids.join(" ")}] /Count ${pageCount} >>`],
    [3, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"],
  ];
  const addPage = (page: number) =>
    pageObjects(page).forEach((body, index) => numbered.push([pageObject(page) + index, body]));
  for (let page = 1; page <= firstHalf; page += 1) addPage(page);
  numbered.push([
    paddingObject,
    `<< /Length ${PADDING_BYTES} >>\nstream\n${" ".repeat(PADDING_BYTES)}\nendstream`,
  ]);
  for (let page = firstHalf + 1; page <= pageCount; page += 1) addPage(page);

  let output = "%PDF-1.4\n";
  const offsets = new Map<number, number>();
  for (const [number, body] of numbered) {
    offsets.set(number, output.length);
    output += `${number} 0 obj\n${body}\nendobj\n`;
  }
  const size = paddingObject + 1;
  const xref = output.length;
  output += `xref\n0 ${size}\n0000000000 65535 f \n`;
  for (let number = 1; number < size; number += 1) {
    output += `${String(offsets.get(number)).padStart(10, "0")} 00000 n \n`;
  }
  output += `trailer\n<< /Size ${size} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`;
  return Buffer.from(output, "latin1");
}

/** Serves `body` like the original endpoints: whole with `Accept-Ranges`, or one requested range as 206. */
export function fulfillPdfRange(route: Route, body: Buffer) {
  const headers = {
    "Accept-Ranges": "bytes",
    "Cache-Control": "no-store",
    "Content-Type": "application/octet-stream",
  };
  const range = /^bytes=(\d+)-(\d*)$/.exec(route.request().headers()["range"] ?? "");
  if (!range) {
    return route.fulfill({
      status: 200,
      headers: { ...headers, "Content-Length": String(body.length) },
      body,
    });
  }
  const first = Number(range[1]);
  const last = Math.min(range[2] ? Number(range[2]) : body.length - 1, body.length - 1);
  return route.fulfill({
    status: 206,
    headers: {
      ...headers,
      "Content-Length": String(last - first + 1),
      "Content-Range": `bytes ${first}-${last}/${body.length}`,
    },
    body: body.subarray(first, last + 1),
  });
}

/** Bytes requested by `bytes=first-last` headers, clamped to a body of `size` bytes. */
export function rangedBytes(ranges: readonly (string | undefined)[], size: number) {
  return ranges.reduce((total, header) => {
    const range = /^bytes=(\d+)-(\d*)$/.exec(header ?? "");
    if (!range) return total;
    const last = Math.min(range[2] ? Number(range[2]) : size - 1, size - 1);
    return total + last - Number(range[1]) + 1;
  }, 0);
}
