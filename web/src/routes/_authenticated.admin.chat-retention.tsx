import { createFileRoute } from "@tanstack/react-router";
import { ChatRetentionPage } from "@/features/chat/chat-retention-page";

export const Route = createFileRoute("/_authenticated/admin/chat-retention")({
  component: ChatRetentionPage,
});
