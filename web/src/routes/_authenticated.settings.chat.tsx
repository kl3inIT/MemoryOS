import { createFileRoute } from "@tanstack/react-router";
import { ChatSettingsPage } from "@/features/identity/chat-settings-page";

export const Route = createFileRoute("/_authenticated/settings/chat")({
  component: ChatSettingsPage,
});
