import { createFileRoute } from "@tanstack/react-router";
import { ChatHistoryPage } from "@/features/chat-history/chat-history-page";

export const Route = createFileRoute("/_authenticated/admin/chat-history")({
  component: ChatHistoryPage,
});
