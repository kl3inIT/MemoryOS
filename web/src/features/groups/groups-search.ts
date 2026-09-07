import { z } from "zod";

const optionalSearch = z
  .preprocess((value) => {
    if (typeof value !== "string") return undefined;
    const normalized = value.trim();
    return normalized || undefined;
  }, z.string().max(200).optional())
  .catch(undefined);

export const groupsSearchSchema = z.object({
  search: optionalSearch,
  page: z.coerce.number().int().min(0).catch(0),
  size: z.coerce
    .number()
    .pipe(z.union([z.literal(20), z.literal(50), z.literal(100)]))
    .catch(20),
});

export type GroupsSearch = z.output<typeof groupsSearchSchema>;
