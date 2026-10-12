/** Finding text in a transcript, kept apart from the component that paints what it finds. */

/** One hit: where it starts in the text and where it ends. */
export type Match = { start: number; end: number };

const COMBINING_MARKS = /\p{Mn}/gu;

/** A letter without its tone and vowel marks, in lower case: "Ạ" and "ạ" both read "a", "Đ" reads "d". */
function bare(letter: string) {
  return letter.normalize("NFD").replace(COMBINING_MARKS, "").replace(/đ/gi, "d").toLowerCase();
}

/**
 * Where each hit for `query` is within `text`, in reading order. Vietnamese is often typed without its marks, so
 * a query written without any finds the words that carry them ("chay" finds "Chạy"); a query written with marks
 * asks for exactly those.
 */
export function matches(text: string, query: string): Match[] {
  const asked = query.trim().normalize("NFC").toLowerCase();
  if (!asked) return [];
  const marked = [...asked].some((letter) => bare(letter) !== letter);
  // Folded letter by letter, with the place each one came from, so a hit is painted on the text as written.
  let haystack = "";
  const origin: number[] = [];
  for (let at = 0; at < text.length; at += 1) {
    const letter = text.charAt(at);
    const folded = marked ? letter.toLowerCase() : bare(letter);
    for (let part = 0; part < folded.length; part += 1) origin.push(at);
    haystack += folded;
  }
  const found: Match[] = [];
  for (let at = haystack.indexOf(asked); at >= 0; at = haystack.indexOf(asked, at + asked.length)) {
    const start = origin[at];
    const last = origin[at + asked.length - 1];
    if (start !== undefined && last !== undefined) found.push({ start, end: last + 1 });
  }
  return found;
}
