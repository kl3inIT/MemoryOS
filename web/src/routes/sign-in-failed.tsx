import { createFileRoute } from "@tanstack/react-router";
import { SignInFailedScreen } from "../features/identity/session-states";

export const Route = createFileRoute("/sign-in-failed")({
  component: SignInFailedScreen,
});
