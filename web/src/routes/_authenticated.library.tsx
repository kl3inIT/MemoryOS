import { createFileRoute } from "@tanstack/react-router";
import { ChatLibraryPage } from "@/features/library/library-page";
import { LIBRARY_CATEGORIES, type LibraryCategory } from "@/features/library/library";

/** `?category=IMAGE` opens the library already narrowed to that kind, as the storage page links to it. */
export const Route = createFileRoute("/_authenticated/library")({
  component: ChatLibraryPage,
  validateSearch: (search: Record<string, unknown>): { category?: LibraryCategory } => {
    const category = search.category;
    return typeof category === "string" &&
      (LIBRARY_CATEGORIES as readonly string[]).includes(category)
      ? { category: category as LibraryCategory }
      : {};
  },
});
