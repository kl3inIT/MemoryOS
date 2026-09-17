import { createFileRoute } from "@tanstack/react-router";
import { AgentsPage } from "@/features/agents/agents-page";

export const Route = createFileRoute("/_authenticated/agents")({
  /** `agent` opens that agent's detail view, the target of a copied share link. */
  validateSearch: (search: Record<string, unknown>): { agent?: string } => ({
    agent: typeof search.agent === "string" ? search.agent : undefined,
  }),
  component: AgentsPage,
});
