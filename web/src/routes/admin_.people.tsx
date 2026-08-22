import { createFileRoute } from "@tanstack/react-router";
import { IdentityGate } from "@/features/identity/identity-gate";

export const Route = createFileRoute("/admin_/people")({
  component: () => <IdentityGate surface="people" />,
});
