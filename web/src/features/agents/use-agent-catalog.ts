import { useAppTranslation } from "@/i18n/use-app-translation";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "@tanstack/react-router";
import { useActionNotifications } from "@/components/ui/action-notifications";
import { invalidateAgents, type Persona } from "@/features/chat/chat-personas-api";
import { personLabel } from "@/features/identity/principals";
import { actionErrorText } from "@/lib/action-errors";
import { withRequestTimeout } from "@/lib/api";
import {
  copyChatPersonaTemplateMutation,
  setChatPersonaListingMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import { usePinUpdates } from "./agent-pins";

/** Who an agent's card names as its owner: MemoryOS, the owning Group or person, or nobody. */
export function useOwnerName() {
  const ui = useAppTranslation();
  return (agent: Persona) =>
    agent.builtin || agent.template
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
const rank = (agent: Persona) => (agent.builtin ? 0 : agent.featured ? 1 : 2);

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
    // The built-in agent, then featured ones, lead the catalog instead of forming a sparse section of their own;
    // the API's order (templates next) holds otherwise.
    shown: [...visible].sort((a, b) => rank(a) - rank(b)),
  };
}

/**
 * Opening an agent's latest topic, or a new topic with a first question, which pins it to the sidebar as in Onyx;
 * and pinning or unpinning it. Nothing is written until the first question is sent.
 */
export function useAgentChatActions() {
  const navigate = useNavigate();
  const updatePins = usePinUpdates();
  const start = useMutation({
    mutationFn: async ({ agent, ask }: { agent: Persona; ask?: string }) => {
      if (!agent.pinned && !agent.builtin)
        await updatePins((current) =>
          current.includes(agent.id) ? current : [...current, agent.id],
        );
      await (ask
        ? navigate({
            to: "/assistants/$personaId/new",
            params: { personaId: agent.id },
            state: (previous) => ({ ...previous, ask }),
          })
        : navigate({ to: "/assistants/$personaId", params: { personaId: agent.id } }));
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

/**
 * What a MemoryOS template offers beyond chatting: "Tạo bản riêng" copies it into the person's own assistant and opens
 * that in the editor, as Dify's "Use template" opens the new app; an administrator shows or hides it for everyone.
 */
export function useTemplateActions() {
  const ui = useAppTranslation();
  const navigate = useNavigate();
  const cache = useQueryClient();
  const notify = useActionNotifications();
  const onError = (cause: unknown) => notify({ title: actionErrorText(cause), tone: "error" });
  const copy = useMutation({
    ...withRequestTimeout(copyChatPersonaTemplateMutation()),
    onSuccess: async (created) => {
      await invalidateAgents(cache);
      if (created.id)
        await navigate({ to: "/agents/$agentId/edit", params: { agentId: created.id } });
    },
    onError,
  });
  const listing = useMutation({
    ...withRequestTimeout(setChatPersonaListingMutation()),
    onSuccess: (_view, { path }) => invalidateAgents(cache, path.personaId),
    onError,
  });
  return {
    copy: (agent: Persona) => copy.mutate({ path: { personaId: agent.id } }),
    /** The template being copied. */
    copying: copy.isPending ? copy.variables.path.personaId : undefined,
    setListed: (agent: Persona, listed: boolean) =>
      listing.mutate(
        {
          path: { personaId: agent.id },
          query: { revision: agent.revision },
          body: { listed, featured: false },
        },
        {
          onSuccess: () =>
            notify({
              title: listed
                ? ui("Đã hiện lại {{v1}}", { v1: agent.name })
                : ui("Đã ẩn {{v1}} với mọi người", { v1: agent.name }),
              tone: "success",
            }),
        },
      ),
  };
}
