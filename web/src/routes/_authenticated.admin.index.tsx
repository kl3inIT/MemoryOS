import { createFileRoute } from "@tanstack/react-router";
import { SourcesPage } from "@/features/sources/sources-page";
import { validateSourceListSearch } from "@/features/sources/source-list-search";

export const Route = createFileRoute("/_authenticated/admin/")({
  validateSearch: validateSourceListSearch,
  component: SourcesPage,
});
