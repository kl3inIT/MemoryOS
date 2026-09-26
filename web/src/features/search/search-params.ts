import { z } from "zod";

/** `SearchRequest.page` accepts 0–49. */
export const MAX_SEARCH_PAGES = 50;

const optional = <T extends z.ZodType>(schema: T) => schema.optional().catch(undefined);

/**
 * The Search page's address: the submitted question, its filters and the result page. Defaults are left out, so a
 * plain search reads `/search?q=…` and a reload or a shared link shows the same results.
 */
export const searchPageSearchSchema = z.object({
  q: optional(z.string().trim().min(1).max(1000)),
  type: optional(z.string().min(1).max(255)),
  source: optional(z.enum(["FILE", "GOOGLE_DRIVE", "SHAREPOINT"])),
  time: optional(z.enum(["7d", "30d", "365d"])),
  set: optional(z.string().min(1).max(64)),
  page: optional(
    z.coerce
      .number()
      .int()
      .min(1)
      .max(MAX_SEARCH_PAGES - 1),
  ),
});

export type SearchPageSearch = z.output<typeof searchPageSearchSchema>;
