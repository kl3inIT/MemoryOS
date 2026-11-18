import { z } from "zod";
import type { ListSourcePageData } from "@/lib/hey-api/types.gen";

type SourcePageParameters = NonNullable<ListSourcePageData["query"]>;

const optionalSearch = z
  .preprocess((value) => {
    if (typeof value !== "string") return undefined;
    const normalized = value.trim();
    return normalized || undefined;
  }, z.string().max(200).optional())
  .catch(undefined);

/** What the Sources page keeps in its address: the search, the filters and the page (from 0). */
export const sourcesSearchSchema = z.object({
  search: optionalSearch,
  status: z
    .enum(["NOT_STARTED", "INDEXING", "ACTIVE", "FAILED", "PAUSED", "PAUSING", "DELETING"])
    .optional()
    .catch(undefined),
  provider: z.enum(["FILE", "GOOGLE_DRIVE", "SHAREPOINT", "LARK"]).optional().catch(undefined),
  access: z.enum(["PUBLIC", "PRIVATE", "SYNC"]).optional().catch(undefined),
  page: z.coerce.number().int().min(0).default(0).catch(0),
});

export type SourcesSearch = z.output<typeof sourcesSearchSchema>;

export function sourcePageQuery(search: SourcesSearch): SourcePageParameters {
  return {
    search: search.search,
    status: search.status,
    type: search.provider,
    access: search.access,
    page: search.page,
  };
}
