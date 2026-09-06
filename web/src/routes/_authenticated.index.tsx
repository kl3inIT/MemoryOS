import { createFileRoute } from "@tanstack/react-router";
import { NewSessionPage } from "@/features/identity/new-session-page";

export const Route = createFileRoute("/_authenticated/")({
  component: NewSessionPage,
});
