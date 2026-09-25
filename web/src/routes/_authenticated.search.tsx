import { createFileRoute } from "@tanstack/react-router";
import { loadDocumentSets } from "@/features/document-sets/document-sets-api";
import { SearchPage } from "@/features/search/search-page";

export const Route = createFileRoute("/_authenticated/search")({
  component: function SearchRoute() {
    return <SearchPage loadDocumentSets={loadDocumentSets} />;
  },
});
