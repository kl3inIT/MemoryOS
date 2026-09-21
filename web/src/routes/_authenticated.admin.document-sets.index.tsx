import { createFileRoute } from "@tanstack/react-router";
import { DocumentSetsPage } from "@/features/document-sets/document-sets-page";

export const Route = createFileRoute("/_authenticated/admin/document-sets/")({
  component: DocumentSetsPage,
});
