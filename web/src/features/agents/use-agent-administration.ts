import { useAppTranslation } from "@/i18n/use-app-translation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useActionNotifications } from "@/components/ui/action-notifications";
import { invalidateAgents, personaOf, type Persona } from "@/features/chat/chat-personas-api";
import {
  reorderChatPersonasMutation,
  restoreChatPersonaMutation,
  setChatPersonaListingMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { PersonaView } from "@/lib/hey-api/types.gen";
import { administrationAgentsOptions } from "./agent-queries";

/**
 * Agent administration for AGENTS_MANAGE: every agent in display order, restoring deleted agents, listing and
 * featuring, and reordering by dragging (Onyx admin agent ordering).
 */
export function useAgentAdministration(includeDeleted: boolean) {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const notify = useActionNotifications();
  const options = administrationAgentsOptions(includeDeleted);
  const agents = useQuery({ ...options, select: (views) => views.map(personaOf) });
  const refresh = () => invalidateAgents(cache);
  const restore = useMutation({ ...restoreChatPersonaMutation(), onSuccess: refresh });
  const listing = useMutation({ ...setChatPersonaListingMutation(), onSuccess: refresh });
  const writeOrder = reorderChatPersonasMutation().mutationFn!;
  // Shows the new order at once, keeping the views as the API sent them; a failure puts the old order back.
  const reorder = useMutation({
    mutationFn: (next: Persona[], context) =>
      writeOrder(
        {
          body: {
            personaIds: next
              .filter((agent) => !agent.builtin && !agent.deletedAt)
              .map((agent) => agent.id),
          },
        },
        context,
      ),
    onMutate: async (next, context) => {
      await context.client.cancelQueries({ queryKey: options.queryKey });
      const previous = context.client.getQueryData<PersonaView[]>(options.queryKey);
      context.client.setQueryData<PersonaView[]>(options.queryKey, (views) =>
        next.flatMap((agent) => views?.filter((view) => view.id === agent.id) ?? []),
      );
      return { previous };
    },
    onError: (_cause, _next, saved, context) =>
      context.client.setQueryData(options.queryKey, saved?.previous),
    onSettled: refresh,
  });
  return {
    agents,
    restore: (agent: Persona) => restore.mutate({ path: { personaId: agent.id } }),
    setListing: (agent: Persona, change: { listed?: boolean; featured?: boolean }) =>
      listing.mutate(
        {
          path: { personaId: agent.id },
          query: { revision: agent.revision },
          body: {
            listed: change.listed ?? agent.listed,
            featured: change.featured ?? agent.featured,
            displayPriority: agent.displayPriority ?? undefined,
          },
        },
        {
          onSuccess: () => {
            if (change.featured !== undefined)
              notify({
                title: change.featured
                  ? ui("Đã đặt {{v1}} nổi bật", { v1: agent.name })
                  : ui("Đã bỏ nổi bật {{v1}}", { v1: agent.name }),
                tone: "success",
              });
          },
        },
      ),
    /** One request writes the whole order in a server transaction. */
    reorder: (next: Persona[]) => reorder.mutate(next),
    /** The failure of the latest action, if it failed. */
    error: [restore, listing, reorder]
      .filter((mutation) => mutation.submittedAt > 0)
      .sort((a, b) => b.submittedAt - a.submittedAt)[0]?.error,
  };
}
