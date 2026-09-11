import { createFileRoute } from "@tanstack/react-router";
import { ChatSharedPage } from "@/features/chat/chat-shared-page";
export const Route = createFileRoute("/_authenticated/shared/$sessionId")({
  component: function SharedRoute() {
    const { sessionId } = Route.useParams();
    return <ChatSharedPage key={sessionId} sessionId={sessionId} />;
  },
});
