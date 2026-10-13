/**
 * Which byte ranges to fetch for the reads a PDF reader asked for at the same moment.
 *
 * pdf.js states its needs in blocks and asks for each one separately. Fetched as asked, a small block turns a
 * text document into hundreds of requests and a large one makes every miss cost a megabyte, so the reads are
 * planned together: neighbours become one request, ordinary reading is extended because the bytes beside it
 * are usually wanted next, and a burst of scattered reads is fetched exactly, because there they are not.
 */

/** The block pdf.js is told to think in. Small, so that finding a page costs little; see `planReads`. */
export const BLOCK = 16 * 1024;

/** Blocks between two reads that are cheaper to fetch than to leave as a second request (64 KiB). */
const MERGE_GAP = 4;

/** The size an ordinary read is extended to (256 KiB): what lies beside a wanted object is usually wanted next. */
const READ_AHEAD = 16;

/**
 * From this many separate runs the reads are a burst: a scan's page dictionaries, one per page across the
 * whole file. Extending each of those would fetch the pages themselves.
 */
const BURST = 3;

/** A wanted block, and when it was asked for; a higher `order` is more recent. */
export type WantedBlock = { block: number; order: number };

/** Blocks `first` to `last` inclusive, fetched as one request; `order` is its most recent ask. */
export type BlockRun = { first: number; last: number; order: number };

/**
 * The requests to make for `wanted`, most recently asked last. `count` is the number of blocks in the file
 * and `taken` says whether a block is already held or on its way, so nothing is fetched twice.
 */
export function planReads(
  wanted: readonly WantedBlock[],
  count: number,
  taken: (block: number) => boolean,
): BlockRun[] {
  const blocks = new Map<number, number>();
  for (const { block, order } of wanted) {
    if (!Number.isInteger(block) || block < 0 || block >= count || taken(block)) continue;
    blocks.set(block, Math.max(order, blocks.get(block) ?? order));
  }
  const free = (from: number, to: number) => {
    for (let block = from; block < to; block += 1) if (taken(block)) return false;
    return true;
  };

  const runs: BlockRun[] = [];
  for (const [block, order] of [...blocks].sort(([first], [second]) => first - second)) {
    const run = runs.at(-1);
    if (run && block - run.last - 1 <= MERGE_GAP && free(run.last + 1, block)) {
      run.last = block;
      run.order = Math.max(run.order, order);
    } else {
      runs.push({ first: block, last: block, order });
    }
  }

  if (runs.length < BURST) {
    const short = (run: BlockRun) => run.last - run.first + 1 < READ_AHEAD;
    // Forward, the last run first, so no run grows into one that has yet to grow.
    for (let index = runs.length - 1; index >= 0; index -= 1) {
      const run = runs[index]!;
      const next = runs[index + 1];
      const limit = next?.first ?? count;
      while (short(run) && run.last + 1 < limit && !taken(run.last + 1)) run.last += 1;
      // Extended up to the next read: one request for both.
      if (next && run.last + 1 === next.first) {
        run.last = next.last;
        run.order = Math.max(run.order, next.order);
        runs.splice(index + 1, 1);
      }
    }
    // A read stopped short by bytes already held after it grows backwards instead. That is a reader walking
    // back from the end of the file: the cross-reference table, then the catalog, then the page tree.
    for (let index = 0; index < runs.length; index += 1) {
      const run = runs[index]!;
      const floor = (runs[index - 1]?.last ?? -1) + 1;
      while (short(run) && run.first > floor && !taken(run.first - 1)) run.first -= 1;
    }
  }
  return runs.sort((first, second) => first.order - second.order);
}
