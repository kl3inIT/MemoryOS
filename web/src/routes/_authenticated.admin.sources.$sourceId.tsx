import { createFileRoute } from "@tanstack/react-router";
import { SourceDetailPage } from "@/features/sources/source-detail-page";

export const Route = createFileRoute("/_authenticated/admin/sources/$sourceId")({
  validateSearch: () => ({}),
  component: SourceDetailPage,
});
