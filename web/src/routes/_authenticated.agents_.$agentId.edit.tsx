import { createFileRoute, stripSearchParams } from "@tanstack/react-router";
import { AgentEditorPage } from "@/features/agents/agent-editor";
import {
  agentEditorSearchSchema,
  DEFAULT_AGENT_EDITOR_SEARCH,
} from "@/features/agents/agent-editor-search";

export const Route = createFileRoute("/_authenticated/agents_/$agentId/edit")({
  validateSearch: agentEditorSearchSchema,
  search: { middlewares: [stripSearchParams(DEFAULT_AGENT_EDITOR_SEARCH)] },
  component: function EditAgentRoute() {
    const { agentId } = Route.useParams();
    const { tab } = Route.useSearch();
    const navigate = Route.useNavigate();
    return (
      <AgentEditorPage
        key={agentId}
        agentId={agentId}
        tab={tab}
        onTabChange={(next) =>
          void navigate({ search: { tab: next }, replace: true, resetScroll: false })
        }
      />
    );
  },
});
