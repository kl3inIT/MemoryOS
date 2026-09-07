import { createFileRoute } from "@tanstack/react-router";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { CreateGoogleDriveSourcePage } from "@/features/sources/create-google-drive-source-page";
import { googleDriveAuthorizationSearch } from "@/features/sources/google-drive-authorization";

export const Route = createFileRoute("/_authenticated/admin/sources/new/google-drive")({
  validateSearch: googleDriveAuthorizationSearch,
  component: function GoogleDriveSetupRoute() {
    const session = useApplicationSession();
    return (
      <CreateGoogleDriveSourcePage
        key={`${session.actorId}:${session.tenant.role}:${session.capabilities.join(",")}`}
      />
    );
  },
});
