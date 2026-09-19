import { createFileRoute } from "@tanstack/react-router";
import { AiCostsPage } from "@/features/usage/ai-costs-page";

export const Route = createFileRoute("/_authenticated/admin/ai-costs")({
  component: AiCostsPage,
});
