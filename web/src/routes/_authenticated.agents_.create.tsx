import { createFileRoute } from "@tanstack/react-router";
import { AgentEditorPage } from "@/features/agents/agent-editor";

export const Route = createFileRoute("/_authenticated/agents_/create")({
  component: () => <AgentEditorPage />,
});
