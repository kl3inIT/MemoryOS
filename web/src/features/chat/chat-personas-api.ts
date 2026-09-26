import { queryOptions, type QueryClient } from "@tanstack/react-query";
import type { NamedRef, Person } from "@/features/identity/principals";
import { allPages } from "@/lib/all-pages";
import {
  getChatPersonaQueryKey,
  listChatPersonaLabelsQueryKey,
  listChatPersonaPinsQueryKey,
  listChatPersonasForAdministrationQueryKey,
  listChatPersonaSourcesQueryKey,
  listChatPersonasQueryKey,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import { listChatPersonas, listChatPersonaSources } from "@/lib/hey-api/sdk.gen";
import type { AgentPerson, AgentRef, PersonaView, SourceOption } from "@/lib/hey-api/types.gen";

export const agentTools = ["search", "web_search", "image_generation", "code_interpreter"] as const;
export type AgentTool = (typeof agentTools)[number];
export type AgentPermission = "VIEWER" | "EDITOR";
export type AgentView = "ALL" | "MINE" | "SHARED";

/** A named reference the API sends with optional fields; one without an id is dropped. */
export function namedRefsOf(refs: AgentRef[] = []): NamedRef[] {
  return refs.flatMap(({ id, name = "" }) => (id === undefined ? [] : [{ id, name }]));
}

function personOf(person: AgentPerson | undefined): Person | null {
  return person?.actorId === undefined
    ? null
    : { actorId: person.actorId, name: person.name, email: person.email };
}

/**
 * An agent as the API sends it. The published contract marks every field of `PersonaView` optional,
 * although the API always sends them; the view is narrowed once here. An absent permission hint hides
 * its control (fail closed).
 */
export function personaOf({
  id,
  revision,
  builtin = false,
  permissions = {},
  name = "",
  description = "",
  instructions = "",
  taskPrompt = "",
  starterPrompts = [],
  sourceIds = [],
  sources,
  documentSetIds = [],
  documentSets,
  tools = [],
  mcpServers,
  fileIds = [],
  modelConfigurationId = null,
  contextTokenLimit = null,
  outputTokenLimit = null,
  iconName,
  hasAvatar = false,
  labels,
  owner,
  vacant = false,
  userShares = [],
  groupShares = [],
  isPublic = false,
  publicPermission = "VIEWER",
  listed = true,
  featured = false,
  displayPriority,
  replaceBaseSystemPrompt = false,
  knowledgeCutoff,
  pinned = false,
  deletedAt,
}: PersonaView) {
  if (id === undefined || revision === undefined) {
    throw new TypeError("An agent view carries its id and revision.");
  }
  return {
    id,
    revision,
    builtin,
    permissions: {
      edit: permissions.edit ?? false,
      share: permissions.share ?? false,
      setPublic: permissions.setPublic ?? false,
      delete: permissions.delete ?? false,
      transfer: permissions.transfer ?? false,
      leave: permissions.leave ?? false,
      manage: permissions.manage ?? false,
    },
    name,
    description,
    instructions,
    taskPrompt,
    starterPrompts,
    sourceIds,
    sources: namedRefsOf(sources),
    documentSetIds,
    documentSets: namedRefsOf(documentSets),
    tools,
    mcpServers: namedRefsOf(mcpServers),
    fileIds,
    modelConfigurationId,
    contextTokenLimit,
    outputTokenLimit,
    iconName: iconName ?? null,
    hasAvatar,
    labels: namedRefsOf(labels),
    owner: {
      actor: personOf(owner?.actor),
      group: owner?.group?.id === undefined ? null : namedRefsOf([owner.group])[0]!,
    },
    vacant,
    userShares: userShares.flatMap((share) => {
      const person = personOf(share.person);
      return person ? [{ person, permission: share.permission ?? "VIEWER" }] : [];
    }),
    groupShares: groupShares.flatMap((share) =>
      namedRefsOf(share.group ? [share.group] : []).map((group) => ({
        group,
        permission: share.permission ?? "VIEWER",
      })),
    ),
    isPublic,
    publicPermission,
    listed,
    featured,
    displayPriority: displayPriority ?? null,
    replaceBaseSystemPrompt,
    knowledgeCutoff: knowledgeCutoff ?? null,
    pinned,
    deletedAt: deletedAt ?? null,
  };
}
export type Persona = ReturnType<typeof personaOf>;

/** A Source an agent or a Document Set can search, as the API sends it. */
function personaSourceOf({ id, name = "", type }: SourceOption) {
  return id === undefined ? [] : [{ id, name, type: type ?? "" }];
}

/** The visibility shown on cards: Tenant-wide, shared with people or Groups, or private. */
export function agentVisibility(persona: Persona) {
  if (persona.builtin || persona.isPublic) return "public" as const;
  return persona.userShares.length > 0 || persona.groupShares.length > 0
    ? ("shared" as const)
    : ("private" as const);
}

function loadPersonas(signal: AbortSignal, view: AgentView = "ALL") {
  return allPages(async (offset) =>
    (await listChatPersonas({ query: { offset, limit: 100, view }, signal })).data.map(personaOf),
  );
}

/**
 * Every agent the actor can use in one view, for pickers and lookups that must not stop at the first page.
 * The key extends the generated list key, so `invalidateAgents` refreshes it with the paged reads.
 */
export function personasOptions(view: AgentView = "ALL") {
  return queryOptions({
    queryKey: [...listChatPersonasQueryKey({ query: { view } }), "all"] as const,
    queryFn: ({ signal }) => loadPersonas(signal, view),
  });
}

/** Every Source the actor can give an agent or a Document Set. */
function loadPersonaSources(signal: AbortSignal) {
  return allPages(async (offset) =>
    (await listChatPersonaSources({ query: { offset, limit: 100 }, signal })).data.flatMap(
      personaSourceOf,
    ),
  );
}

/** Every selectable Source, keyed under the generated Source-option list key. */
export function personaSourcesOptions() {
  return queryOptions({
    queryKey: [...listChatPersonaSourcesQueryKey(), "all"] as const,
    queryFn: ({ signal }) => loadPersonaSources(signal),
  });
}

/**
 * Refreshes every read of agents after one changes: the lists (all views, all pages, the administration
 * list), the sidebar pins, the labels and, when given, the changed agent.
 */
export function invalidateAgents(cache: QueryClient, personaId?: string) {
  return Promise.all([
    cache.invalidateQueries({ queryKey: listChatPersonasQueryKey() }),
    cache.invalidateQueries({ queryKey: listChatPersonasForAdministrationQueryKey() }),
    cache.invalidateQueries({ queryKey: listChatPersonaPinsQueryKey() }),
    cache.invalidateQueries({ queryKey: listChatPersonaLabelsQueryKey() }),
    personaId === undefined
      ? undefined
      : cache.invalidateQueries({ queryKey: getChatPersonaQueryKey({ path: { personaId } }) }),
  ]);
}
