import { sameOriginMutationHeaders } from "@/lib/api";
import { z } from "zod";
import {
  moveChatProject,
  listChatPersonas,
  listChatProjects,
  listChatPersonaSources,
} from "@/lib/hey-api/sdk.gen";

export const personaSchema = z.object({
  id: z.string().uuid(),
  builtin: z.boolean(),
  editable: z.boolean(),
  revision: z.number().int(),
  name: z.string(),
  description: z.string(),
  instructions: z.string(),
  starterPrompts: z.array(z.string()),
  sourceIds: z.array(z.string().uuid()),
  fileIds: z.array(z.string().uuid()).default([]),
  searchEnabled: z.boolean(),
  modelConfigurationId: z.string().uuid().nullish(),
  contextTokenLimit: z.number().nullish(),
  outputTokenLimit: z.number().nullish(),
});
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
export function loadPersonas(signal: AbortSignal) {
  return allPages(async (offset) =>
    personaSchema
      .array()
      .parse(
        (await listChatPersonas({ query: { offset, limit: 100 }, signal, throwOnError: true }))
          .data,
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
