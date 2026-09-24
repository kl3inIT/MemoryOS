import { z } from "zod";
import { namedRefSchema, personSchema } from "@/features/identity/principals";
import { allPages } from "@/lib/all-pages";
import { listDocumentSets } from "@/lib/hey-api/sdk.gen";

export const documentSetSchema = z.object({
  id: z.string().uuid(),
  revision: z.number().int(),
  name: z.string(),
  description: z.string(),
  isPublic: z.boolean().default(false),
  sourceIds: z.array(z.string().uuid()).default([]),
  userShares: z.array(personSchema).default([]),
  groupShares: z.array(namedRefSchema).default([]),
  sources: z.array(namedRefSchema).default([]),
  /** Sources in the set that the viewer may not select, shown only as a count. */
  hiddenSources: z.number().int().default(0),
  permissions: z.object({
    edit: z.boolean().default(false),
    share: z.boolean().default(false),
    delete: z.boolean().default(false),
    manage: z.boolean().default(false),
  }),
});
export type DocumentSet = z.infer<typeof documentSetSchema>;

/** Query-key prefix shared by every Document Set read, so one invalidation refreshes them all. */
export const documentSetsKey = ["document-sets"] as const;

export function loadDocumentSets(signal: AbortSignal) {
  return allPages(async (offset) =>
    documentSetSchema
      .array()
      .parse((await listDocumentSets({ query: { offset, limit: 100 }, signal })).data),
  );
}
