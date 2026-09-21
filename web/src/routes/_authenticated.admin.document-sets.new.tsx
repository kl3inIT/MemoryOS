import { createFileRoute } from "@tanstack/react-router";
import { DocumentSetFormPage } from "@/features/document-sets/document-set-form-page";
import { AccessDeniedScreen } from "@/features/identity/session-states";
import { useGlobalCapability } from "@/features/identity/application-session-context";

export const Route = createFileRoute("/_authenticated/admin/document-sets/new")({
  component: function CreateDocumentSetRoute() {
    return useGlobalCapability("AGENTS_CREATE") ? <DocumentSetFormPage /> : <AccessDeniedScreen />;
  },
});
