import { createFileRoute } from "@tanstack/react-router";

export const Route = createFileRoute("/_authenticated/_chat/")({
  component: () => null,
});
