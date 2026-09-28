/**
 * The views that read what reaches the person beyond their own files, each behind the capability that owns what
 * it lists. Their rows are references the server re-authorizes on every read.
 */
export type LibraryEntryView = "recent" | "shared" | "documents" | "starred";
/** The views of what the person owns: usable files, what is arriving, and the trash. */
export type LibraryOwnedView = "ready" | "pending" | "trash";
/** Which slice of the library is on screen; `sources` lists the Sources behind the organisation's documents. */
export type LibraryView = LibraryEntryView | LibraryOwnedView | "sources";

const ENTRY_VIEWS: Record<LibraryEntryView, true> = {
  recent: true,
  shared: true,
  documents: true,
  starred: true,
};

const OWNED_VIEWS: Record<LibraryOwnedView, true> = {
  ready: true,
  pending: true,
  trash: true,
};

export function isEntryView(view: LibraryView): view is LibraryEntryView {
  return Object.hasOwn(ENTRY_VIEWS, view);
}

/** Whether the view lists the person's own files, which the owned listing reads. */
export function isOwnedView(view: LibraryView): view is LibraryOwnedView {
  return Object.hasOwn(OWNED_VIEWS, view);
}
