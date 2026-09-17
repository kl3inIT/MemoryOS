import { sameOriginMutationHeaders } from "@/lib/api";
import { z } from "zod";
import {
  moveChatProject,
  listChatPersonas,
  listChatProjects,
  listChatPersonaSources,
} from "@/lib/hey-api/sdk.gen";

const refSchema = z.object({ id: z.string().uuid(), name: z.string() });
const personSchema = z.object({
  actorId: z.string().uuid(),
  name: z.string().nullish(),
  email: z.string().nullish(),
});
const permissionSchema = z.enum(["VIEWER", "EDITOR"]);
export const agentTools = ["search", "web_search", "image_generation", "code_interpreter"] as const;
export type AgentTool = (typeof agentTools)[number];

export const personaSchema = z.object({
  id: z.string().uuid(),
  builtin: z.boolean(),
  // Fail closed: an absent permission hint hides the control.
  permissions: z.object({
    edit: z.boolean().default(false),
    share: z.boolean().default(false),
    setPublic: z.boolean().default(false),
    delete: z.boolean().default(false),
    transfer: z.boolean().default(false),
    leave: z.boolean().default(false),
    manage: z.boolean().default(false),
  }),
  revision: z.number().int(),
  name: z.string(),
  description: z.string(),
  instructions: z.string(),
  taskPrompt: z.string().default(""),
  starterPrompts: z.array(z.string()),
  sourceIds: z.array(z.string().uuid()),
  sources: z.array(refSchema).default([]),
  tools: z.array(z.string()).default([]),
  mcpServers: z.array(refSchema).default([]),
  fileIds: z.array(z.string().uuid()).default([]),
  modelConfigurationId: z.string().uuid().nullish(),
  contextTokenLimit: z.number().nullish(),
  outputTokenLimit: z.number().nullish(),
  iconName: z.string().nullish(),
  hasAvatar: z.boolean().default(false),
  labels: z.array(refSchema).default([]),
  owner: z
    .object({ actor: personSchema.nullish(), group: refSchema.nullish() })
    .default({ actor: null, group: null }),
  vacant: z.boolean().default(false),
  userShares: z.array(z.object({ person: personSchema, permission: permissionSchema })).default([]),
  groupShares: z.array(z.object({ group: refSchema, permission: permissionSchema })).default([]),
  isPublic: z.boolean().default(false),
  publicPermission: permissionSchema.default("VIEWER"),
  listed: z.boolean().default(true),
  featured: z.boolean().default(false),
  displayPriority: z.number().nullish(),
  replaceBaseSystemPrompt: z.boolean().default(false),
  datetimeAware: z.boolean().default(true),
  knowledgeCutoff: z.string().nullish(),
  pinned: z.boolean().default(false),
  deletedAt: z.string().nullish(),
});
export const agentLabelSchema = refSchema;
export const shareOptionsSchema = z.object({
  people: z.array(personSchema),
  groups: z.array(refSchema),
});
export type AgentPermission = z.infer<typeof permissionSchema>;
export type AgentPerson = z.infer<typeof personSchema>;
export type AgentRef = z.infer<typeof refSchema>;
export type AgentView = "ALL" | "MINE" | "SHARED";

/** The visibility shown on cards: Tenant-wide, shared with people or Groups, or private. */
export function agentVisibility(persona: Persona) {
  if (persona.builtin || persona.isPublic) return "public" as const;
  return persona.userShares.length > 0 || persona.groupShares.length > 0
    ? ("shared" as const)
    : ("private" as const);
}

export function personLabel(person: AgentPerson | null | undefined) {
  return person?.name || person?.email || person?.actorId.slice(0, 8) || "";
}

export const projectSchema = z.object({
  fileIds: z.array(z.string().uuid()).default([]),
  id: z.string().uuid(),
  name: z.string(),
  description: z.string(),
  instructions: z.string(),
  revision: z.number().int(),
  updatedAt: z.string(),
});
export const sharingSchema = z.object({ enabled: z.boolean(), revision: z.number().int() });
export const feedbackSchema = z.object({
  assistantMessageId: z.string().uuid(),
  positive: z.boolean().nullable(),
  comment: z.string(),
  reason: z.string(),
});
export const branchSchema = z.object({
  id: z.string().uuid(),
  parentMessageId: z.string().uuid().nullable(),
  latestChildMessageId: z.string().uuid().nullable(),
});
export type Persona = z.infer<typeof personaSchema>;
export type Project = z.infer<typeof projectSchema>;
export type Feedback = z.infer<typeof feedbackSchema>;
export type Branch = z.infer<typeof branchSchema>;

// Pickers must not silently hide choices after the first page.
async function allPages<T>(load: (offset: number) => Promise<T[]>) {
  const items: T[] = [];
  for (let offset = 0; offset <= 10000; offset += 100) {
    const page = await load(offset);
    items.push(...page);
    if (page.length < 100) return items;
  }
  throw new Error("Danh sách vượt giới hạn tải. Hãy thu gọn trước khi chọn.");
}
export function loadPersonas(signal: AbortSignal, view: AgentView = "ALL") {
  return allPages(async (offset) =>
    personaSchema.array().parse(
      (
        await listChatPersonas({
          query: { offset, limit: 100, view },
          signal,
          throwOnError: true,
        })
      ).data,
    ),
  );
}
export function loadProjects(signal: AbortSignal) {
  return allPages(async (offset) =>
    projectSchema
      .array()
      .parse(
        (await listChatProjects({ query: { offset, limit: 100 }, signal, throwOnError: true }))
          .data,
      ),
  );
}
export function loadPersonaSources(signal: AbortSignal) {
  const schema = z.object({ id: z.string().uuid(), name: z.string(), type: z.string() }).array();
  return allPages(async (offset) =>
    schema.parse(
      (await listChatPersonaSources({ query: { offset, limit: 100 }, signal, throwOnError: true }))
        .data,
    ),
  );
}

export async function moveConversation(sessionId: string, projectId: string | null) {
  await moveChatProject({
    path: { sessionId },
    body: { projectId },
    headers: sameOriginMutationHeaders,
    signal: AbortSignal.timeout(30000),
    throwOnError: true,
  });
}
