import { createFileRoute, stripSearchParams } from "@tanstack/react-router";
import { ChatLibraryPage } from "@/features/chat/library/chat-library";
import { librarySearchDefaults, librarySearchSchema } from "@/features/library/library-search";

/** The library keeps its view, filters, order and page in the address; `?category=IMAGE` opens it narrowed. */
export const Route = createFileRoute("/_authenticated/library")({
  validateSearch: librarySearchSchema,
  search: { middlewares: [stripSearchParams(librarySearchDefaults)] },
  component: ChatLibraryPage,
});
