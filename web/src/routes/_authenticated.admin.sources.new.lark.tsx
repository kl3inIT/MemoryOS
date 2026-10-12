import { createFileRoute } from "@tanstack/react-router";
import { CreateLarkSourcePage } from "@/features/sources/lark/create-lark-source-page";
import { larkSetupSearch } from "@/features/sources/lark/lark-setup-search";

export const Route = createFileRoute("/_authenticated/admin/sources/new/lark")({
  validateSearch: larkSetupSearch,
  component: CreateLarkSourcePage,
});
