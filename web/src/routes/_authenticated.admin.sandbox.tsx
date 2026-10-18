import { createFileRoute } from "@tanstack/react-router";
import { ChatSandboxSettings } from "@/features/chat/interpreter/chat-sandbox-settings";

export const Route = createFileRoute("/_authenticated/admin/sandbox")({
  component: ChatSandboxSettings,
});
