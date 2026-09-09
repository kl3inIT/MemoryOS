import { createFileRoute } from "@tanstack/react-router";
import { ExistingChatPage } from "@/features/chat/chat-page";

export const Route = createFileRoute("/_authenticated/chat/$sessionId")({
  component: ExistingChatPage,
});
