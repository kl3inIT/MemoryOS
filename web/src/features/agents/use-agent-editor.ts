import { useEffect, useRef, useState } from "react";
import { revalidateLogic, useStore } from "@tanstack/react-form";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useBlocker, useNavigate } from "@tanstack/react-router";
import { useAppForm, setServerErrors, useProblemErrors } from "@/components/form/app-form";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { useMcpConnections } from "@/features/mcp/mcp-connections";
import {
  invalidateAgents,
  namedRefsOf,
  personaSourcesOptions,
  type Persona,
} from "@/features/chat/chat-personas-api";
import {
  createChatPersonaMutation,
  listAvailableChatModelsOptions,
  listAvailableChatModelsQueryKey,
  listChatPersonaLabelsOptions,
  listChatPersonaModelsOptions,
  updateChatPersonaMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import { can } from "@/lib/resource-permissions";
import {
  agentRequest,
  agentValidation,
  initialValues,
  readDraft,
  writeDraft,
  type ModelLimits,
} from "./agent-form";
import { agentDocumentSetsOptions } from "./agent-queries";

/** The choices the editor offers: Sources, Document Sets, models, labels and MCP servers. */
export function useAgentChoices(agent?: Persona) {
  const sources = useQuery(personaSourcesOptions());
  const documentSets = useQuery(agentDocumentSetsOptions());
  // An existing agent keeps offering the model it is pinned to; a new one offers the actor's models.
  const personaModels = useQuery({
    ...listChatPersonaModelsOptions({ path: { personaId: agent?.id ?? "" } }),
    enabled: agent !== undefined,
  });
  const availableModels = useQuery({
    ...listAvailableChatModelsOptions(),
    enabled: agent === undefined,
  });
  const models = agent ? personaModels : availableModels;
  const labels = useQuery({ ...listChatPersonaLabelsOptions(), select: namedRefsOf });
  const mcp = useMcpConnections();
  return { sources, documentSets, models, labels, mcp };
}

/**
 * The editor form: values from the agent or this browser's draft, saved through create or update. A new agent's
 * draft follows every change; leaving an edited agent with unsaved changes asks first.
 */
export function useAgentForm(agent: Persona | undefined, models: ModelLimits[] | undefined) {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const navigate = useNavigate();
  const problemErrors = useProblemErrors();
  const { actorId } = useApplicationSession();
  const create = useMutation(createChatPersonaMutation());
  const update = useMutation(updateChatPersonaMutation());
  const [initial] = useState(() => initialValues(agent));
  const [restored, setRestored] = useState(() => (agent ? undefined : readDraft(actorId)));
  const saved = useRef(false);
  const form = useAppForm({
    defaultValues: restored ?? initial,
    validationLogic: revalidateLogic(),
    validators: { onDynamic: agentValidation(ui, models) },
    onSubmit: async ({ value, formApi }) => {
      if (!value.name.trim()) return;
      const body = agentRequest(value, agent);
      try {
        if (agent)
          await update.mutateAsync({
            path: { personaId: agent.id },
            query: { revision: agent.revision },
            body,
          });
        else await create.mutateAsync({ body });
      } catch (cause) {
        setServerErrors(formApi, problemErrors(cause));
        return;
      }
      saved.current = true;
      if (!agent) writeDraft(actorId, undefined);
      await Promise.all([
        invalidateAgents(cache, agent?.id),
        cache.invalidateQueries({ queryKey: listAvailableChatModelsQueryKey() }),
      ]);
      await navigate({ to: "/agents" });
    },
  });
  const initialJson = JSON.stringify(initial);
  const values = useStore(form.store, (state) => state.values);
  const dirty = JSON.stringify(values) !== initialJson;

  // A new agent keeps a draft in this browser until it is saved or discarded.
  useEffect(() => {
    if (!agent && !saved.current) writeDraft(actorId, dirty ? values : undefined);
  }, [actorId, agent, dirty, values]);

  const unsaved = () =>
    !!agent && !saved.current && JSON.stringify(form.state.values) !== initialJson;
  const blocker = useBlocker({
    shouldBlockFn: unsaved,
    enableBeforeUnload: unsaved,
    withResolver: true,
  });

  return {
    form,
    dirty,
    editable: agent ? can(agent, "edit") : true,
    blocker,
    /** A draft of a new agent was restored from this browser. */
    restored: restored !== undefined,
    discardDraft: () => {
      writeDraft(actorId, undefined);
      form.reset(initial);
      setRestored(undefined);
    },
  };
}
export type AgentFormApi = ReturnType<typeof useAgentForm>["form"];
export type AgentChoices = ReturnType<typeof useAgentChoices>;
