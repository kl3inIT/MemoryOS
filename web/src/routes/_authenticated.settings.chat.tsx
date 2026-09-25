import { createFileRoute } from "@tanstack/react-router";
import { ChatSettingsPage } from "@/features/chat/settings/chat-settings-page";

export const Route = createFileRoute("/_authenticated/settings/chat")({
  component: ChatSettingsPage,
});
