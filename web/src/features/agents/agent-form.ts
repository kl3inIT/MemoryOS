import { z } from "zod";
import { agentTools, type Persona } from "@/features/chat/chat-personas-api";
import { uiLocale } from "@/i18n/format";
import type { AppTranslate } from "@/i18n/use-app-translation";
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
  /** `model` sends no override, so the selected model's own limits apply. */
  limitsMode: z.enum(["model", "custom"]),
  contextTokenLimit: z.string(),
  outputTokenLimit: z.string(),
  replaceBaseSystemPrompt: z.boolean(),
  grounded: z.boolean(),
});
export type AgentValues = z.infer<typeof agentValuesSchema>;

/** The limits of a model the editor offers, as both model listings return them. */
export type ModelLimits = {
  id?: string;
  isDefault?: boolean;
  contextWindow: number;
  maxOutputTokens?: number | null;
};

/**
 * The model an agent's turns use: the selected one, or the Tenant default when none is selected ("Tự động"), as
 * the server resolves it.
 */
export function effectiveModel(models: ModelLimits[] | undefined, modelConfigurationId: string) {
  return models?.find((model) =>
    modelConfigurationId ? model.id === modelConfigurationId : model.isDefault,
  );
}

/**
 * The highest overrides the server accepts: the contract's range, narrowed by the effective model once it is
 * known. An output override also stays below the context window.
 */
export function limitCaps(model: ModelLimits | undefined) {
  const context = tokenLimits.contextTokenLimit.max;
  const output = tokenLimits.outputTokenLimit.max;
  if (model === undefined) return { context, output };
  return {
    context: Math.min(context, model.contextWindow),
    output: Math.min(output, model.maxOutputTokens ?? Infinity, model.contextWindow - 1),
  };
}

/** The bounds the API contract declares for a token limit; a contract without both is a generation error. */
function contractRange(schema: z.ZodNumber) {
  const { minValue: min, maxValue: max } = schema;
  if (min === null || max === null) throw new Error("The token limit contract declares no range.");
  return { min, max };
}

/** The token limits the API accepts for any model; the effective model's own limits narrow them further. */
export const tokenLimits = {
  contextTokenLimit: contractRange(zPersonaInput.shape.contextTokenLimit.unwrap().unwrap()),
  outputTokenLimit: contractRange(zPersonaInput.shape.outputTokenLimit.unwrap().unwrap()),
};

type ProblemMessage = (message: ErrorMessage) => string;

/**
 * The editor's validation. A nameless agent cannot be submitted (the save action waits for a name), and the
 * starter list never outgrows what the API keeps. Custom token limits must be whole numbers within the range the
 * API accepts and within the effective model's limits, as the server checks on every save, so the field is marked
 * before a request is sent. The server's field violations are placed on the controls after a failed save.
 */
export function agentValidation(
  ui: AppTranslate,
  message: ProblemMessage,
  models: ModelLimits[] | undefined,
) {
  return agentValuesSchema.superRefine((values, context) => {
    if (values.limitsMode !== "custom") return;
    const caps = limitCaps(effectiveModel(models, values.modelConfigurationId));
    const check = (path: keyof typeof tokenLimits, cap: number) => {
      const text = values[path];
      if (text === "") return;
      const value = Number(text);
      const { min, max } = tokenLimits[path];
      const refuse = (text: string) =>
        context.addIssue({ code: "custom", path: [path], message: text });
      if (!Number.isInteger(value)) refuse(message({ key: "invalid" }));
      else if (value < min) refuse(message({ key: "min", params: { min } }));
      else if (value > max) refuse(message({ key: "max", params: { max } }));
      else if (value > cap) refuse(ui("Tối đa {{v1}} token.", { v1: formatTokens(cap) }));
    };
    check("contextTokenLimit", caps.context);
    check("outputTokenLimit", caps.output);
  });
}

export function formatTokens(value: number) {
  return new Intl.NumberFormat(uiLocale()).format(value);
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
    limitsMode:
      agent?.contextTokenLimit == null && agent?.outputTokenLimit == null ? "model" : "custom",
    contextTokenLimit: agent?.contextTokenLimit?.toString() ?? "",
    outputTokenLimit: agent?.outputTokenLimit?.toString() ?? "",
    replaceBaseSystemPrompt: agent?.replaceBaseSystemPrompt ?? false,
    grounded: agent?.grounded ?? false,
  };
}

/** The create or update request for the editor's values. */
export function agentRequest(values: AgentValues, agent?: Persona): PersonaInput {
  const custom = values.limitsMode === "custom";
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
    contextTokenLimit: custom && values.contextTokenLimit ? Number(values.contextTokenLimit) : null,
    outputTokenLimit: custom && values.outputTokenLimit ? Number(values.outputTokenLimit) : null,
    iconName: values.avatarFileId || values.keepAvatar ? undefined : values.iconName,
    avatarFileId: values.avatarFileId ?? undefined,
    labelIds: values.labelIds,
    replaceBaseSystemPrompt: values.replaceBaseSystemPrompt,
    grounded: values.grounded,
    knowledgeCutoff: values.knowledgeCutoff ? `${values.knowledgeCutoff}T00:00:00Z` : undefined,
  };
}

/**
 * A draft kept before the grounded switch existed reads as not grounded; one kept before the limits mode existed
 * keeps any limits it carries as custom ones.
 */
const draftSchema = agentValuesSchema
  .extend({
    grounded: z.boolean().default(false),
    limitsMode: agentValuesSchema.shape.limitsMode.optional(),
  })
  .transform((draft): AgentValues => ({
    ...draft,
    limitsMode:
      draft.limitsMode ?? (draft.contextTokenLimit || draft.outputTokenLimit ? "custom" : "model"),
  }));

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
