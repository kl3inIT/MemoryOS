import { createFileRoute } from "@tanstack/react-router";
import { SearchPage } from "@/features/search/search-page";
import { searchPageSearchSchema } from "@/features/search/search-params";

export const Route = createFileRoute("/_authenticated/search")({
  validateSearch: searchPageSearchSchema,
  component: SearchPage,
});
