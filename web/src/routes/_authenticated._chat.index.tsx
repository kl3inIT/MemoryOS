import { createFileRoute } from "@tanstack/react-router";
import { StartPageRedirect } from "@/features/identity/start-page-redirect";

export const Route = createFileRoute("/_authenticated/_chat/")({
  component: StartPageRedirect,
});
