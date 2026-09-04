export type SourceListSearch = {
  sourceId?: string;
};

export function validateSourceListSearch(search: Record<string, unknown>): SourceListSearch {
  return {
    sourceId:
      typeof search.sourceId === "string" && search.sourceId.length > 0
        ? search.sourceId
        : undefined,
  };
}
