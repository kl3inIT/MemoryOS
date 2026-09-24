import { createFileRoute } from "@tanstack/react-router";
import { ChatStoragePage } from "@/features/library/storage-page";

export const Route = createFileRoute("/_authenticated/settings/storage")({
  component: ChatStoragePage,
});
