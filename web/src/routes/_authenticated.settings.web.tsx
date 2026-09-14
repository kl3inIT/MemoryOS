import { createFileRoute } from "@tanstack/react-router";
import { AppShell } from "@/components/app-shell/app-shell";
import { ChatWebSettings } from "@/features/chat/chat-web-settings";
import { useAppTranslation } from "@/i18n/use-app-translation";

export const Route = createFileRoute("/_authenticated/settings/web")({
  component: function WebSettingsRoute() {
    const ui = useAppTranslation();
    return (
      <AppShell area="admin" adminPage="web" pageTitle={ui("Tìm kiếm Web")}>
        <ChatWebSettings />
      </AppShell>
    );
  },
});
