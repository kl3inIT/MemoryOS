export type SourceListSearch = {
  sourceId?: string;
};

export function validateSourceListSearch(search: Record<string, unknown>): SourceListSearch {
  const sourceId = typeof search.sourceId === "string" ? search.sourceId.trim() : "";
  return { sourceId: sourceId || undefined };
}
