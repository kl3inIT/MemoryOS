import { createFileRoute, redirect } from "@tanstack/react-router";

/** An assistant is created from a short dialog on the agents page; this address opens it there. */
export const Route = createFileRoute("/_authenticated/agents_/create")({
  beforeLoad: () => {
    throw redirect({ to: "/agents", search: { create: true }, replace: true });
  },
});
