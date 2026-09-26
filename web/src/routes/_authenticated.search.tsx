import { createFileRoute } from "@tanstack/react-router";
import { loadDocumentSets } from "@/features/document-sets/document-sets-api";
import { SearchPage } from "@/features/search/search-page";
import { searchPageSearchSchema } from "@/features/search/search-params";

export const Route = createFileRoute("/_authenticated/search")({
  validateSearch: searchPageSearchSchema,
  component: function SearchRoute() {
    return <SearchPage loadDocumentSets={loadDocumentSets} />;
  },
});
