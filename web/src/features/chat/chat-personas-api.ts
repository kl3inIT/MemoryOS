import { z } from "zod";
import { namedRefSchema, personSchema } from "@/features/identity/principals";
import { allPages } from "@/lib/all-pages";
import { listChatPersonas, listChatPersonaSources } from "@/lib/hey-api/sdk.gen";

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
  sources: z.array(namedRefSchema).default([]),
  documentSetIds: z.array(z.string().uuid()).default([]),
  documentSets: z.array(namedRefSchema).default([]),
  tools: z.array(z.string()).default([]),
  mcpServers: z.array(namedRefSchema).default([]),
  fileIds: z.array(z.string().uuid()).default([]),
  modelConfigurationId: z.string().uuid().nullish(),
  contextTokenLimit: z.number().nullish(),
  outputTokenLimit: z.number().nullish(),
  iconName: z.string().nullish(),
  hasAvatar: z.boolean().default(false),
  labels: z.array(namedRefSchema).default([]),
  owner: z
    .object({ actor: personSchema.nullish(), group: namedRefSchema.nullish() })
    .default({ actor: null, group: null }),
  vacant: z.boolean().default(false),
  userShares: z.array(z.object({ person: personSchema, permission: permissionSchema })).default([]),
  groupShares: z
    .array(z.object({ group: namedRefSchema, permission: permissionSchema }))
    .default([]),
  isPublic: z.boolean().default(false),
  publicPermission: permissionSchema.default("VIEWER"),
  listed: z.boolean().default(true),
  featured: z.boolean().default(false),
  displayPriority: z.number().nullish(),
  replaceBaseSystemPrompt: z.boolean().default(false),
  grounded: z.boolean().default(false),
  knowledgeCutoff: z.string().nullish(),
  pinned: z.boolean().default(false),
  deletedAt: z.string().nullish(),
});
export const agentLabelSchema = namedRefSchema;
export type AgentPermission = z.infer<typeof permissionSchema>;
export type AgentView = "ALL" | "MINE" | "SHARED";
export type Persona = z.infer<typeof personaSchema>;

/** The visibility shown on cards: Tenant-wide, shared with people or Groups, or private. */
export function agentVisibility(persona: Persona) {
  if (persona.builtin || persona.isPublic) return "public" as const;
  return persona.userShares.length > 0 || persona.groupShares.length > 0
    ? ("shared" as const)
    : ("private" as const);
}

export function loadPersonas(signal: AbortSignal, view: AgentView = "ALL") {
  return allPages(async (offset) =>
    personaSchema.array().parse(
      (
        await listChatPersonas({
          query: { offset, limit: 100, view },
          signal,
        })
      ).data,
    ),
  );
}
export function loadPersonaSources(signal: AbortSignal) {
  const schema = z.object({ id: z.string().uuid(), name: z.string(), type: z.string() }).array();
  return allPages(async (offset) =>
    schema.parse((await listChatPersonaSources({ query: { offset, limit: 100 }, signal })).data),
  );
}
