import { createFileRoute } from "@tanstack/react-router";

export const Route = createFileRoute("/_authenticated/_chat/chat/$sessionId")({
  /** `ask` is a first question sent once into a new agent conversation, from the agent's detail view. */
  validateSearch: (search: Record<string, unknown>): { ask?: string } => ({
    ask: typeof search.ask === "string" && search.ask.trim() ? search.ask : undefined,
  }),
  component: () => null,
});
