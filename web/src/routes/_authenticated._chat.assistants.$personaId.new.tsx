import { createFileRoute } from "@tanstack/react-router";

export const Route = createFileRoute("/_authenticated/_chat/assistants/$personaId/new")({
  /** A new topic with the assistant, written down by its first send. */
  component: () => null,
});
