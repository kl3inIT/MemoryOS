import { createFileRoute } from "@tanstack/react-router";
import { AgentEditorPage } from "@/features/agents/agent-editor";

export const Route = createFileRoute("/_authenticated/agents_/$agentId/edit")({
  component: function EditAgentRoute() {
    const { agentId } = Route.useParams();
    return <AgentEditorPage key={agentId} agentId={agentId} />;
  },
});
