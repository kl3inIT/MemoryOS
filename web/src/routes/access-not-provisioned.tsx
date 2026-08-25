import { createFileRoute } from "@tanstack/react-router";
import { AccessNotProvisionedScreen } from "../features/identity/session-states";

export const Route = createFileRoute("/access-not-provisioned")({
  component: AccessNotProvisionedScreen,
});
