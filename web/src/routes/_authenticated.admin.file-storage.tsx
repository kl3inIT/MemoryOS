import { createFileRoute } from "@tanstack/react-router";
import { ChatStorageQuotaPage } from "@/features/chat/chat-storage-quota-page";

export const Route = createFileRoute("/_authenticated/admin/file-storage")({
  component: ChatStorageQuotaPage,
});
