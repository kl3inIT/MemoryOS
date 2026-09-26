import { createFileRoute } from "@tanstack/react-router";
import { z } from "zod";
import { AgentsPage } from "@/features/agents/agents-page";

export const Route = createFileRoute("/_authenticated/agents")({
  /** `agent` opens that agent's detail view, the target of a copied share link. */
  validateSearch: z.object({ agent: z.string().optional().catch(undefined) }),
  component: AgentsPage,
});
