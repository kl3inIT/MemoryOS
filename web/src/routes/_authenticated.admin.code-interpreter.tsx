import { createFileRoute } from "@tanstack/react-router";
import { ChatInterpreterSettings } from "@/features/chat/chat-interpreter-settings";

export const Route = createFileRoute("/_authenticated/admin/code-interpreter")({
  component: ChatInterpreterSettings,
});
