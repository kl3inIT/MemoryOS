import { createFileRoute } from "@tanstack/react-router";
import { CreateGoogleDriveSourcePage } from "@/features/sources/create-google-drive-source-page";
import { googleDriveAuthorizationSearch } from "@/features/sources/google-drive-authorization";

export const Route = createFileRoute("/_authenticated/admin/sources/new/google-drive")({
  validateSearch: googleDriveAuthorizationSearch,
  component: CreateGoogleDriveSourcePage,
});
