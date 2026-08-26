import { createFileRoute } from "@tanstack/react-router";
import { SourcesPage } from "@/features/knowledge/sources-page";

export const Route = createFileRoute("/_authenticated/admin/")({
  component: SourcesPage,
});
