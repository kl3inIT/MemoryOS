import { z } from "zod";

/** A Group, label or other named row, as the sharing and picker endpoints return it. */
export const namedRefSchema = z.object({ id: z.string().uuid(), name: z.string() });
/** A Tenant member as sharing surfaces show them. */
export const personSchema = z.object({
  actorId: z.string().uuid(),
  name: z.string().nullish(),
  email: z.string().nullish(),
});
/** The active members and ordinary Groups a search for principals returns. */
export const principalOptionsSchema = z.object({
  people: z.array(personSchema),
  groups: z.array(namedRefSchema),
});
export type Person = z.infer<typeof personSchema>;
export type NamedRef = z.infer<typeof namedRefSchema>;

export function personLabel(person: Person | null | undefined) {
  return person?.name || person?.email || person?.actorId.slice(0, 8) || "";
}
