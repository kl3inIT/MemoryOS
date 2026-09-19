import { createFileRoute, Outlet, useMatchRoute } from "@tanstack/react-router";
import { AppShell } from "@/components/app-shell/app-shell";
import { useAppTranslation } from "@/i18n/use-app-translation";

/** Personal settings with Onyx's tabs in the administration-style sidebar (MEM-145). */
export const Route = createFileRoute("/_authenticated/settings")({
  component: function PersonalSettingsLayout() {
    const ui = useAppTranslation();
    const matchRoute = useMatchRoute();
    const page = matchRoute({ to: "/settings/chat" })
      ? "chat"
      : matchRoute({ to: "/settings/usage" })
        ? "usage"
        : "general";
    const titles = { general: "General", chat: "Chat", usage: "Usage" } as const;
    return (
      <AppShell area="settings" settingsPage={page} pageTitle={ui(titles[page])}>
        <Outlet />
      </AppShell>
    );
  },
});
