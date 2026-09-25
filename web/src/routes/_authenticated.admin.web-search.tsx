import { createFileRoute } from "@tanstack/react-router";
import { ChatWebSettings } from "@/features/chat/web-search/chat-web-settings";

export const Route = createFileRoute("/_authenticated/admin/web-search")({
  component: ChatWebSettings,
});
