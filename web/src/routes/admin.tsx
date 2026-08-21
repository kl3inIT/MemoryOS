import { createFileRoute } from "@tanstack/react-router";
import { IdentityGate } from "@/features/identity/identity-gate";

export const Route = createFileRoute("/admin")({
  component: () => <IdentityGate surface="admin" />,
});
