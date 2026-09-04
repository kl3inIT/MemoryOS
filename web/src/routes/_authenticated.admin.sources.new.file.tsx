import { createFileRoute } from "@tanstack/react-router";
import { CreateFileSourcePage } from "@/features/sources/create-file-source-page";

export const Route = createFileRoute("/_authenticated/admin/sources/new/file")({
  component: CreateFileSourcePage,
});
