import { createFileRoute } from "@tanstack/react-router";
import { ChatImageSettings } from "@/features/chat/image/chat-image-settings";

export const Route = createFileRoute("/_authenticated/admin/image-generation")({
  component: ChatImageSettings,
});
