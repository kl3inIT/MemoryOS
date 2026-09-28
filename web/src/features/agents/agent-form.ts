import { z } from "zod";
import { agentTools, type Persona } from "@/features/chat/chat-personas-api";
import type { PersonaInput } from "@/lib/hey-api/types.gen";
import { zPersonaInput } from "@/lib/hey-api/zod.gen";
import type { ErrorMessage } from "@/lib/problem-presentation";

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
  grounded: z.boolean(),
});
export type AgentValues = z.infer<typeof agentValuesSchema>;

/** The bounds the API contract declares for a token limit; a contract without both is a generation error. */
function contractRange(schema: z.ZodNumber) {
  const { minValue: min, maxValue: max } = schema;
  if (min === null || max === null) throw new Error("The token limit contract declares no range.");
  return { min, max };
}

/** The token limits the API accepts; a limit left empty uses the model's own. */
export const tokenLimits = {
  contextTokenLimit: contractRange(zPersonaInput.shape.contextTokenLimit.unwrap().unwrap()),
  outputTokenLimit: contractRange(zPersonaInput.shape.outputTokenLimit.unwrap().unwrap()),
};

/** A token limit as typed: empty, or a whole number within `min` and `max`. */
function tokenLimit({ min, max }: { min: number; max: number }, message: ProblemMessage) {
  const set = (value: string) => value !== "";
  return z
    .string()
    .refine((value) => !set(value) || Number.isInteger(Number(value)), message({ key: "invalid" }))
    .refine(
      (value) => !set(value) || Number(value) >= min,
      message({ key: "min", params: { min } }),
    )
    .refine(
      (value) => !set(value) || Number(value) <= max,
      message({ key: "max", params: { max } }),
    );
}

type ProblemMessage = (message: ErrorMessage) => string;

/**
 * The editor's validation. A nameless agent cannot be submitted (the save action waits for a name), and the
 * starter list never outgrows what the API keeps; a token limit is checked against the range the API accepts, so
 * the field is marked before a request is sent. The server's field violations are placed on the controls after a
 * failed save.
 */
export function agentValidation(message: ProblemMessage) {
  return agentValuesSchema.extend({
    contextTokenLimit: tokenLimit(tokenLimits.contextTokenLimit, message),
    outputTokenLimit: tokenLimit(tokenLimits.outputTokenLimit, message),
  });
}

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
    grounded: agent?.grounded ?? false,
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
    grounded: values.grounded,
    knowledgeCutoff: values.knowledgeCutoff ? `${values.knowledgeCutoff}T00:00:00Z` : undefined,
  };
}

/** A draft kept before the grounded switch existed reads as not grounded. */
const draftSchema = agentValuesSchema.extend({ grounded: z.boolean().default(false) });

const draftKey = (actorId: string) => `memoryos.agent-draft.${actorId}`;

/** A new agent's unsaved values kept in this browser; storage may be unavailable or hold an older shape. */
export function readDraft(actorId: string) {
  try {
    const raw = window.localStorage.getItem(draftKey(actorId));
    const parsed = raw ? draftSchema.safeParse(JSON.parse(raw)) : undefined;
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
