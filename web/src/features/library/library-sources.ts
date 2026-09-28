import type { ComponentType, ReactNode } from "react";

/** The status, provider and access the Sources view is narrowed to; an empty value is no filter. */
type LibrarySourceFilters = { status: string; provider: string; access: string };

/**
 * What the library shows of Sources: the Sources behind the organisation's documents that the person may read
 * from. How a Source list reads belongs to the connector capability's screens, which the library does not import
 * (ADR 0015), so whoever composes the page hands the view in; without it the organisation's section holds only its
 * documents. The library keeps the view's search and filters in its address and hands it the way to one Source's
 * documents.
 */
export type LibrarySources = ComponentType<{
  search: string;
  onSearch: (next: string) => void;
  filters: LibrarySourceFilters;
  onFilters: (next: LibrarySourceFilters) => void;
  /** Opens the library's documents of one Source. */
  DocumentsLink: ComponentType<{ sourceId: string; className?: string; children: ReactNode }>;
}>;
