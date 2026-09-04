import { createFileRoute } from "@tanstack/react-router";
import { SourceCatalogPage } from "@/features/sources/source-catalog-page";

export const Route = createFileRoute("/_authenticated/admin/sources/new/")({
  component: SourceCatalogPage,
});
