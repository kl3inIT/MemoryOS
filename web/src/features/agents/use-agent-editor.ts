import { useState } from "react";
import { revalidateLogic, useStore } from "@tanstack/react-form";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useBlocker } from "@tanstack/react-router";
import { useAppForm, setServerErrors, useProblemErrors } from "@/components/form/app-form";
import { useMcpConnections } from "@/features/mcp/mcp-connections";
import {
  invalidateAgents,
  namedRefsOf,
  personaOf,
  personaSourcesOptions,
  type Persona,
} from "@/features/chat/chat-personas-api";
import {
  createChatPersonaLabelMutation,
  getChatPersonaQueryKey,
  listAvailableChatModelsQueryKey,
  listChatPersonaLabelsOptions,
  listChatPersonaLabelsQueryKey,
  listChatPersonaModelsOptions,
  updateChatPersonaMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { PersonaView } from "@/lib/hey-api/types.gen";
import { can } from "@/lib/resource-permissions";
import { agentRequest, agentValidation, initialValues } from "./agent-form";
import { agentDocumentSetsOptions } from "./agent-queries";
import { withRequestTimeout } from "@/lib/api";

/** The choices the editor offers: Sources, Document Sets, models, labels and MCP servers. */
export function useAgentChoices(agent: Persona) {
  const cache = useQueryClient();
  const sources = useQuery(personaSourcesOptions());
  const documentSets = useQuery(agentDocumentSetsOptions());
  // The assistant keeps offering the model it is pinned to.
  const models = useQuery(listChatPersonaModelsOptions({ path: { personaId: agent.id } }));
  const labels = useQuery({ ...listChatPersonaLabelsOptions(), select: namedRefsOf });
  const mcp = useMcpConnections();
  const createLabel = useMutation({
    ...withRequestTimeout(createChatPersonaLabelMutation()),
    onSuccess: () => cache.invalidateQueries({ queryKey: listChatPersonaLabelsQueryKey() }),
  });
  return { sources, documentSets, models, labels, mcp, createLabel };
}

/**
 * The revision this editor is in step with. Every save from the page (configuration, sharing, labels, listing)
 * moves it to the revision the server answered with, so the next save still detects a change made elsewhere
 * instead of overwriting it; a refetch alone never moves it.
 */
export function useAgentRevision(agent: Persona) {
  const cache = useQueryClient();
  const [revision, setRevision] = useState(agent.revision);
  return {
    revision,
    /** Takes the server's answer to a save from this page. */
    applied: async (view: PersonaView) => {
      const saved = personaOf(view);
      setRevision(saved.revision);
      cache.setQueryData(getChatPersonaQueryKey({ path: { personaId: saved.id } }), view);
      await invalidateAgents(cache);
      return saved;
    },
  };
}
export type AgentRevision = ReturnType<typeof useAgentRevision>;

/**
 * The configuration form: values from the saved assistant, saved in place. After a save the form starts again from
 * what the server kept; leaving the page with unsaved changes asks first, switching tabs does not.
 */
export function useAgentForm(agent: Persona, { revision, applied }: AgentRevision) {
  const cache = useQueryClient();
  const problemErrors = useProblemErrors();
  const update = useMutation(withRequestTimeout(updateChatPersonaMutation()));
  const [initial, setInitial] = useState(() => initialValues(agent));
  const [saved, setSaved] = useState(false);
  const form = useAppForm({
    defaultValues: initial,
    validationLogic: revalidateLogic(),
    validators: { onDynamic: agentValidation },
    onSubmit: async ({ value, formApi }) => {
      if (!value.name.trim()) return;
      let view: PersonaView;
      try {
        view = await update.mutateAsync({
          path: { personaId: agent.id },
          query: { revision },
          body: agentRequest(value, agent),
        });
      } catch (cause) {
        setServerErrors(formApi, problemErrors(cause));
        return;
      }
      const next = initialValues(await applied(view));
      setInitial(next);
      formApi.reset(next);
      setSaved(true);
      await cache.invalidateQueries({ queryKey: listAvailableChatModelsQueryKey() });
    },
  });
  const initialJson = JSON.stringify(initial);
  const dirty = useStore(form.store, (state) => JSON.stringify(state.values) !== initialJson);

  const unsaved = () => JSON.stringify(form.state.values) !== initialJson;
  const blocker = useBlocker({
    // The tabs live in the address, so only leaving the editor's page counts.
    shouldBlockFn: ({ current, next }) => current.pathname !== next.pathname && unsaved(),
    enableBeforeUnload: unsaved,
    withResolver: true,
  });

  return {
    form,
    dirty,
    /** The last save succeeded and nothing changed since. */
    saved: saved && !dirty,
    editable: can(agent, "edit"),
    blocker,
  };
}
export type AgentFormApi = ReturnType<typeof useAgentForm>["form"];
export type AgentChoices = ReturnType<typeof useAgentChoices>;
