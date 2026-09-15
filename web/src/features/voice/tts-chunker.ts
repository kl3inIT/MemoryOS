/** Sentence ends followed by more text, and line breaks between blocks. */
const BOUNDARY = /[.!?…](?=\s)|\n/g;
const TRAILING_END = /[.!?…]\s*$/;
/** Below the server's 4096-character limit for one part. */
const MAX_PART = 4_000;
const FAST_START_MS = 200;
const FLUSH_MS = 250;

export type ChunkerTimers = {
  set: (callback: () => void, delay: number) => number;
  clear: (handle: number) => void;
};

const windowTimers: ChunkerTimers = {
  set: (callback, delay) => window.setTimeout(callback, delay),
  clear: (handle) => window.clearTimeout(handle),
};

/**
 * Onyx streaming TTS chunking over the speech text of a growing answer. Parts end at a sentence (the first one at 30 or
 * more characters when possible), else at a clause after 150 characters or a word after 200. The first part may start
 * after 200 ms, and a finished sentence at the end is sent after a 250 ms pause.
 */
export class SpeechChunker {
  private readonly emit: (part: string) => void;
  private readonly timers: ChunkerTimers;
  private text = "";
  private sent = 0;
  private emitted = 0;
  private fastStart: number | undefined;
  private flush: number | undefined;
  private done = false;

  constructor(emit: (part: string) => void, timers: ChunkerTimers = windowTimers) {
    this.emit = emit;
    this.timers = timers;
  }

  /** Parts emitted so far. */
  get count() {
    return this.emitted;
  }

  /** The whole speech text so far; only what follows the text already sent is considered. */
  update(text: string) {
    if (this.done) return;
    this.replace(text);
    for (let cut = boundary(this.pending()); cut !== undefined; cut = boundary(this.pending()))
      this.take(cut);
    this.schedule();
  }

  /** The final speech text: everything not yet sent is sent now. */
  complete(text: string) {
    if (this.done) return;
    this.replace(text);
    this.cancel();
    this.take(this.pending().length);
  }

  cancel() {
    this.done = true;
    if (this.fastStart !== undefined) this.timers.clear(this.fastStart);
    if (this.flush !== undefined) this.timers.clear(this.flush);
    this.fastStart = undefined;
    this.flush = undefined;
  }

  /** Rendering can shorten the text as markdown completes; never read past its new end. */
  private replace(text: string) {
    this.text = text;
    this.sent = Math.min(this.sent, text.length);
  }

  private pending() {
    return this.text.slice(this.sent);
  }

  private take(length: number) {
    const part = this.text.slice(this.sent, this.sent + length);
    this.sent += length;
    for (const piece of pieces(part)) {
      this.emitted += 1;
      this.emit(piece);
    }
  }

  private schedule() {
    if (this.emitted === 0 && this.fastStart === undefined && this.pending().trim().length >= 20)
      this.fastStart = this.timers.set(() => {
        this.fastStart = undefined;
        if (this.done || this.emitted > 0) return;
        const space = this.pending().slice(0, 50).lastIndexOf(" ");
        if (space >= 15) this.take(space);
      }, FAST_START_MS);
    if (this.flush !== undefined) this.timers.clear(this.flush);
    this.flush = undefined;
    if (TRAILING_END.test(this.pending()))
      this.flush = this.timers.set(() => {
        this.flush = undefined;
        if (!this.done && TRAILING_END.test(this.pending())) this.take(this.pending().length);
      }, FLUSH_MS);
  }
}

function boundary(pending: string): number | undefined {
  let last: number | undefined;
  for (const match of pending.matchAll(BOUNDARY)) {
    if (match.index < 10) continue;
    if (match.index >= 30) return match.index + 1;
    last = match.index + 1;
  }
  if (last !== undefined) return last;
  if (pending.length >= 150) {
    const clause = Math.max(
      pending.lastIndexOf(",", 149),
      pending.lastIndexOf(";", 149),
      pending.lastIndexOf(":", 149),
    );
    if (clause >= 70) return clause + 1;
  }
  if (pending.length >= 200) {
    const space = pending.lastIndexOf(" ", 119);
    if (space > 80) return space;
  }
  return undefined;
}

function pieces(part: string): string[] {
  const result: string[] = [];
  let rest = part.trim();
  while (rest.length > MAX_PART) {
    const space = rest.lastIndexOf(" ", MAX_PART);
    const cut = space > 0 ? space : MAX_PART;
    result.push(rest.slice(0, cut).trim());
    rest = rest.slice(cut).trim();
  }
  if (rest) result.push(rest);
  return result;
}
