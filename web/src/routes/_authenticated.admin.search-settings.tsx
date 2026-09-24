import { createFileRoute } from "@tanstack/react-router";
import { SearchSettingsPage } from "@/features/search-settings/search-settings-page";

export const Route = createFileRoute("/_authenticated/admin/search-settings")({
  component: SearchSettingsPage,
});
