import { createFileRoute, redirect } from "@tanstack/react-router";
import { listChatSessions } from "@/lib/hey-api/sdk.gen";

export const Route = createFileRoute("/_authenticated/_chat/assistants/$personaId/")({
  /** Opening an assistant returns to its latest topic, or starts a new one when it has none. */
  beforeLoad: async ({ params: { personaId } }) => {
    const { data } = await listChatSessions({
      query: { personaId, limit: 1, archived: false },
      signal: AbortSignal.timeout(30_000),
    });
    const latest = data[0];
    throw latest
      ? redirect({ to: "/chat/$sessionId", params: { sessionId: latest.id }, replace: true })
      : redirect({ to: "/assistants/$personaId/new", params: { personaId }, replace: true });
  },
});
