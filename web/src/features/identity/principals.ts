import type { PrincipalGroup, PrincipalPerson } from "@/lib/hey-api/types.gen";

/** A Group, label or other named row, as the sharing and picker endpoints return it. */
export type NamedRef = PrincipalGroup;
/** A Tenant member as sharing surfaces show them; some views leave the name or email out. */
export type Person = Pick<PrincipalPerson, "actorId"> & Partial<Omit<PrincipalPerson, "actorId">>;

export function personLabel(person: Person | null | undefined) {
  return person?.name || person?.email || person?.actorId.slice(0, 8) || "";
}
