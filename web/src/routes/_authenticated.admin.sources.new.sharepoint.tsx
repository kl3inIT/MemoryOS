import { createFileRoute } from "@tanstack/react-router";
import { CreateSharePointSourcePage } from "@/features/sources/sharepoint/create-sharepoint-source-page";
import { sharePointSetupSearch } from "@/features/sources/sharepoint/sharepoint-setup-search";

export const Route = createFileRoute("/_authenticated/admin/sources/new/sharepoint")({
  validateSearch: sharePointSetupSearch,
  component: CreateSharePointSourcePage,
});
