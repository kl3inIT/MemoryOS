import { createFileRoute } from "@tanstack/react-router";
import { VoiceAdminPage } from "@/features/voice/voice-admin-page";

export const Route = createFileRoute("/_authenticated/admin/voice")({
  component: VoiceAdminPage,
});
