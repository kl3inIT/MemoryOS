import { z } from "zod";
import { agentTools, type Persona } from "@/features/chat/chat-personas-api";
import type { PersonaInput } from "@/lib/hey-api/types.gen";

/** A conversation starter list longer than this is refused by the API. */
export const maxStarterPrompts = 8;

/**
 * The editor's values, named as the request fields they become so that an `ApiProblem` field violation lands on
 * its control. Numbers stay text while they are typed. The same shape is kept as a browser draft.
 */
const agentValuesSchema = z.object({
  name: z.string(),
  description: z.string(),
  iconName: z.string(),
  avatarFileId: z.string().nullable(),
  keepAvatar: z.boolean(),
  labelIds: z.array(z.string()),
  instructions: z.string(),
  taskPrompt: z.string(),
  starterPrompts: z.array(z.string()),
  sourceIds: z.array(z.string()),
  documentSetIds: z.array(z.string()),
  fileIds: z.array(z.string()),
  knowledgeCutoff: z.string(),
  tools: z.array(z.string()),
  mcpServerIds: z.array(z.string()),
  modelConfigurationId: z.string(),
  contextTokenLimit: z.string(),
  outputTokenLimit: z.string(),
  replaceBaseSystemPrompt: z.boolean(),
});
export type AgentValues = z.infer<typeof agentValuesSchema>;

/**
 * The editor's validation. A nameless agent cannot be submitted (the save action waits for a name), and the
 * starter list never outgrows what the API keeps, so the values only need their shape here; the server's field
 * violations are placed on the controls after a failed save.
 */
export const agentValidation = agentValuesSchema;

export function initialValues(agent?: Persona): AgentValues {
  return {
    name: agent?.name ?? "",
    description: agent?.description ?? "",
    iconName: agent?.iconName ?? "bot",
    avatarFileId: null,
    keepAvatar: agent?.hasAvatar ?? false,
    labelIds: agent?.labels.map((label) => label.id) ?? [],
    instructions: agent?.instructions ?? "",
    taskPrompt: agent?.taskPrompt ?? "",
    starterPrompts: agent?.starterPrompts ?? [],
    sourceIds: agent?.sourceIds ?? [],
    documentSetIds: agent?.documentSetIds ?? [],
    fileIds: agent?.fileIds ?? [],
    knowledgeCutoff: agent?.knowledgeCutoff?.slice(0, 10) ?? "",
    tools: agent?.tools ?? [...agentTools],
    mcpServerIds: agent?.mcpServers.map((server) => server.id) ?? [],
    modelConfigurationId: agent?.modelConfigurationId ?? "",
    contextTokenLimit: agent?.contextTokenLimit?.toString() ?? "",
    outputTokenLimit: agent?.outputTokenLimit?.toString() ?? "",
    replaceBaseSystemPrompt: agent?.replaceBaseSystemPrompt ?? false,
  };
}

/** The create or update request for the editor's values. */
export function agentRequest(values: AgentValues, agent?: Persona): PersonaInput {
  return {
    name: values.name.trim(),
    description: values.description,
    instructions: values.instructions,
    taskPrompt: values.taskPrompt,
    starterPrompts: values.starterPrompts.map((prompt) => prompt.trim()).filter(Boolean),
    sourceIds: values.sourceIds,
    documentSetIds: values.documentSetIds,
    tools: values.tools,
    // The built-in agent uses every MCP server and file its reader may use.
    mcpServerIds: agent?.builtin ? [] : values.mcpServerIds,
    fileIds: agent?.builtin ? undefined : values.fileIds,
    modelConfigurationId: values.modelConfigurationId || null,
    contextTokenLimit: values.contextTokenLimit ? Number(values.contextTokenLimit) : null,
    outputTokenLimit: values.outputTokenLimit ? Number(values.outputTokenLimit) : null,
    iconName: values.avatarFileId || values.keepAvatar ? undefined : values.iconName,
    avatarFileId: values.avatarFileId ?? undefined,
    labelIds: values.labelIds,
    replaceBaseSystemPrompt: values.replaceBaseSystemPrompt,
    knowledgeCutoff: values.knowledgeCutoff ? `${values.knowledgeCutoff}T00:00:00Z` : undefined,
  };
}

const draftKey = (actorId: string) => `memoryos.agent-draft.${actorId}`;

/** A new agent's unsaved values kept in this browser; storage may be unavailable or hold an older shape. */
export function readDraft(actorId: string) {
  try {
    const raw = window.localStorage.getItem(draftKey(actorId));
    const parsed = raw ? agentValuesSchema.safeParse(JSON.parse(raw)) : undefined;
    return parsed?.success ? parsed.data : undefined;
  } catch {
    return undefined;
  }
}

export function writeDraft(actorId: string, values: AgentValues | undefined) {
  try {
    if (values) window.localStorage.setItem(draftKey(actorId), JSON.stringify(values));
    else window.localStorage.removeItem(draftKey(actorId));
  } catch {
    // Drafts are a convenience; storage may be unavailable.
  }
}
