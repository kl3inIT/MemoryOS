import { createFileRoute } from "@tanstack/react-router";
import { ChatProjectPage } from "@/features/chat/chat-projects-page";
export const Route = createFileRoute("/_authenticated/projects/$projectId")({
  component: function ProjectRoute() {
    const { projectId } = Route.useParams();
    return <ChatProjectPage key={projectId} projectId={projectId} />;
  },
});
