import { createFileRoute } from "@tanstack/react-router";
import { ChatArchivedSessionsPage } from "@/features/chat/chat-archived-sessions";

export const Route = createFileRoute("/_authenticated/settings/archived-chats")({
  component: ChatArchivedSessionsPage,
});
