import { createFileRoute, stripSearchParams } from "@tanstack/react-router";
import { ChatLibraryPage } from "@/features/chat/library/chat-library";
import { librarySearchDefaults, librarySearchSchema } from "@/features/library/library-search";
import { ReadableSources } from "@/features/sources/readable-sources";

/**
 * The library keeps its view, filters, order and page in the address; `?category=IMAGE` opens it narrowed. Neither
 * the library nor Chat imports the connector capability's screens (ADR 0015), so the route hands in the Sources view.
 */
export const Route = createFileRoute("/_authenticated/library")({
  validateSearch: librarySearchSchema,
  search: { middlewares: [stripSearchParams(librarySearchDefaults)] },
  component: function LibraryRoute() {
    return <ChatLibraryPage sources={ReadableSources} />;
  },
});
