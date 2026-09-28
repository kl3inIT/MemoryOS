/** The status, provider and access a Source list is narrowed to; an empty value is no filter. */
export type SourceFilters = { status: string; provider: string; access: string };

export const noSourceFilters: SourceFilters = { status: "", provider: "", access: "" };
