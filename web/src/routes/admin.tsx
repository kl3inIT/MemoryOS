import { createFileRoute } from "@tanstack/react-router";
import { ApplicationSessionBoundary } from "@/features/identity/application-session-boundary";

export const Route = createFileRoute("/admin")({
  component: () => <ApplicationSessionBoundary page="sources" />,
});
