import { z } from "zod";
import type { Persona } from "@/features/chat/chat-personas-api";
import type { PersonaInput } from "@/lib/hey-api/types.gen";
import { randomAvatarSeed } from "./agent-face";

/** A conversation starter list longer than this is refused by the API. */
export const maxStarterPrompts = 8;

/** The tools a new agent starts with, as the API gives one created without a choice: documents and the web. */
const defaultAgentTools = ["search", "web_search"];

/** The longest title and greeting the API keeps. */
export const maxTitle = 80;
export const maxGreeting = 500;

/** The reasoning levels an agent may set; empty leaves the level to the conversation, the member and the model. */
const reasoningEfforts = ["OFF", "LOW", "MEDIUM", "HIGH"] as const;
export type AgentReasoningEffort = (typeof reasoningEfforts)[number];

/**
 * The editor's values, named as the request fields they become so that an `ApiProblem` field violation lands on
 * its control. The same shape is kept as a browser draft.
 */
const agentValuesSchema = z.object({
  name: z.string(),
  title: z.string(),
  description: z.string(),
  avatarSeed: z.string(),
  avatarFileId: z.string().nullable(),
  keepAvatar: z.boolean(),
  labelIds: z.array(z.string()),
  instructions: z.string(),
  greeting: z.string(),
  starterPrompts: z.array(z.string()),
  sourceIds: z.array(z.string()),
  documentSetIds: z.array(z.string()),
  fileIds: z.array(z.string()),
  tools: z.array(z.string()),
  mcpServerIds: z.array(z.string()),
  modelConfigurationId: z.string(),
  reasoningEffort: z.enum(reasoningEfforts).or(z.literal("")),
  grounded: z.boolean(),
});
export type AgentValues = z.infer<typeof agentValuesSchema>;

/**
 * The editor's validation. A nameless agent cannot be submitted (the save action waits for a name), the starter
 * list never outgrows what the API keeps and the text fields stop at the API's limits, so only the shape is checked
 * here; the server's field violations are placed on the controls after a failed save.
 */
export const agentValidation = agentValuesSchema;

export function initialValues(agent?: Persona): AgentValues {
  return {
    name: agent?.name ?? "",
    title: agent?.title ?? "",
    description: agent?.description ?? "",
    avatarSeed: agent?.avatarSeed ?? randomAvatarSeed(),
    avatarFileId: null,
    keepAvatar: agent?.hasAvatar ?? false,
    labelIds: agent?.labels.map((label) => label.id) ?? [],
    instructions: agent?.instructions ?? "",
    greeting: agent?.greeting ?? "",
    starterPrompts: agent?.starterPrompts ?? [],
    sourceIds: agent?.sourceIds ?? [],
    documentSetIds: agent?.documentSetIds ?? [],
    fileIds: agent?.fileIds ?? [],
    tools: agent?.tools ?? [...defaultAgentTools],
    mcpServerIds: agent?.mcpServers.map((server) => server.id) ?? [],
    modelConfigurationId: agent?.modelConfigurationId ?? "",
    reasoningEffort: agent?.reasoningEffort ?? "",
    grounded: agent?.grounded ?? false,
  };
}

/** The create or update request for the editor's values. */
export function agentRequest(values: AgentValues, agent?: Persona): PersonaInput {
  return {
    name: values.name.trim(),
    title: values.title.trim() || null,
    description: values.description,
    instructions: values.instructions,
    greeting: values.greeting.trim() || null,
    starterPrompts: values.starterPrompts.map((prompt) => prompt.trim()).filter(Boolean),
    sourceIds: values.sourceIds,
    documentSetIds: values.documentSetIds,
    tools: values.tools,
    // The built-in agent uses every MCP server and file its reader may use.
    mcpServerIds: agent?.builtin ? [] : values.mcpServerIds,
    fileIds: agent?.builtin ? undefined : values.fileIds,
    modelConfigurationId: values.modelConfigurationId || null,
    // An absent level is a cleared one; the generated contract has no null for it.
    reasoningEffort: values.reasoningEffort || undefined,
    avatarSeed: values.avatarSeed,
    avatarFileId: values.avatarFileId ?? undefined,
    // An image that was saved and is now neither kept nor replaced is dropped, so the face shows.
    removeAvatar: !!agent?.hasAvatar && !values.keepAvatar && !values.avatarFileId,
    labelIds: values.labelIds,
    grounded: values.grounded,
  };
}

/**
 * A draft kept before the grounded switch existed reads as not grounded, one kept before faces gets a new face, and
 * one kept before titles, greetings and levels has none; fields a draft kept that the editor no longer has are dropped.
 */
const draftSchema = agentValuesSchema.extend({
  grounded: z.boolean().default(false),
  avatarSeed: z.string().default(() => randomAvatarSeed()),
  title: z.string().default(""),
  greeting: z.string().default(""),
  reasoningEffort: z.enum(reasoningEfforts).or(z.literal("")).default(""),
});

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
