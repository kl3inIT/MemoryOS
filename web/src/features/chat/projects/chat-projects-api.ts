import { z } from "zod";
import { allPages } from "@/lib/all-pages";
import { listChatProjects, moveChatProject } from "@/lib/hey-api/sdk.gen";

export const projectSchema = z.object({
  fileIds: z.array(z.string().uuid()).default([]),
  id: z.string().uuid(),
  name: z.string(),
  description: z.string(),
  instructions: z.string(),
  iconName: z.string().nullish(),
  revision: z.number().int(),
  updatedAt: z.string(),
});
export type Project = z.infer<typeof projectSchema>;

export function loadProjects(signal: AbortSignal) {
  return allPages(async (offset) =>
    projectSchema
      .array()
      .parse((await listChatProjects({ query: { offset, limit: 100 }, signal })).data),
  );
}

export async function moveConversation(sessionId: string, projectId: string | null) {
  await moveChatProject({
    path: { sessionId },
    body: { projectId },
    signal: AbortSignal.timeout(30000),
  });
}
