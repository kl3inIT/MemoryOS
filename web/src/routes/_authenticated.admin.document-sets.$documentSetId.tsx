import { createFileRoute } from "@tanstack/react-router";
import { DocumentSetFormPage } from "@/features/document-sets/document-set-form-page";

export const Route = createFileRoute("/_authenticated/admin/document-sets/$documentSetId")({
  component: function EditDocumentSetRoute() {
    const { documentSetId } = Route.useParams();
    return <DocumentSetFormPage key={documentSetId} documentSetId={documentSetId} />;
  },
});
