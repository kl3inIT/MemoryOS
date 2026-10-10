import { createFileRoute, redirect } from "@tanstack/react-router";

/** The page became a tab of AI Providers; its old address still leads there. */
export const Route = createFileRoute("/_authenticated/admin/system-one")({
  beforeLoad: () => {
    throw redirect({ to: "/admin/ai-providers", search: { tab: "system-one" }, replace: true });
  },
});
