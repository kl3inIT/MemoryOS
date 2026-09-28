import { createFileRoute } from "@tanstack/react-router";
import { ChatStoragePage } from "@/features/chat/library/chat-library";

export const Route = createFileRoute("/_authenticated/settings/storage")({
  component: ChatStoragePage,
});
