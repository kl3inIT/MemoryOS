import { createFileRoute } from "@tanstack/react-router";
import { ChatStoragePage } from "@/features/chat/chat-storage-page";

export const Route = createFileRoute("/_authenticated/settings/storage")({
  component: ChatStoragePage,
});
