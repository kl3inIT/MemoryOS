import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { BLOCK } from "./pdf-range-plan";
import { HeldOriginal, openPdfOriginal, PdfReading, preparePdfOriginal } from "./pdf-range-source";

const KIB = 1024;
const MIB = 1024 * KIB;

/** A file of random bytes, so a slice read from the wrong offset does not compare equal. */
function file(size: number) {
  const bytes = new Uint8Array(size);
  for (let offset = 0; offset < size; offset += 65536)
    crypto.getRandomValues(bytes.subarray(offset, Math.min(size, offset + 65536)));
  return bytes;
}

/** Answers range requests for `bytes` as the original route does, and records the ranges asked for. */
function serve(bytes: Uint8Array) {
  const ranges: string[] = [];
  const state = { status: 206, bytes };
  vi.stubGlobal(
    "fetch",
    vi.fn(async (_url: string, init?: RequestInit) => {
      const header = new Headers(init?.headers).get("Range") ?? "";
      ranges.push(header);
      if (state.status !== 206) return new Response(null, { status: state.status });
      const [, first, last] = /^bytes=(\d+)-(\d+)$/.exec(header)!;
      const end = Math.min(Number(last), state.bytes.length - 1);
      return new Response(state.bytes.slice(Number(first), end + 1), {
        status: 206,
        headers: { "Content-Range": `bytes ${first}-${end}/${state.bytes.length}` },
      });
    }),
  );
  return { ranges, state };
}

/** Lets the planning window pass and every answer land. */
async function settle() {
  await vi.advanceTimersByTimeAsync(10);
}

let address = 0;
/** A new original for each test: what a session read is kept by address. */
const original = () => `/api/search/documents/${(address += 1)}/original?generation=g`;

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe("opening an original", () => {
  it("reads the head, then the tail where the cross-reference table is", async () => {
    const bytes = file(3 * MIB + 5000);
    const { ranges } = serve(bytes);
    const held = await openPdfOriginal(original());

    const tail = Math.floor((bytes.length - 64 * KIB) / BLOCK) * BLOCK;
    expect(ranges).toEqual([`bytes=0-${128 * KIB - 1}`, `bytes=${tail}-${bytes.length - 1}`]);
    expect(held.length).toBe(bytes.length);
    expect(held.heldUntil(0)).toBe(128 * KIB);
    expect(held.heldUntil(tail)).toBe(bytes.length);
    expect(held.read(tail, bytes.length)).toEqual(bytes.slice(tail));
  });

  it("reads a small original whole, and one under the head in a single request", async () => {
    const small = file(300 * KIB + 7);
    const first = serve(small);
    const held = await openPdfOriginal(original());
    expect(first.ranges).toEqual([
      `bytes=0-${128 * KIB - 1}`,
      `bytes=${128 * KIB}-${small.length - 1}`,
    ]);
    expect(held.heldUntil(0)).toBe(small.length);

    const tiny = file(9000);
    const second = serve(tiny);
    const whole = await openPdfOriginal(original());
    expect(second.ranges).toEqual([`bytes=0-${128 * KIB - 1}`]);
    expect(whole.read(0, tiny.length)).toEqual(tiny);
  });

  it("takes the whole file when the server ignores the range", async () => {
    const bytes = file(200 * KIB);
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => new Response(bytes.slice(), { status: 200 })),
    );
    const held = await openPdfOriginal(original());
    expect(held.read(0, bytes.length)).toEqual(bytes);
  });

  it("refuses an answer that is not the range it asked for", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(
        async () =>
          new Response(new Uint8Array(10), {
            status: 206,
            headers: { "Content-Range": "bytes 100-109/5000" },
          }),
      ),
    );
    await expect(openPdfOriginal(original())).rejects.toThrow();
  });

  it("hands an opening started ahead to the reader that claims it", async () => {
    const { ranges } = serve(file(2 * MIB));
    const url = original();
    preparePdfOriginal(url);
    preparePdfOriginal(url);
    const held = await openPdfOriginal(url);
    expect(ranges).toHaveLength(2);
    expect(held.length).toBe(2 * MIB);
  });

  it("authorizes a held original with one byte before it is used again", async () => {
    const { ranges } = serve(file(2 * MIB));
    const url = original();
    const first = await openPdfOriginal(url);
    ranges.length = 0;
    const again = await openPdfOriginal(url);
    expect(ranges).toEqual(["bytes=0-0"]);
    expect(again).toBe(first);
  });

  it("forgets what it held when the original is refused", async () => {
    const { ranges, state } = serve(file(2 * MIB));
    const url = original();
    await openPdfOriginal(url);
    state.status = 404;
    await expect(openPdfOriginal(url)).rejects.toThrow();
    state.status = 206;
    ranges.length = 0;
    await openPdfOriginal(url);
    expect(ranges[0]).toBe(`bytes=0-${128 * KIB - 1}`);
  });

  it("reads afresh an original whose size changed under the same address", async () => {
    const { ranges, state } = serve(file(2 * MIB));
    const url = original();
    const first = await openPdfOriginal(url);
    state.bytes = file(2 * MIB + BLOCK);
    ranges.length = 0;
    const second = await openPdfOriginal(url);
    expect(ranges[0]).toBe("bytes=0-0");
    expect(ranges[1]).toBe(`bytes=0-${128 * KIB - 1}`);
    expect(second).not.toBe(first);
    expect(second.length).toBe(2 * MIB + BLOCK);
  });
});

describe("reading an opened original", () => {
  async function opened(size: number) {
    const bytes = file(size);
    const served = serve(bytes);
    const url = original();
    const held = await openPdfOriginal(url);
    served.ranges.length = 0;
    const failed = vi.fn();
    return { bytes, ...served, reading: new PdfReading(url, held, failed), failed };
  }

  it("starts pdf.js with the head", async () => {
    const { bytes, reading } = await opened(4 * MIB);
    expect(reading.length).toBe(4 * MIB);
    expect(reading.head()).toEqual(bytes.slice(0, 128 * KIB));
  });

  it("answers what is held without a request", async () => {
    const { bytes, ranges, reading } = await opened(4 * MIB);
    const answer = vi.fn();
    reading.request(BLOCK, 2 * BLOCK, answer);
    await settle();
    expect(ranges).toEqual([]);
    // The held blocks that follow come with it, so pdf.js does not ask for them one by one.
    expect(answer).toHaveBeenCalledWith(bytes.slice(BLOCK, 128 * KIB));
  });

  it("extends an ordinary read and serves the next ask from it", async () => {
    const { bytes, ranges, reading } = await opened(4 * MIB);
    const begin = 1 * MIB;
    const answer = vi.fn();
    reading.request(begin, begin + BLOCK, answer);
    await settle();
    expect(ranges).toEqual([`bytes=${begin}-${begin + 16 * BLOCK - 1}`]);
    expect(answer).toHaveBeenCalledWith(bytes.slice(begin, begin + 16 * BLOCK));

    const next = vi.fn();
    reading.request(begin + 4 * BLOCK, begin + 5 * BLOCK, next);
    await settle();
    expect(ranges).toHaveLength(1);
    expect(next).toHaveBeenCalledWith(bytes.slice(begin + 4 * BLOCK, begin + 16 * BLOCK));
  });

  it("fetches a burst of scattered reads exactly, one request each", async () => {
    const { bytes, ranges, reading } = await opened(8 * MIB);
    const answers = [1, 2, 3, 4, 5].map((page) => {
      const begin = page * MIB;
      const answer = vi.fn();
      reading.request(begin, begin + BLOCK, answer);
      return { begin, answer };
    });
    await settle();
    // The most recent ask goes first.
    expect(ranges).toEqual(
      [5, 4, 3, 2, 1].map((page) => `bytes=${page * MIB}-${page * MIB + BLOCK - 1}`),
    );
    for (const { begin, answer } of answers)
      expect(answer).toHaveBeenCalledWith(bytes.slice(begin, begin + BLOCK));
  });

  it("answers an ask up to the end of the file", async () => {
    const { bytes, reading } = await opened(4 * MIB + 100);
    const last = Math.floor(bytes.length / BLOCK) * BLOCK;
    const answer = vi.fn();
    reading.request(last, last + BLOCK, answer);
    await settle();
    expect(answer).toHaveBeenCalledWith(bytes.slice(last));
  });

  it("reports a failed read once and stops", async () => {
    const { state, reading, failed } = await opened(8 * MIB);
    state.status = 500;
    const answer = vi.fn();
    reading.request(MIB, MIB + BLOCK, answer);
    reading.request(3 * MIB, 3 * MIB + BLOCK, answer);
    reading.request(5 * MIB, 5 * MIB + BLOCK, answer);
    await settle();
    expect(failed).toHaveBeenCalledTimes(1);
    expect(answer).not.toHaveBeenCalled();
  });

  it("asks for nothing once it is closed", async () => {
    const { ranges, reading, failed } = await opened(8 * MIB);
    const answer = vi.fn();
    reading.request(MIB, MIB + BLOCK, answer);
    reading.close();
    await settle();
    reading.request(2 * MIB, 2 * MIB + BLOCK, answer);
    await settle();
    expect(ranges).toEqual([]);
    expect(answer).not.toHaveBeenCalled();
    expect(failed).not.toHaveBeenCalled();
  });
});

describe("HeldOriginal", () => {
  it("lets go of the blocks read first when it has to shed", () => {
    const held = new HeldOriginal(10 * BLOCK);
    held.put(0, new Uint8Array(4 * BLOCK));
    held.put(6 * BLOCK, new Uint8Array(4 * BLOCK));
    expect(held.bytes).toBe(8 * BLOCK);
    held.shed(5 * BLOCK);
    expect(held.bytes).toBe(5 * BLOCK);
    expect(held.has(0)).toBe(false);
    expect(held.has(9)).toBe(true);
  });

  it("keeps only whole blocks, and the short one a file ends with", () => {
    const held = new HeldOriginal(2 * BLOCK + 10);
    held.put(0, new Uint8Array(BLOCK + 5));
    expect(held.heldUntil(0)).toBe(BLOCK);
    held.put(2 * BLOCK, new Uint8Array(10));
    expect(held.has(2)).toBe(true);
    expect(held.bytes).toBe(BLOCK + 10);
  });
});
