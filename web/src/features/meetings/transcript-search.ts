/** Finding text in a transcript, kept apart from the component that paints what it finds. */
/** Where each hit for `query` starts within `text`, in reading order. */
export function matches(text: string, query: string): number[] {
  const needle = query.trim().toLowerCase();
  if (!needle) return [];
  const haystack = text.toLowerCase();
  const found: number[] = [];
  for (
    let at = haystack.indexOf(needle);
    at >= 0;
    at = haystack.indexOf(needle, at + needle.length)
  )
    found.push(at);
  return found;
}
