import { useAppTranslation } from "@/i18n/use-app-translation";
import { useMutation } from "@tanstack/react-query";
import { useNavigate } from "@tanstack/react-router";
import { initialChatTitle } from "@/features/chat/chat-api";
import type { Persona } from "@/features/chat/chat-personas-api";
import { useRefreshChatSessions } from "@/features/chat/runtime/chat-threads-context";
import { personLabel } from "@/features/identity/principals";
import { createChatSessionMutation } from "@/lib/hey-api/@tanstack/react-query.gen";
import { usePinUpdates } from "./agent-pins";

/** Who an agent's card names as its owner: MemoryOS, the owning Group or person, or nobody. */
export function useOwnerName() {
  const ui = useAppTranslation();
  return (agent: Persona) =>
    agent.builtin
      ? ui("MemoryOS")
      : (agent.owner.group?.name ?? (personLabel(agent.owner.actor) || ui("Chưa có chủ sở hữu")));
}

type CatalogFilters = {
  /** The folded search text. */
  query: string;
  labelId: string | undefined;
  /** Owner names; empty means everyone. */
  creators: string[];
};

/**
 * The catalog under its filters (Langdock, Lindy): the agents shown with featured ones first, label chips with
 * facet counts under the other filters, and the owners the creator filter offers.
 */
export function agentCatalog(
  agents: Persona[],
  { query, labelId, creators }: CatalogFilters,
  ownerName: (agent: Persona) => string,
) {
  const matchesSearch = (agent: Persona) =>
    !query ||
    agent.name.toLocaleLowerCase().includes(query) ||
    agent.description.toLocaleLowerCase().includes(query) ||
    agent.labels.some((label) => label.name.toLocaleLowerCase().includes(query));
  const matchesCreator = (agent: Persona) =>
    creators.length === 0 || creators.includes(ownerName(agent));
  const labelCounts = new Map<string, { label: string; count: number }>();
  for (const agent of agents.filter((agent) => matchesSearch(agent) && matchesCreator(agent)))
    for (const label of agent.labels) {
      const entry = labelCounts.get(label.id) ?? { label: label.name, count: 0 };
      entry.count += 1;
      labelCounts.set(label.id, entry);
    }
  const labelChips = [...labelCounts.entries()]
    .sort((a, b) => b[1].count - a[1].count || a[1].label.localeCompare(b[1].label))
    .map(([value, entry]) => ({ value, label: entry.label, count: entry.count }));
  // The selected label stays visible (with no matches) so it can always be cleared.
  const selectedLabel =
    labelId && agents.flatMap((agent) => agent.labels).find((label) => label.id === labelId);
  if (selectedLabel && !labelChips.some((chip) => chip.value === labelId))
    labelChips.unshift({ value: selectedLabel.id, label: selectedLabel.name, count: 0 });
  const visible = agents.filter(
    (agent) =>
      (!labelId || agent.labels.some((label) => label.id === labelId)) &&
      matchesCreator(agent) &&
      matchesSearch(agent),
  );
  return {
    labelChips,
    owners: [...new Set(agents.map(ownerName))].sort((a, b) => a.localeCompare(b)),
    // Featured agents lead the catalog instead of forming a sparse section of their own.
    shown: [...visible].sort((a, b) => Number(b.featured) - Number(a.featured)),
  };
}

/**
 * Starting a conversation with an agent, which pins it to the sidebar as in Onyx, and pinning or unpinning it.
 */
export function useAgentChatActions() {
  const navigate = useNavigate();
  const refreshSessions = useRefreshChatSessions();
  const updatePins = usePinUpdates();
  const createSession = useMutation(createChatSessionMutation());
  const start = useMutation({
    mutationFn: async ({ agent, ask }: { agent: Persona; ask?: string }) => {
      if (!agent.pinned && !agent.builtin)
        await updatePins((current) =>
          current.includes(agent.id) ? current : [...current, agent.id],
        );
      const session = await createSession.mutateAsync({
        body: { title: initialChatTitle(agent.name), personaId: agent.id },
      });
      await refreshSessions();
      await navigate({
        to: "/chat/$sessionId",
        params: { sessionId: session.id },
        search: { ask },
      });
    },
  });
  const pin = useMutation({
    mutationFn: (agent: Persona) =>
      updatePins((current) =>
        agent.pinned
          ? current.filter((id) => id !== agent.id)
          : current.includes(agent.id)
            ? current
            : [...current, agent.id],
      ),
  });
  return {
    start,
    pin,
    /** The agent whose conversation is being opened. */
    starting: start.isPending ? start.variables.agent.id : undefined,
    /** The failure of the latest start or pin, if it failed. */
    error: start.submittedAt >= pin.submittedAt ? start.error : pin.error,
  };
}
