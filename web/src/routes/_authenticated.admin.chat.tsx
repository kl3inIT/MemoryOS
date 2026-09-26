import { createFileRoute } from "@tanstack/react-router";
import { AdminChatSettings } from "@/features/chat/settings/admin-chat-settings";

export const Route = createFileRoute("/_authenticated/admin/chat")({
  component: AdminChatSettings,
});
