import { BLOCK, planReads, type BlockRun, type WantedBlock } from "./pdf-range-plan";

/**
 * Reads a stored PDF original by HTTP range for pdf.js, in place of the library's own URL loader.
 *
 * pdf.js asks for the dictionary of every page at once when a document lists its pages flat under the root,
 * which is how a scanner writes one. With the megabyte chunks the reader used, that one question read the
 * whole file before the first page was drawn. Here pdf.js thinks in small blocks and the reads it asks for
 * together are planned together (`planReads`), so a need costs what it needs.
 *
 * What a session read is kept in memory for the next opening of the same original, never in the HTTP cache,
 * and authority is not kept at all: every opening makes one authorized request before a held byte is used.
 */

/** Read with the first request: the header pdf.js parses first, and most small files whole. */
const HEAD = 8 * BLOCK;

/** Read with the second request: the cross-reference table and trailer a PDF ends with. */
const TAIL = 4 * BLOCK;

/** A file up to this size is read whole in those two requests. */
const WHOLE = 1024 * 1024;

/** Bytes kept across every original a session opened. */
const HELD_BYTES = 32 * 1024 * 1024;

/** An original nobody opened or read for this long is forgotten. */
const IDLE_MS = 10 * 60_000;

/** How long an opening started ahead of its reader waits to be claimed. */
const CLAIM_MS = 30_000;

/** Reads asked for within this window are planned as one; pdf.js sends a burst as separate messages. */
const GATHER_MS = 4;

/**
 * Range requests in flight for one reader. pdf.js cannot load a flat document before it has every page
 * dictionary, so a scan's opening burst is as slow as its last wave; HTTP/2 carries a wave side by side.
 * Every range is authorized against the database, and twelve stays under the API's connection pool.
 */
const MAX_READS = 12;

/** Blocks added to an answer when they are already held, so pdf.js does not have to ask for them. */
const BONUS = 16;

/** The bytes of one original a session has read so far, by block. */
export class HeldOriginal {
  private readonly blocks = new Map<number, Uint8Array>();
  private size = 0;
  readonly length: number;

  constructor(length: number) {
    this.length = length;
  }

  get count() {
    return Math.ceil(this.length / BLOCK);
  }

  get bytes() {
    return this.size;
  }

  has(block: number) {
    return this.blocks.has(block);
  }

  /** Keeps the blocks `data` covers whole; `begin` is the block boundary it starts at. */
  put(begin: number, data: Uint8Array) {
    for (let offset = 0; offset < data.length; offset += BLOCK) {
      const block = (begin + offset) / BLOCK;
      const size = Math.min(BLOCK, this.length - begin - offset);
      if (size <= 0 || offset + size > data.length) return;
      if (this.blocks.has(block)) continue;
      this.blocks.set(block, data.subarray(offset, offset + size));
      this.size += size;
    }
  }

  /** A copy of bytes `begin` to `end`, every block of which is held. */
  read(begin: number, end: number) {
    const bytes = new Uint8Array(end - begin);
    for (let block = Math.floor(begin / BLOCK); block * BLOCK < end; block += 1) {
      const held = this.blocks.get(block);
      if (!held) throw new Error("Block is not held");
      const start = block * BLOCK;
      const from = Math.max(begin, start);
      bytes.set(
        held.subarray(from - start, Math.min(end, start + held.length) - start),
        from - begin,
      );
    }
    return bytes;
  }

  /** Where the bytes held without a gap from `from` end. */
  heldUntil(from: number) {
    let block = Math.floor(from / BLOCK);
    while (this.blocks.has(block)) block += 1;
    return Math.min(block * BLOCK, this.length);
  }

  /** Lets go of the blocks read first until at most `limit` bytes remain. */
  shed(limit: number) {
    for (const [block, held] of this.blocks) {
      if (this.size <= limit) return;
      this.blocks.delete(block);
      this.size -= held.length;
    }
  }
}

/** Originals this session read, least recently used first. The URL names the document and its generation. */
const kept = new Map<string, { held: HeldOriginal; usedAt: number }>();
let sweep: ReturnType<typeof setTimeout> | undefined;

/** Marks `held` as just used and brings the total back under the budget, other originals first. */
function settle(url: string, held: HeldOriginal) {
  kept.delete(url);
  kept.set(url, { held, usedAt: Date.now() });
  let total = 0;
  for (const entry of kept.values()) total += entry.held.bytes;
  for (const [other, entry] of kept) {
    if (total <= HELD_BYTES) break;
    if (entry.held === held) continue;
    kept.delete(other);
    total -= entry.held.bytes;
  }
  if (total > HELD_BYTES) held.shed(HELD_BYTES);
  sweep ??= setTimeout(forgetIdle, IDLE_MS);
}

function forgetIdle() {
  sweep = undefined;
  const now = Date.now();
  for (const [url, entry] of kept) if (now - entry.usedAt >= IDLE_MS) kept.delete(url);
  if (kept.size) sweep = setTimeout(forgetIdle, IDLE_MS);
}

type Part = { begin: number; total: number; data: Uint8Array };

/** Sends one range request and resolves at its headers, so a second request can start beside its body. */
async function startRange(url: string, begin: number, end: number, signal?: AbortSignal) {
  const response = await fetch(url, {
    headers: { Range: `bytes=${begin}-${end - 1}` },
    // The server says so too; an original is kept in this module's memory or nowhere.
    cache: "no-store",
    signal,
  });
  const body = () => response.arrayBuffer().then((buffer) => new Uint8Array(buffer));
  // RFC 9110 lets a server ignore the header and send the whole file.
  if (response.status === 200) {
    const whole = body();
    return {
      total: undefined,
      part: whole.then((data) => ({ begin: 0, total: data.length, data })),
    };
  }
  const range = /^bytes (\d+)-(\d+)\/(\d+)$/.exec(response.headers.get("Content-Range") ?? "");
  if (response.status !== 206 || !range || Number(range[1]) !== begin) {
    void response.body?.cancel();
    throw new Error("The original could not be read by range");
  }
  const last = Number(range[2]);
  const total = Number(range[3]);
  const part = body().then((data): Part => {
    if (data.length !== last - begin + 1 || last !== Math.min(end, total) - 1)
      throw new Error("The original's range is not the one asked for");
    return { begin, total, data };
  });
  return { total, part };
}

async function readRange(url: string, begin: number, end: number, signal?: AbortSignal) {
  return (await startRange(url, begin, end, signal)).part;
}

/**
 * One authorized request, then what the reader needs to start: the head, and the tail a PDF's
 * cross-reference table is in, read side by side. A held original costs a single byte to authorize.
 */
async function authorize(url: string): Promise<HeldOriginal> {
  const known = kept.get(url)?.held;
  if (known?.has(0)) {
    try {
      const { total } = await readRange(url, 0, 1);
      if (total === known.length) {
        settle(url, known);
        return known;
      }
    } catch (refused) {
      kept.delete(url);
      throw refused;
    }
    // Another file under the same address: nothing held describes it.
    kept.delete(url);
  }

  const head = await startRange(url, 0, HEAD);
  const total = head.total;
  // The rest of a small file, or the tail of a large one, starts while the head is still arriving.
  const rest =
    total === undefined || total <= HEAD
      ? undefined
      : readRange(url, total <= WHOLE ? HEAD : Math.floor((total - TAIL) / BLOCK) * BLOCK, total);
  const [first, second] = await Promise.all([head.part, rest]);
  const held = new HeldOriginal(first.total);
  for (const part of [first, second]) {
    if (!part) continue;
    if (part.total !== held.length) throw new Error("The original changed while it was read");
    held.put(part.begin, part.data);
  }
  settle(url, held);
  return held;
}

const prepared = new Map<
  string,
  { opening: Promise<HeldOriginal>; expiry: ReturnType<typeof setTimeout> }
>();

/**
 * Starts opening `url` before its reader exists, so the bytes arrive while the reader's code does. The reader
 * claims the opening with `openPdfOriginal`; one nobody claims is dropped.
 */
export function preparePdfOriginal(url: string) {
  if (prepared.has(url)) return;
  const opening = authorize(url);
  // A reader that never mounts must not leave a rejection unhandled.
  opening.catch(() => {});
  prepared.set(url, { opening, expiry: setTimeout(() => prepared.delete(url), CLAIM_MS) });
}

/** The opening started for `url`, or a new one. Each call is one authorized opening. */
export function openPdfOriginal(url: string): Promise<HeldOriginal> {
  const ahead = prepared.get(url);
  if (!ahead) return authorize(url);
  prepared.delete(url);
  clearTimeout(ahead.expiry);
  return ahead.opening;
}

type Ask = { begin: number; end: number; order: number; answer: (bytes: Uint8Array) => void };

/** One reader's reads of an opened original, until the reader closes it. */
export class PdfReading {
  private readonly asks: Ask[] = [];
  private readonly coming = new Set<number>();
  private readonly queue: BlockRun[] = [];
  private readonly cancel = new AbortController();
  private flying = 0;
  private asked = 0;
  private gather: ReturnType<typeof setTimeout> | undefined;
  private closed = false;
  private readonly url: string;
  private readonly held: HeldOriginal;
  private readonly onFailure: () => void;

  /** `onFailure` is called once when a read fails; the reader then falls back to reading the file whole. */
  constructor(url: string, held: HeldOriginal, onFailure: () => void) {
    this.url = url;
    this.held = held;
    this.onFailure = onFailure;
  }

  get length() {
    return this.held.length;
  }

  /** The bytes held from the start of the file, which pdf.js parses before it asks for any. */
  head() {
    return this.held.read(0, this.held.heldUntil(0));
  }

  /** Answers with bytes `begin` to at least `end`, once they are held. */
  request(begin: number, end: number, answer: (bytes: Uint8Array) => void) {
    if (this.closed) return;
    this.asks.push({ begin, end: Math.min(end, this.held.length), order: this.asked, answer });
    this.asked += 1;
    this.serve();
    if (this.asks.length)
      this.gather ??= setTimeout(() => {
        this.gather = undefined;
        this.plan();
      }, GATHER_MS);
  }

  close() {
    if (this.closed) return;
    this.closed = true;
    clearTimeout(this.gather);
    this.cancel.abort();
    this.asks.length = 0;
    this.queue.length = 0;
  }

  /** Answers every ask whose bytes are all held. */
  private serve() {
    for (let index = this.asks.length - 1; index >= 0; index -= 1) {
      const { begin, end, answer } = this.asks[index]!;
      const until = this.held.heldUntil(begin);
      if (until < end) continue;
      this.asks.splice(index, 1);
      const bytes = this.held.read(begin, Math.min(until, end + BONUS * BLOCK));
      // pdf.js registers the reader that takes the answer as it asks; answer after it has.
      queueMicrotask(() => {
        if (!this.closed) answer(bytes);
      });
    }
  }

  /** Queues the reads the open asks still need; blocks held or on their way are left out. */
  private plan() {
    const wanted: WantedBlock[] = [];
    for (const { begin, end, order } of this.asks)
      for (let block = Math.floor(begin / BLOCK); block * BLOCK < end; block += 1)
        wanted.push({ block, order });
    const taken = (block: number) => this.held.has(block) || this.coming.has(block);
    for (const run of planReads(wanted, this.held.count, taken)) {
      for (let block = run.first; block <= run.last; block += 1) this.coming.add(block);
      this.queue.push(run);
    }
    this.pump();
  }

  /** Starts queued reads, the most recently asked first: what a reader just turned to goes ahead. */
  private pump() {
    while (!this.closed && this.flying < MAX_READS && this.queue.length) {
      this.flying += 1;
      void this.read(this.queue.pop()!);
    }
  }

  private async read(run: BlockRun) {
    try {
      const end = Math.min((run.last + 1) * BLOCK, this.held.length);
      const part = await readRange(this.url, run.first * BLOCK, end, this.cancel.signal);
      if (part.total !== this.held.length)
        throw new Error("The original changed while it was read");
      this.held.put(part.begin, part.data);
    } catch {
      if (!this.closed) {
        this.close();
        this.onFailure();
      }
      return;
    }
    for (let block = run.first; block <= run.last; block += 1) this.coming.delete(block);
    this.flying -= 1;
    this.serve();
    settle(this.url, this.held);
    // Settling may have shed a block an open ask counted on as held; plan again so it is not left waiting.
    if (this.asks.length) this.plan();
    else this.pump();
  }
}
