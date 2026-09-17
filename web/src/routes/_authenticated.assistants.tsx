import { createFileRoute, redirect } from "@tanstack/react-router";

/** The assistants page became the agent gallery; old links keep working. */
export const Route = createFileRoute("/_authenticated/assistants")({
  beforeLoad: () => {
    throw redirect({ to: "/agents", replace: true });
  },
});
